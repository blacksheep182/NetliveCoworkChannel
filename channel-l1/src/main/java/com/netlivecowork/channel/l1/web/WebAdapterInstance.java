package com.netlivecowork.channel.l1.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelDelivery;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.ChannelOperation;
import com.netlivecowork.channel.contract.ChannelResponse;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.DeliveryAttempt;
import com.netlivecowork.channel.contract.DeliveryInbox;
import com.netlivecowork.channel.contract.DeliveryReceipt;
import com.netlivecowork.channel.contract.EventPayload;
import com.netlivecowork.channel.contract.EventType;
import com.netlivecowork.channel.contract.IngressRequest;
import com.netlivecowork.channel.contract.IngressResolution;
import com.netlivecowork.channel.contract.VerifiedIngress;
import com.netlivecowork.channel.contract.adapter.ActivationResult;
import com.netlivecowork.channel.contract.adapter.AdapterInstance;
import com.netlivecowork.channel.contract.adapter.AdapterInstanceConfig;
import com.netlivecowork.channel.contract.adapter.StopReason;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.json.PacketJson;
import com.netlivecowork.channel.contract.port.AdapterHost;
import com.netlivecowork.channel.contract.port.DeliveryPort;
import com.netlivecowork.channel.l1.render.PlainTextRenderer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Web / 小程序实例。被动入口：令牌 → 调用者引用，入站统一走 resolve + submit；
 * 出站经 L2 公共设施持久接管，客户端以不透明游标补读并独立确认（INV-75～78）。
 */
public final class WebAdapterInstance implements AdapterInstance, DeliveryPort, com.netlivecowork.channel.contract.port.DeliveryQueryPort {

    /** 客户端轮询的已投递条目：sourceLabel 单独返回，避免与正文混淆（INV-40）。 */
    public record Delivered(long sequence, String type, String sourceLabel, String text, String targetRef, String messageRef, com.netlivecowork.channel.contract.EventPacket packet) {
        public Delivered(long sequence,String type,String sourceLabel,String text,String targetRef,String messageRef) {
            this(sequence,type,sourceLabel,text,targetRef,messageRef,null);
        }
        public Delivered(long sequence, String type, String sourceLabel, String text, String targetRef) {
            this(sequence,type,sourceLabel,text,targetRef,null);
        }
        public Delivered(long sequence, String type, String sourceLabel, String text) {
            this(sequence, type, sourceLabel, text, null);
        }
    }

    private final AdapterInstanceConfig config;
    private final AdapterHost host;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final String streamId = java.util.UUID.randomUUID().toString();
    private final Map<String, String> users = new ConcurrentHashMap<>();
    private final Object lifecycle = new Object();
    private final Object usersLock = new Object();

    private volatile State state = State.NEW;
    private volatile boolean usersLoaded;

    public WebAdapterInstance(AdapterInstanceConfig config, AdapterHost host) {
        this.config = Objects.requireNonNull(config, "config");
        this.host = Objects.requireNonNull(host, "host");
    }

    private enum State {
        NEW, ACTIVE, STOPPED
    }

    @Override
    public String entryRef() {
        return config.entryRef();
    }

    @Override
    public DeliveryPort delivery() {
        return this;
    }

    @Override
    public CompletionStage<ActivationResult> activate() {
        synchronized (lifecycle) {
            if (state == State.STOPPED) {
                return CompletableFuture.failedFuture(new IllegalStateException("web instance is stopped"));
            }
            if(host.inbox()==null) return CompletableFuture.failedFuture(new IllegalStateException("Persistent inbox facility is required"));
            host.inbox().verifyStorage().toCompletableFuture().join();
            state = State.ACTIVE;
        }
        return CompletableFuture.completedFuture(
            new ActivationResult(WebAdapterFactory.capabilities(), "web:" + config.entryRef()));
    }

    @Override
    public CompletionStage<Void> deactivate(StopReason reason) {
        synchronized (lifecycle) {
            if (state == State.STOPPED) {
                return CompletableFuture.completedFuture(null);
            }
            state = State.STOPPED;
        }
        executor.shutdown();
        return CompletableFuture.completedFuture(null);
    }

    // ---------------------------------------------------------------- 入站

    /**
     * 入站提交：令牌只用于定位调用者引用，客户端不能自报 callerRef（INV-09）。
     *
     * @throws SecurityException        令牌未知或为空
     * @throws IllegalArgumentException requestId 或正文为空
     */
    public ChannelResponse submitInbound(String presentedToken, String requestId, String text) {
        return submitInbound(presentedToken, requestId, text, null);
    }

    /** Explicit target pins this input to the user's confirmed target; L2 still authorizes it. */
    public ChannelResponse submitInbound(String presentedToken, String requestId, String text, String targetRef) {
        return submitText(presentedToken, requestId, text, targetRef, ChannelOperation.Interpretation.COMMAND_AWARE);
    }

    /** Normal chat composition is literal input, including text beginning with a slash. */
    public ChannelResponse submitChat(String token, String requestId, String text, String targetRef) {
        if (targetRef == null || targetRef.isBlank()) throw new IllegalArgumentException("targetRef is required");
        return submitText(token, requestId, text, targetRef, ChannelOperation.Interpretation.LITERAL);
    }

    private ChannelResponse submitText(String presentedToken, String requestId, String text, String targetRef,
                                       ChannelOperation.Interpretation interpretation) {
        String callerRef = authenticate(presentedToken);
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is required");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        if (state != State.ACTIVE) {
            return new ChannelResponse.Rejected(requestId,
                ChannelErrorCode.UNAVAILABLE.error("web instance is not active"));
        }
        VerifiedIngress verified = verified(requestId, callerRef);
        IngressResolution resolution = host.ingress().resolve(verified).toCompletableFuture().join();
        IngressRequest request = new IngressRequest(IngressRequest.SUPPORTED_VERSION, requestId,
            resolution.identity(), resolution.routing(),
            new ChannelOperation.InputSubmit(targetRef, interpretation,
                List.of(new ContentPart.Text("p1", text)), null));
        return host.ingress().submit(resolution.admissionRef(), request).toCompletableFuture().join();
    }

    public ChannelResponse createSession(String token,String requestId,String viewId,String workspace,String prompt,long revision) {
        return submitOperation(token,requestId,viewId,new ChannelOperation.SessionCreate(workspace,prompt,revision));
    }
    public ChannelResponse getSelection(String token,String requestId,String viewId) {
        return submitOperation(token,requestId,viewId,new ChannelOperation.SelectionGet());
    }
    public ChannelResponse listWorkspaces(String token,String requestId,String viewId) {
        return submitOperation(token,requestId,viewId,new ChannelOperation.WorkspaceList());
    }
    public ChannelResponse selectWorkspace(String token,String requestId,String viewId,String workspace,long revision) {
        return submitOperation(token,requestId,viewId,new ChannelOperation.WorkspaceSelect(workspace,revision));
    }
    public ChannelResponse selectSession(String token,String requestId,String viewId,String target,long revision) {
        return submitOperation(token,requestId,viewId,new ChannelOperation.SessionSelect(target,revision));
    }
    public ChannelResponse listSessions(String token,String requestId,String cursor,Integer limit,String viewId,String workspace) {
        return submitOperation(token,requestId,viewId,new ChannelOperation.SessionList(null,new com.netlivecowork.channel.contract.PageRequest(cursor,limit),workspace));
    }
    public ChannelResponse getSession(String token,String requestId,String target,String viewId) {
        return submitOperation(token,requestId,viewId,new ChannelOperation.SessionGet(target));
    }
    public ChannelResponse submitChat(String token,String requestId,String text,String target,String viewId,long revision) {
        if(text==null || text.isBlank())throw new IllegalArgumentException("text is required");
        return submitOperation(token,requestId,viewId,new ChannelOperation.InputSubmit(target,ChannelOperation.Interpretation.LITERAL,
            List.of(new ContentPart.Text("p1",text)),null,revision));
    }

    public ChannelResponse respond(String token,String requestId,String viewId,String ref,long revision,JsonNode response) {
        return submitOperation(token,requestId,viewId,
            com.netlivecowork.channel.l1.render.InteractionReplyMapper.form(ref,revision,response));
    }

    /** Platform DTO conversion above ends here; every source uses the existing common port below. */
    private ChannelResponse submitOperation(String token,String requestId,String viewId,ChannelOperation operation) {
        String caller=authenticate(token);
        if(requestId==null || requestId.isBlank() || viewId==null || !viewId.matches("[A-Za-z0-9_-]{16,80}"))
            throw new IllegalArgumentException("requestId and a valid viewId are required");
        if(operation instanceof ChannelOperation.InputSubmit input &&
            (input.interpretation()!=ChannelOperation.Interpretation.LITERAL || input.targetRef()==null || input.expectedBindingRevision()==null))
            throw new IllegalArgumentException("Chat requires a literal message, selected target and selection revision");
        if(state!=State.ACTIVE)return ChannelResponse.rejected(requestId,ChannelErrorCode.UNAVAILABLE,"Entry is not active");
        var resolution=host.ingress().resolve(verified(requestId,caller,viewId)).toCompletableFuture().join();
        return host.ingress().submit(resolution.admissionRef(),new IngressRequest(IngressRequest.SUPPORTED_VERSION,
            requestId,resolution.identity(),resolution.routing(),operation)).toCompletableFuture().join();
    }

    /** The page queries the existing authorized session.list operation, including every page. */
    public ChannelResponse listSessions(String token, String requestId, String cursor, Integer limit) {
        String callerRef = authenticate(token);
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("requestId is required");
        if (state != State.ACTIVE) return ChannelResponse.rejected(requestId, ChannelErrorCode.UNAVAILABLE,
            "web instance is not active");
        var page = new com.netlivecowork.channel.contract.PageRequest(cursor, limit);
        var resolution = host.ingress().resolve(verified(requestId, callerRef)).toCompletableFuture().join();
        var request = new IngressRequest(IngressRequest.SUPPORTED_VERSION, requestId,
            resolution.identity(), resolution.routing(), new ChannelOperation.SessionList(null, page));
        return host.ingress().submit(resolution.admissionRef(), request).toCompletableFuture().join();
    }

    /** Reads the clicked target explicitly; another tab's current binding cannot redirect this query. */
    public ChannelResponse getSession(String token, String requestId, String targetRef) {
        String caller = authenticate(token);
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("requestId is required");
        var operation = new ChannelOperation.SessionGet(targetRef);
        if (state != State.ACTIVE) return ChannelResponse.rejected(requestId, ChannelErrorCode.UNAVAILABLE,
            "web instance is not active");
        var resolution = host.ingress().resolve(verified(requestId, caller)).toCompletableFuture().join();
        return host.ingress().submit(resolution.admissionRef(), new IngressRequest(IngressRequest.SUPPORTED_VERSION,
            requestId, resolution.identity(), resolution.routing(), operation)).toCompletableFuture().join();
    }

    public com.netlivecowork.channel.contract.SessionAccess.View sessionAccess(String token) {
        var resolved=managementAdmission(token);
        return host.ingress().getSessionAccess(resolved.admissionRef()).toCompletableFuture().join();
    }
    public com.netlivecowork.channel.contract.SessionHistory.Page queryHistory(String token, String targetRef,
            String cursor, Integer limit) {
        if (targetRef == null) {
            String requestId = java.util.UUID.randomUUID().toString();
            var admission = managementAdmission(token, requestId);
            var selection = host.ingress().submit(admission.admissionRef(), new IngressRequest(
                IngressRequest.SUPPORTED_VERSION, requestId, admission.identity(), admission.routing(),
                new ChannelOperation.SelectionGet())).toCompletableFuture().join();
            if (selection instanceof ChannelResponse.Completed completed
                    && completed.result() instanceof com.netlivecowork.channel.contract.OperationResult.SelectionGet current
                    && current.selection().targetRef() != null) {
                targetRef = current.selection().targetRef();
            } else if (selection instanceof ChannelResponse.Rejected rejected) {
                throw new ChannelContractException(ChannelErrorCode.valueOf(rejected.error().code()), rejected.error().message());
            } else {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "请先用 /list 和 /use 选择会话");
            }
        }
        var resolved=managementAdmission(token);
        return host.ingress().queryHistory(resolved.admissionRef(),new com.netlivecowork.channel.contract.SessionHistory.Request(
            targetRef,new com.netlivecowork.channel.contract.PageRequest(cursor,limit))).toCompletableFuture().join();
    }
    public com.netlivecowork.channel.contract.SessionAccess.Saved saveSessionAccess(String token,com.netlivecowork.channel.contract.SessionAccess.Save request) {
        var resolved=managementAdmission(token);
        return host.ingress().saveSessionAccess(resolved.admissionRef(),request).toCompletableFuture().join();
    }
    public com.netlivecowork.channel.contract.SessionAccess.Saved lookupSessionAccess(String token,String requestId) {
        var resolved=managementAdmission(token);
        return host.ingress().lookupSessionAccess(resolved.admissionRef(),requestId).toCompletableFuture().join();
    }
    private IngressResolution managementAdmission(String token) {
        return managementAdmission(token, java.util.UUID.randomUUID().toString());
    }
    private IngressResolution managementAdmission(String token, String requestId) {
        String caller=authenticate(token);
        if(state!=State.ACTIVE) throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE,"Entry is not active");
        return host.ingress().resolve(verified(requestId,caller)).toCompletableFuture().join();
    }

    /** Lookup re-resolves the authenticated caller; it never submits or replays an input. */
    public ChannelResponse lookupInbound(String presentedToken, String requestId) {
        String callerRef = authenticate(presentedToken);
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("requestId is required");
        if (state != State.ACTIVE) return ChannelResponse.rejected(requestId, ChannelErrorCode.UNAVAILABLE,
            "web instance is not active");
        var resolution = host.ingress().resolve(verified(requestId, callerRef)).toCompletableFuture().join();
        return host.ingress().lookup(resolution.admissionRef(), requestId).toCompletableFuture().join();
    }

    public String streamId() { return streamId; }

    private VerifiedIngress verified(String requestId, String callerRef) {
        return verified(requestId,callerRef,null);
    }
    private VerifiedIngress verified(String requestId, String callerRef,String viewId) {
        return new VerifiedIngress(requestId, "web:" + config.entryRef(),
            new VerifiedIngress.Subject(VerifiedIngress.SubjectKind.USER, callerRef),
            new VerifiedIngress.ExternalScope(viewId==null?"web":"web-view", viewId==null?callerRef:callerRef+":"+viewId),
            VerifiedIngress.ExternalReplyTarget.personalConversation(callerRef),
            requestId, null);
    }

    // ---------------------------------------------------------------- 出站

    @Override
    public CompletionStage<DeliveryReceipt> deliver(ChannelDelivery delivery, DeliveryAttempt attempt) {
        String deliveryId = delivery.deliveryId();
        if (state != State.ACTIVE) {
            return CompletableFuture.completedFuture(new DeliveryReceipt.Failed(deliveryId,
                ChannelErrorCode.UNAVAILABLE.error("web instance is deactivated")));
        }
        if (!config.entryRef().equals(delivery.routing().entryRef())) {
            return CompletableFuture.completedFuture(new DeliveryReceipt.Failed(deliveryId,
                ChannelErrorCode.INVALID_ARGUMENT.error("delivery routing belongs to another entry")));
        }
        try {
            PlainTextRenderer.body(delivery.packet());
        } catch (PlainTextRenderer.UnsupportedContent e) {
            return CompletableFuture.completedFuture(new DeliveryReceipt.Failed(deliveryId,
                ChannelErrorCode.UNSUPPORTED_CONTENT.error(e.getMessage())));
        }
        try {
            return CompletableFuture.supplyAsync(() -> accept(delivery), executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(new DeliveryReceipt.Failed(deliveryId,
                ChannelErrorCode.UNAVAILABLE.error("web instance is deactivated")));
        }
    }

    private DeliveryReceipt accept(ChannelDelivery delivery) {
        try {
            var destination=host.destinations().resolve(delivery.routing().destinationRef(),delivery.routing().destinationRevision()).toCompletableFuture().join();
            String subject = destination.protocolData().path("conversation").asText();
            boolean known = config.settings().has(WebAdapterFactory.RELAY_DIGEST)
                ? WebAdapterFactory.RELAY_SUBJECT.equals(subject) : users().containsKey(subject);
            if (!known)
                return new DeliveryReceipt.Failed(delivery.deliveryId(),ChannelErrorCode.IDENTITY_UNBOUND.error("Unknown receiver"));
            var receipt=host.inbox().append(delivery.deliveryId()).toCompletableFuture().join();
            return new DeliveryReceipt.Accepted(receipt.deliveryId(),receipt.receiptRef());
        } catch (RuntimeException e) {
            // The database may have committed before the response failed. Never report an invented rollback.
            return new DeliveryReceipt.Unknown(delivery.deliveryId(),ChannelErrorCode.OUTCOME_UNKNOWN.error("Receiver custody needs reconciliation"));
        }
    }

    @Override public com.netlivecowork.channel.contract.port.DeliveryQueryPort deliveryQuery() { return this; }
    @Override public CompletionStage<DeliveryReceipt> queryDelivery(String id) { return host.inbox().queryReceipt(id); }

    public com.netlivecowork.channel.contract.DeliveryInbox.Result readInbox(String token,com.netlivecowork.channel.contract.DeliveryInbox.Request request) {
        var admission=managementAdmission(token);
        return host.inbox().read(admission.admissionRef(),request).toCompletableFuture().join();
    }
    public void acknowledgeInbox(String token,com.netlivecowork.channel.contract.DeliveryInbox.Acknowledgement request) {
        var admission=managementAdmission(token);
        host.inbox().acknowledge(admission.admissionRef(),request).toCompletableFuture().join();
    }

    public ObjectNode renderInboxItem(DeliveryInbox.Item item) {
        var delivery = item.delivery();
        var packet = delivery.packet();
        var message = Json.obj().put("sequence", item.sequence()).put("deliveryId", delivery.deliveryId())
            .put("type", packet.type().wire()).put("sourceLabel", delivery.presentation().sourceLabel())
            .put("text", PlainTextRenderer.body(delivery)).put("targetRef", packet.context().targetRef());
        message.set("presentation", Json.mapper().valueToTree(delivery.presentation()));
        var receipt = delivery.completionReceipt();
        if (receipt != null) message.set("receipt", Json.mapper().valueToTree(receipt));
        if (item.readAt() != null) message.put("readAt", item.readAt());
        if (packet.payload() instanceof EventPayload.MessageReady ready)
            message.put("messageRef", ready.message().messageRef());
        if (packet.type().wire().startsWith("interaction.") || packet.type() == EventType.TASK_COMPLETED)
            message.set("packet", PacketJson.enc(packet));
        return message;
    }

    /** Compatibility read only; the typed inbox API carries explicit gaps and client acknowledgements. */
    public List<Delivered> poll(String token, long afterSequence) {
        var result=readInbox(token,new com.netlivecowork.channel.contract.DeliveryInbox.Request("legacy-client-00000000",null,100));
        if(!"page".equals(result.status())) throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,"Legacy cursor has a gap; use inbox recovery");
        return result.page().items().stream().filter(item->Long.parseLong(item.sequence())>afterSequence).map(item->{
            var delivery=item.delivery();var packet=delivery.packet();
            return new Delivered(Long.parseLong(item.sequence()),packet.type().wire(),delivery.presentation().sourceLabel(),
                PlainTextRenderer.body(packet),packet.context().targetRef(),packet.payload() instanceof com.netlivecowork.channel.contract.EventPayload.MessageReady ready?ready.message().messageRef():null,packet);
        }).toList();
    }

    // ---------------------------------------------------------------- 令牌

    private String authenticate(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            throw new SecurityException("web token is required");
        }
        if (config.settings().has(WebAdapterFactory.RELAY_DIGEST)) {
            String digest = com.netlivecowork.channel.infra.Digests.sha256(presentedToken);
            if (MessageDigest.isEqual(digest.getBytes(StandardCharsets.US_ASCII),
                    config.settings().path(WebAdapterFactory.RELAY_DIGEST).asText().getBytes(StandardCharsets.US_ASCII)))
                return WebAdapterFactory.RELAY_SUBJECT;
            throw new SecurityException("invalid host relay");
        }
        byte[] presented = presentedToken.getBytes(StandardCharsets.UTF_8);
        for (Map.Entry<String, String> entry : users().entrySet()) {
            if (MessageDigest.isEqual(presented, entry.getValue().getBytes(StandardCharsets.UTF_8))) {
                return entry.getKey();
            }
        }
        throw new SecurityException("unknown web token");
    }

    /** usersFile 在首次鉴权时读取：离线校验不触碰文件，令牌不进入配置快照。 */
    private Map<String, String> users() {
        if (usersLoaded) {
            return users;
        }
        synchronized (usersLock) {
            if (usersLoaded) {
                return users;
            }
            JsonNode settings = config.settings();
            JsonNode node = settings.get(WebAdapterFactory.SETTINGS_USERS);
            if (node == null || node.isNull()) {
                String file = settings.path(WebAdapterFactory.SETTINGS_USERS_FILE).asText("");
                if (file.isBlank()) {
                    throw new IllegalStateException("web adapter requires settings.users or settings.usersFile");
                }
                try {
                    node = Json.mapper().readTree(Path.of(file).toFile());
                } catch (IOException e) {
                    throw new IllegalStateException("web users file cannot be read");
                }
            }
            if (node == null || !node.isObject() || node.isEmpty()) {
                throw new IllegalStateException("web adapter has no users");
            }
            Map<String, String> loaded = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> {
                if (entry.getValue().isTextual() && !entry.getValue().asText().isBlank()) {
                    loaded.put(entry.getKey(), entry.getValue().asText());
                }
            });
            if (loaded.isEmpty()) {
                throw new IllegalStateException("web adapter has no usable users");
            }
            users.putAll(loaded);
            usersLoaded = true;
            return users;
        }
    }
}
