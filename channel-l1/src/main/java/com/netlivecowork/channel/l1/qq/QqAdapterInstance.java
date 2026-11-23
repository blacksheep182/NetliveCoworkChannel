package com.netlivecowork.channel.l1.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.adapter.*;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.port.AdapterHost;
import com.netlivecowork.channel.contract.port.DeliveryPort;
import com.netlivecowork.channel.infra.Digests;
import com.netlivecowork.channel.l1.render.InteractionReplyContext;
import com.netlivecowork.channel.l1.render.PlainTextRenderer;
import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** One QQ bot account. Gateway sequence is saved only after an event is accepted or rejected durably. */
public final class QqAdapterInstance implements AdapterInstance, ConnectionPort, DeliveryPort {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(QqAdapterInstance.class);
    private static final String KEY_SESSION = "qq.gateway.session";
    private static final long GROUP_REPLY_SECONDS = 300;
    private static final long C2C_REPLY_SECONDS = 3600;
    private final AdapterInstanceConfig config;
    private final AdapterHost host;
    private final QqProtocol protocol;
    private final InteractionReplyContext replies;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private final AtomicLong events = new AtomicLong();
    private volatile boolean active;
    private volatile boolean ready;
    private volatile long generation;
    private volatile long heartbeatMillis = 30_000;
    private volatile long lastHeartbeatAck;
    private volatile WebSocket socket;
    private volatile Thread heartbeats;
    private volatile String sessionId;
    private volatile long sessionSequence = -1;

    QqAdapterInstance(AdapterInstanceConfig config, AdapterHost host, QqProtocol protocol) {
        this.config = config; this.host = host; this.protocol = protocol;
        this.replies = new InteractionReplyContext(host.protocolState());
    }
    @Override public String entryRef() { return config.entryRef(); }
    @Override public ConnectionPort connection() { return this; }
    @Override public DeliveryPort delivery() { return this; }
    @Override public CompletionStage<ActivationResult> activate() {
        String appId=config.settings().path("appId").asText("");
        if(config.expectedExternalAccountKey()!=null && !config.expectedExternalAccountKey().equals(appId))
            return CompletableFuture.failedFuture(new ChannelContractException(ChannelErrorCode.ACCOUNT_MISMATCH,
                "QQ AppID does not match the configured bot account"));
        active = true;
        var saved = host.protocolState().get(KEY_SESSION).toCompletableFuture().join();
        if (saved != null && appId.equals(saved.value().path("appId").asText())) {
            sessionId = saved.value().path("sessionId").asText(null);
            sessionSequence = saved.value().path("sequence").asLong(-1);
        }
        // The configured AppID is only verified after QQ accepts IDENTIFY or RESUME.
        return CompletableFuture.completedFuture(new ActivationResult(QqAdapterFactory.capabilities(),null));
    }
    @Override public CompletionStage<Void> deactivate(StopReason reason) {
        active = false; closeSocket(); executor.shutdownNow();
        return CompletableFuture.completedFuture(null);
    }
    @Override public CompletionStage<ConnectResult> connect(ConnectionAttempt attempt) {
        if (!active) return CompletableFuture.failedFuture(new IllegalStateException("QQ instance is inactive"));
        if (attempt.generation() < generation) return CompletableFuture.completedFuture(new ConnectResult.Connecting());
        generation = attempt.generation(); ready = false;
        closeSocket();
        Thread.startVirtualThread(() -> open(attempt.generation()));
        return CompletableFuture.completedFuture(new ConnectResult.Connecting());
    }
    private void open(long attempt) {
        try {
            URI uri = protocol.gateway();
            if (!active || generation != attempt) return;
            var listener = new Listener(attempt);
            var ws = protocol.client().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(20)).buildAsync(uri, listener).join();
            if (!active || generation != attempt || listener.closed) { ws.abort(); return; }
            socket = ws;
        } catch (RuntimeException e) {
            if (active && generation == attempt) {
                ChannelErrorCode reason=code(e);
                report(reason==ChannelErrorCode.AUTH_REQUIRED
                    ? new ConnectionEvent.AuthRequired(attempt,events.incrementAndGet(),reason.error("QQ credential is unavailable"))
                    : new ConnectionEvent.Disconnected(attempt,events.incrementAndGet(),reason.error("QQ gateway connection failed")));
            }
        }
    }
    @Override public CompletionStage<Void> disconnect(long ignored, StopReason reason) {
        ready = false; closeSocket(); return CompletableFuture.completedFuture(null);
    }
    @Override public CompletionStage<ProbeResult> probe(ConnectionAttempt attempt) {
        if (!active || generation != attempt.generation() || !ready || socket == null)
            return CompletableFuture.completedFuture(ProbeResult.unhealthy(ChannelErrorCode.UNAVAILABLE.error("QQ gateway is offline")));
        return CompletableFuture.completedFuture(System.currentTimeMillis() - lastHeartbeatAck < heartbeatMillis * 3
            ? ProbeResult.healthy()
            : ProbeResult.unhealthy(ChannelErrorCode.UNAVAILABLE.error("QQ gateway heartbeat is stale")));
    }

    private final class Listener implements WebSocket.Listener {
        private final long attempt;
        private final StringBuilder buffer = new StringBuilder();
        private volatile boolean closed;
        private Listener(long attempt) { this.attempt = attempt; }
        @Override public void onOpen(WebSocket ws) {
            if (!active || generation != attempt) { closed = true; ws.abort(); return; }
            socket = ws; ws.request(1);
        }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (!last) { ws.request(1); return CompletableFuture.completedFuture(null); }
            String frame = buffer.toString(); buffer.setLength(0);
            try {
                executor.execute(() -> {
                    try { handle(ws, attempt, Json.read(frame)); ws.request(1); }
                    catch (RuntimeException e) {
                        log.warn("QQ gateway event could not be committed; reconnecting for replay: {}", e.toString());
                        closed = true; ws.abort(); disconnected(attempt, -1);
                    }
                });
            } catch (RuntimeException e) { closed = true; ws.abort(); disconnected(attempt, -1); }
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            if (!closed) { closed = true; disconnected(attempt, status); }
            return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket ws, Throwable error) {
            if (!closed) { closed = true; disconnected(attempt, -1); }
        }
    }

    private void handle(WebSocket ws, long attempt, JsonNode frame) {
        if (!active || generation != attempt) return;
        int op = frame.path("op").asInt(-1);
        if (op == 10) {
            heartbeatMillis = Math.max(1_000, frame.path("d").path("heartbeat_interval").asLong(30_000));
            if (sessionId != null && sessionSequence >= 0) {
                sendWs(ws, Json.obj().put("op", 6).set("d", Json.obj().put("token", "QQBot " + protocol.token())
                    .put("session_id", sessionId).put("seq", sessionSequence)));
            } else identify(ws);
            startHeartbeat(ws, attempt);
        } else if (op == 0) {
            String type = frame.path("t").asText("");
            if ("READY".equals(type)) {
                sessionId = frame.path("d").path("session_id").asText(null); ready = true;
                lastHeartbeatAck = System.currentTimeMillis();
                report(new ConnectionEvent.Connected(attempt, events.incrementAndGet(),
                    config.settings().path("appId").asText()));
            } else if ("RESUMED".equals(type)) {
                ready = true; lastHeartbeatAck = System.currentTimeMillis();
                report(new ConnectionEvent.Connected(attempt, events.incrementAndGet(),
                    config.settings().path("appId").asText()));
            } else if ("GROUP_AT_MESSAGE_CREATE".equals(type) || "GROUP_MESSAGE_CREATE".equals(type)
                || "C2C_MESSAGE_CREATE".equals(type)) {
                inbound(type, frame.path("d"));
            }
            if (frame.path("s").canConvertToLong()) {
                sessionSequence = frame.path("s").asLong(); saveSession();
            }
        } else if (op == 11) {
            lastHeartbeatAck = System.currentTimeMillis();
            if (ready) report(new ConnectionEvent.Heartbeat(attempt, events.incrementAndGet()));
        } else if (op == 9) {
            sessionId = null; sessionSequence = -1; saveSession(); ws.abort();
        } else if (op == 7) ws.abort();
    }
    private void identify(WebSocket ws) {
        var data = Json.obj().put("token", "QQBot " + protocol.token()).put("intents", 1 << 25);
        data.putArray("shard").add(0).add(1);
        data.set("properties", Json.obj().put("$os", "windows").put("$browser", "NetliveCoworkChannel")
            .put("$device", "NetliveCoworkChannel"));
        sendWs(ws, Json.obj().put("op", 2).set("d", data));
    }
    private void startHeartbeat(WebSocket ws, long attempt) {
        Thread prior = heartbeats; if (prior != null) prior.interrupt();
        heartbeats = Thread.startVirtualThread(() -> {
            while (active && generation == attempt && socket == ws) {
                try {
                    Thread.sleep(heartbeatMillis);
                    var frame = Json.obj().put("op", 1);
                    if (sessionSequence < 0) frame.putNull("d"); else frame.put("d", sessionSequence);
                    sendWs(ws, frame);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                catch (RuntimeException e) { ws.abort(); return; }
            }
        });
    }
    private void saveSession() {
        var prior = host.protocolState().get(KEY_SESSION).toCompletableFuture().join();
        var data = Json.obj().put("appId",config.settings().path("appId").asText())
            .put("sequence", sessionSequence);
        if (sessionId != null) data.put("sessionId", sessionId);
        host.protocolState().put(KEY_SESSION, prior == null ? null : prior.revision(), data)
            .toCompletableFuture().join();
    }
    private void disconnected(long attempt, int status) {
        if (!active || generation != attempt) return;
        ready = false; socket = null;
        Thread beat = heartbeats; if (beat != null) beat.interrupt();
        if (status == 4004) protocol.invalidateToken();
        if (status == 4006 || status == 4007 || status == 4009) {
            sessionId = null; sessionSequence = -1; saveSession();
        }
        report(new ConnectionEvent.Disconnected(attempt, events.incrementAndGet(),
            ChannelErrorCode.UNAVAILABLE.error("QQ gateway disconnected")));
    }
    private void closeSocket() {
        Thread beat = heartbeats; if (beat != null) beat.interrupt();
        WebSocket ws = socket; socket = null; ready = false;
        if (ws != null) ws.abort();
    }
    private static void sendWs(WebSocket ws, JsonNode frame) { ws.sendText(Json.write(frame), true).join(); }
    private void report(ConnectionEvent event) {
        try { host.events().report(event).toCompletableFuture().get(5, TimeUnit.SECONDS); }
        catch (Exception e) { log.warn("QQ connection fact could not be reported: {}", e.toString()); }
    }
    private static ChannelErrorCode code(Throwable e) {
        return e instanceof QqProtocol.PlatformFailure f ? f.failure.code() : ChannelErrorCode.UNAVAILABLE;
    }

    void inbound(String type, JsonNode message) {
        if (message.path("author").path("bot").asBoolean(false)) return;
        boolean group = !"C2C_MESSAGE_CREATE".equals(type);
        String id = message.path("id").asText("");
        String conversation = group ? message.path("group_openid").asText("") :
            message.path("author").path("user_openid").asText("");
        String sender = group ? message.path("author").path("member_openid").asText("") : conversation;
        if (id.isBlank() || conversation.isBlank() || sender.isBlank())
            throw new IllegalArgumentException("QQ message has no verified sender or destination");
        String text = message.path("content").asText("").strip();
        if (text.isBlank()) return;
        String key = "qq-" + Digests.sha256(config.settings().path("appId").asText() + "\u0000" + id);
        String digest = Digests.sha256(text);
        InboundDisposition prior = host.dispositions().find(key).toCompletableFuture().join();
        if (prior != null) {
            if (prior.expired()) host.dispositions().markGap(key, "retention expired").toCompletableFuture().join();
            else if (!prior.sameContent(digest)) log.warn("QQ inbound message ID has conflicting content");
            return;
        }
        rememberReply(group, conversation, id);
        var verified = new VerifiedIngress(key, config.settings().path("appId").asText(),
            new VerifiedIngress.Subject(VerifiedIngress.SubjectKind.USER,
                config.settings().path("appId").asText() + ":" + (group ? conversation + ":" : "c2c:") + sender),
            new VerifiedIngress.ExternalScope(group ? "qq-group" : "qq", conversation),
            new VerifiedIngress.ExternalReplyTarget(group ? VerifiedIngress.Audience.SHARED :
                VerifiedIngress.Audience.PERSONAL, conversation, Json.obj().put("conversation", conversation).put("group", group)),
            id, null);
        try {
            if (text.equals("/bind") || text.startsWith("/bind ")) {
                host.ingress().completeOwnerBinding(verified, text.length() > 6 ? text.substring(6).strip() : "")
                    .toCompletableFuture().join();
                return;
            }
            IngressResolution resolved = host.ingress().resolve(verified).toCompletableFuture().join();
            var response = host.ingress().submit(resolved.admissionRef(), new IngressRequest(
                IngressRequest.SUPPORTED_VERSION, key, resolved.identity(), resolved.routing(),
                interactionOrText(key, conversation, text))).toCompletableFuture().join();
            if (response instanceof ChannelResponse.Accepted) return;
            if (response instanceof ChannelResponse.Completed completed) {
                replyToInbound(group, conversation, commandText(completed.result()));
                return;
            }
            ChannelErrorCode reason = response instanceof ChannelResponse.Rejected r ? r.error().errorCode() :
                response instanceof ChannelResponse.Unknown u ? u.error().errorCode() : ChannelErrorCode.UNAVAILABLE;
            if (!permanent(reason)) throw new IllegalStateException("QQ inbound submit is retryable");
            reject(key, digest, reason);
            if (reason == ChannelErrorCode.INVALID_ARGUMENT)
                replyToInbound(group, conversation, "输入或目标无效。发送 /workspaces 查看工作空间，或检查命令格式。");
        } catch (RuntimeException e) {
            ChannelErrorCode reason = contractCode(e);
            if (reason == null || !permanent(reason)) throw e;
            reject(key, digest, reason);
            if (reason == ChannelErrorCode.INVALID_ARGUMENT)
                replyToInbound(group, conversation, "输入或目标无效。发送 /workspaces 查看工作空间，或检查命令格式。");
        }
    }

    private void replyToInbound(boolean group, String conversation, String text) {
        if (text == null || text.isBlank()) return;
        List<String> parts = chunks(text);
        ReplyAnchor anchor = takeReply(group, conversation, parts.size());
        for (int i = 0; i < parts.size(); i++)
            protocol.send(group, conversation, anchor.id(), anchor.sequence() + i, parts.get(i), Duration.ofSeconds(10));
    }

    static String commandText(OperationResult result) {
        if (result instanceof OperationResult.WorkspaceList list) {
            if (list.items().isEmpty()) return "当前群没有可用的工作空间。";
            var lines = new ArrayList<String>();
            lines.add("可用工作空间：");
            for (int i = 0; i < list.items().size(); i++)
                lines.add((i + 1) + ". " + list.items().get(i).label());
            lines.add("发送 /workspace 1 选择工作空间。");
            return String.join("\n", lines);
        }
        if (result instanceof OperationResult.WorkspaceSelect) return "已选择工作空间。发送 /new <首条消息> 创建会话。";
        if (result instanceof OperationResult.SessionList list) {
            if (list.items().isEmpty()) return "当前工作空间没有群会话。发送 /new <首条消息> 创建。";
            var lines = new ArrayList<String>();
            lines.add("群会话：");
            for (int i = 0; i < list.items().size(); i++)
                lines.add((i + 1) + ". " + list.items().get(i).label());
            lines.add("发送 /use 1 选择会话。");
            return String.join("\n", lines);
        }
        if (result instanceof OperationResult.SessionSelect) return "已选择会话，可以直接发送消息。";
        if (result instanceof OperationResult.SessionCreate) return "会话已创建，首条消息正在处理。";
        if (result instanceof OperationResult.SessionResume) return "已请求恢复会话。";
        if (result instanceof OperationResult.TaskGet task) return "任务状态：" + task.task().status();
        if (result instanceof OperationResult.InteractionRespond) return "已提交回复。";
        if (result instanceof OperationResult.TaskCancel) return "已提交取消请求。";
        return null;
    }
    private ChannelOperation interactionOrText(String key, String conversation, String text) {
        var answer = replies.reply(config.settings().path("appId").asText(), conversation, key, text);
        return answer != null ? answer : new ChannelOperation.InputSubmit(null,
            ChannelOperation.Interpretation.COMMAND_AWARE, List.of(new ContentPart.Text("p1", text)), null);
    }
    private void reject(String key, String digest, ChannelErrorCode reason) {
        DispositionResult result = host.dispositions().recordRejected(key, digest, reason.name())
            .toCompletableFuture().join();
        if (result == DispositionResult.FAILED) throw new IllegalStateException("QQ rejection was not persisted");
    }
    private static ChannelErrorCode contractCode(Throwable e) {
        for (Throwable current = e; current != null; current = current.getCause())
            if (current instanceof ChannelContractException contract) return contract.code();
        return null;
    }
    private static boolean permanent(ChannelErrorCode c) {
        return switch (c) {
            case IDENTITY_UNBOUND, INVALID_ARGUMENT, UNSUPPORTED_VERSION, UNSUPPORTED_CAPABILITY,
                UNSUPPORTED_CONTENT, PERMISSION_DENIED, ACCOUNT_MISMATCH, REQUEST_CONFLICT,
                INTERACTION_STALE, BINDING_CONFLICT, DESTINATION_STALE -> true;
            default -> false;
        };
    }

    private String replyKey(boolean group, String conversation) {
        return "qq.reply." + Digests.sha256(config.settings().path("appId").asText()+"\u0000"+
            (group ? "group:" : "c2c:") + conversation);
    }
    private synchronized void rememberReply(boolean group, String conversation, String id) {
        String key = replyKey(group, conversation);
        var prior = host.protocolState().get(key).toCompletableFuture().join();
        if (prior != null && id.equals(prior.value().path("id").asText())) return;
        var data = Json.obj().put("id", id).put("nextSequence", 1).put("expiresAt",
            Instant.now().plusSeconds(group ? GROUP_REPLY_SECONDS : C2C_REPLY_SECONDS).toString());
        host.protocolState().put(key, prior == null ? null : prior.revision(), data).toCompletableFuture().join();
    }
    private synchronized ReplyAnchor takeReply(boolean group, String conversation, int count) {
        String key = replyKey(group, conversation);
        var prior = host.protocolState().get(key).toCompletableFuture().join();
        if (prior == null || !Instant.parse(prior.value().path("expiresAt").asText()).isAfter(Instant.now()))
            throw new ChannelContractException(ChannelErrorCode.DESTINATION_STALE, "QQ passive reply window expired");
        int sequence = prior.value().path("nextSequence").asInt(1);
        if (sequence + count - 1 > 5) throw new ChannelContractException(ChannelErrorCode.RATE_LIMITED,
            "QQ allows at most five replies to one message");
        String id = prior.value().path("id").asText("");
        var data = ((com.fasterxml.jackson.databind.node.ObjectNode) prior.value().deepCopy());
        data.put("nextSequence", sequence + count);
        host.protocolState().put(key, prior.revision(), data).toCompletableFuture().join();
        return new ReplyAnchor(id, sequence);
    }
    private record ReplyAnchor(String id, int sequence) {}

    static List<String> chunks(String text) {
        var result=new ArrayList<String>(); var part=new StringBuilder(); int bytes=0;
        for(int offset=0;offset<text.length();) {
            int point=text.codePointAt(offset);String unit=new String(Character.toChars(point));
            int size=unit.getBytes(StandardCharsets.UTF_8).length;
            if(bytes+size>1900 && !part.isEmpty()) {
                result.add(part.toString());part.setLength(0);bytes=0;
            }
            part.append(unit);bytes+=size;offset+=Character.charCount(point);
        }
        if(!part.isEmpty())result.add(part.toString());
        if(result.size()>5)throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CONTENT,
            "QQ text exceeds the five-reply limit");
        return result;
    }

    @Override public CompletionStage<DeliveryReceipt> deliver(ChannelDelivery delivery, DeliveryAttempt attempt) {
        return CompletableFuture.supplyAsync(() -> {
            String id = delivery.deliveryId();
            if (!active || !ready) return new DeliveryReceipt.Failed(id,
                ChannelErrorCode.UNAVAILABLE.error("QQ gateway is offline"));
            if (!entryRef().equals(delivery.routing().entryRef())) return new DeliveryReceipt.Failed(id,
                ChannelErrorCode.INVALID_ARGUMENT.error("QQ delivery belongs to another entry"));
            String text;
            try { text = PlainTextRenderer.deliveryText(delivery); }
            catch (PlainTextRenderer.UnsupportedContent e) { return new DeliveryReceipt.Failed(id,
                ChannelErrorCode.UNSUPPORTED_CONTENT.error(e.getMessage())); }
            int sent=0;
            try {
                ResolvedDestination dest = host.destinations().resolve(delivery.routing().destinationRef(),
                    delivery.routing().destinationRevision()).toCompletableFuture().join();
                boolean group = dest.protocolData().path("group").asBoolean(false);
                String conversation = dest.protocolData().path("conversation").asText("");
                if (conversation.isBlank()) throw new ChannelContractException(ChannelErrorCode.DESTINATION_STALE,
                    "QQ destination has no conversation");
                String full=text+replies.delivered(config.settings().path("appId").asText(), conversation,
                    delivery.packet());
                List<String> parts=chunks(full);
                ReplyAnchor anchor = takeReply(group, conversation, parts.size());
                String receipt=null;
                for(String part:parts) {
                    receipt = protocol.send(group, conversation, anchor.id(), anchor.sequence()+sent,
                        part, Duration.ofMillis(Math.max(5_000, attempt.timeoutMs())));
                    sent++;
                }
                return new DeliveryReceipt.Accepted(id, receipt);
            } catch (QqProtocol.PlatformFailure e) {
                var error = e.failure.code().error(e.getMessage());
                return e.failure.unknown() || sent>0 ? new DeliveryReceipt.Unknown(id,
                    ChannelErrorCode.OUTCOME_UNKNOWN.error("QQ reply was only partly confirmed")) :
                    new DeliveryReceipt.Failed(id, error);
            } catch (RuntimeException e) {
                if(sent>0)return new DeliveryReceipt.Unknown(id,
                    ChannelErrorCode.OUTCOME_UNKNOWN.error("QQ reply was only partly confirmed"));
                ChannelErrorCode code = contractCode(e);
                return new DeliveryReceipt.Failed(id, (code == null ? ChannelErrorCode.DESTINATION_STALE : code)
                    .error("QQ reply destination is unavailable"));
            }
        });
    }
}
