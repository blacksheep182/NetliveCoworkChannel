package com.netlivecowork.channel.l1.weixin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelDelivery;
import com.netlivecowork.channel.contract.ChannelError;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.ChannelOperation;
import com.netlivecowork.channel.contract.ChannelResponse;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.DeliveryAttempt;
import com.netlivecowork.channel.contract.DeliveryReceipt;
import com.netlivecowork.channel.contract.DispositionResult;
import com.netlivecowork.channel.contract.InboundDisposition;
import com.netlivecowork.channel.contract.IngressRequest;
import com.netlivecowork.channel.contract.IngressResolution;
import com.netlivecowork.channel.contract.ProtocolStateEntry;
import com.netlivecowork.channel.contract.ResolvedDestination;
import com.netlivecowork.channel.contract.VerifiedIngress;
import com.netlivecowork.channel.contract.adapter.ActivationResult;
import com.netlivecowork.channel.contract.adapter.AdapterInstance;
import com.netlivecowork.channel.contract.adapter.AdapterInstanceConfig;
import com.netlivecowork.channel.contract.adapter.AuthPort;
import com.netlivecowork.channel.contract.adapter.AuthSession;
import com.netlivecowork.channel.contract.adapter.ConnectResult;
import com.netlivecowork.channel.contract.adapter.ConnectionAttempt;
import com.netlivecowork.channel.contract.adapter.ConnectionEvent;
import com.netlivecowork.channel.contract.adapter.ConnectionPort;
import com.netlivecowork.channel.contract.adapter.ProbeResult;
import com.netlivecowork.channel.contract.adapter.StopReason;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.port.AdapterHost;
import com.netlivecowork.channel.contract.port.DeliveryPort;
import com.netlivecowork.channel.contract.port.DeliveryQueryPort;
import com.netlivecowork.channel.infra.Digests;
import com.netlivecowork.channel.infra.Ids;
import com.netlivecowork.channel.l1.render.PlainTextRenderer;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 微信 iLink 机器人实例。一个实例 = 一个 bot 账号：凭据、游标、QR 会话互不共享（INV-48）。
 *
 * <p>生命周期 SDK_MANAGED：{@link #connect} 只交付代次并启动本实例唯一的轮询线程，
 * 该线程自行完成长轮询、退避与会话恢复，只通过 {@code host.events()} 上报协议事实，
 * 不决定 L2 的重连与探测策略（INV-48、INV-49）。</p>
 */
public final class WeixinAdapterInstance implements AdapterInstance, ConnectionPort, DeliveryPort, AuthPort {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(WeixinAdapterInstance.class);

    /** 协议状态键：已验证账号与入站游标（INV-12 / INV-14）。拒绝处置在 L2 的权威处置表。 */
    static final String KEY_ACCOUNT = "account";
    static final String KEY_CURSOR = "cursor";

    private static final long MIN_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 60_000;
    private static final long RATE_LIMITED_WAIT_MS = 3_600_000;
    private static final long POLL_INTERVAL_MS = 1_000;
    private static final long AUTH_WAIT_MS = 5_000;
    private static final long STALE_POLL_NANOS = Duration.ofMillis(
        WeixinProtocol.LONG_POLL_TIMEOUT.toMillis() + MAX_BACKOFF_MS).toNanos();
    private static final long REPORT_TIMEOUT_SECONDS = 5;
    private static final long QR_TTL_SECONDS = 120;

    private final AdapterInstanceConfig config;
    private final AdapterHost host;
    private final com.netlivecowork.channel.l1.render.InteractionReplyContext replies;
    private final WeixinProtocol protocol;
    private final ExecutorService executor;
    private final Object lifecycle = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, Login> logins = new ConcurrentHashMap<>();

    private volatile State state = State.NEW;
    private volatile long generation;
    private volatile String accountKey;
    private volatile boolean accountMismatch;
    private volatile boolean loopRunning;
    private volatile Thread poller;
    private volatile boolean connected;
    private volatile boolean authReported;
    private volatile long lastSuccessNanos;
    private volatile ChannelError lastError;

    public WeixinAdapterInstance(AdapterInstanceConfig config, AdapterHost host) {
        this(config, host, HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build());
    }

    public WeixinAdapterInstance(AdapterInstanceConfig config, AdapterHost host, HttpClient http) {
        this.config = Objects.requireNonNull(config, "config");
        this.host = Objects.requireNonNull(host, "host");
        this.replies=new com.netlivecowork.channel.l1.render.InteractionReplyContext(host.protocolState());
        this.protocol = new WeixinProtocol(Objects.requireNonNull(http, "http"),
            WeixinAdapterFactory.secretFile(config.settings()), WeixinAdapterFactory.baseUrl(config.settings()));
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    private enum State {
        NEW, ACTIVE, STOPPED
    }

    /** QR 登录会话：challenge 只在本实例内使用，bot_token 从不进入会话对象（INV-52）。 */
    private record Login(String challenge, AuthSession.Presentation presentation, Instant expiresAt,
                         AuthSession.Status status, String externalAccountKey, ChannelError error) {

        Login next(AuthSession.Status nextStatus, String nextKey, ChannelError nextError) {
            return new Login(challenge, nextStatus == AuthSession.Status.PENDING ? presentation : null,
                expiresAt, nextStatus, nextKey, nextError);
        }
    }

    @Override
    public String entryRef() {
        return config.entryRef();
    }

    @Override
    public ConnectionPort connection() {
        return this;
    }

    @Override
    public DeliveryPort delivery() {
        return this;
    }

    /** 平台不提供投递查询：不能声明 receiptQuery（能力与端口一致）。 */
    @Override
    public DeliveryQueryPort deliveryQuery() {
        return null;
    }

    @Override
    public AuthPort auth() {
        return this;
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public CompletionStage<ActivationResult> activate() {
        synchronized (lifecycle) {
            if (state == State.STOPPED) {
                return CompletableFuture.failedFuture(new IllegalStateException("weixin instance is stopped"));
            }
            if (state == State.ACTIVE) {
                return CompletableFuture.completedFuture(activation());
            }
            state = State.ACTIVE;
        }
        WeixinProtocol.Secret secret;
        try {
            secret = protocol.refreshSecret();
        } catch (RuntimeException e) {
            synchronized (lifecycle) {
                state = State.NEW;
            }
            return CompletableFuture.failedFuture(e);
        }
        applyCredential(secret);
        // 首次授权未完成时账号为空，实例只开放授权与检测，业务被 AUTH_REQUIRED 阻断。
        return bindAccount(accountKey).thenApply(revision -> activation());
    }

    @Override
    public CompletionStage<Void> deactivate(StopReason reason) {
        synchronized (lifecycle) {
            if (state == State.STOPPED) {
                return CompletableFuture.completedFuture(null);
            }
            state = State.STOPPED;
        }
        stopLoop();
        logins.clear();
        executor.shutdown();
        return CompletableFuture.completedFuture(null);
    }

    private ActivationResult activation() {
        return new ActivationResult(WeixinAdapterFactory.capabilities(), accountMismatch ? null : accountKey);
    }

    // ---------------------------------------------------------------- 连接

    @Override
    public CompletionStage<ConnectResult> connect(ConnectionAttempt attempt) {
        synchronized (lifecycle) {
            if (state != State.ACTIVE) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException("activate the weixin instance before connect"));
            }
            if (attempt.generation() < generation) {
                // 旧代次不得覆盖新连接（INV-49）
                return CompletableFuture.completedFuture(new ConnectResult.Connecting());
            }
            generation = attempt.generation();
        }
        WeixinProtocol.Secret secret = protocol.refreshSecret();
        applyCredential(secret);
        if (!secret.hasToken()) {
            connected = false;
            authReported = true;
            report(new ConnectionEvent.AuthRequired(generation, sequence.incrementAndGet(),
                ChannelErrorCode.AUTH_REQUIRED.error("weixin credential is missing")));
            // 轮询仍启动：授权完成后同一 SDK 会话直接接管，不需要 L2 重新连接
            startLoop();
            return CompletableFuture.completedFuture(new ConnectResult.Connecting());
        }
        if (accountMismatch) {
            connected = false;
            authReported = true;
            report(new ConnectionEvent.AuthRequired(generation, sequence.incrementAndGet(),
                ChannelErrorCode.ACCOUNT_MISMATCH.error("weixin bot account does not match the account binding")));
            startLoop();
            return CompletableFuture.completedFuture(new ConnectResult.Connecting());
        }
        // connect 已确认账号，事实流从心跳开始，避免与 Connected 结果重复
        connected = true;
        authReported = false;
        startLoop();
        return CompletableFuture.completedFuture(new ConnectResult.Connected(accountKey));
    }

    @Override
    public CompletionStage<Void> disconnect(long generation, StopReason reason) {
        synchronized (lifecycle) {
            if (generation < this.generation) {
                return CompletableFuture.completedFuture(null);
            }
        }
        stopLoop();
        return CompletableFuture.completedFuture(null);
    }

    /** 长轮询本身即心跳：只根据最近一次成功/失败事实判断，不发起可能丢数据的额外请求。 */
    @Override
    public CompletionStage<ProbeResult> probe(ConnectionAttempt attempt) {
        if (state != State.ACTIVE) {
            return CompletableFuture.completedFuture(ProbeResult.unhealthy(
                ChannelErrorCode.UNAVAILABLE.error("weixin instance is not active")));
        }
        if (!loopRunning) {
            return CompletableFuture.completedFuture(ProbeResult.unhealthy(
                ChannelErrorCode.UNAVAILABLE.error("weixin long poll is not running")));
        }
        ChannelError error = lastError;
        if (error != null) {
            return CompletableFuture.completedFuture(ProbeResult.unhealthy(error));
        }
        long success = lastSuccessNanos;
        if (success == 0) {
            return CompletableFuture.completedFuture(ProbeResult.unknown("weixin long poll has not completed"));
        }
        return CompletableFuture.completedFuture(System.nanoTime() - success <= STALE_POLL_NANOS
            ? ProbeResult.healthy()
            : ProbeResult.unknown("weixin long poll is stale"));
    }

    // ---------------------------------------------------------------- 投递

    @Override
    public CompletionStage<DeliveryReceipt> deliver(ChannelDelivery delivery, DeliveryAttempt attempt) {
        String deliveryId = delivery.deliveryId();
        if (state != State.ACTIVE) {
            return completed(new DeliveryReceipt.Failed(deliveryId,
                ChannelErrorCode.UNAVAILABLE.error("weixin instance is deactivated")));
        }
        if (accountMismatch) {
            return completed(new DeliveryReceipt.Failed(deliveryId,
                ChannelErrorCode.ACCOUNT_MISMATCH.error("weixin bot account does not match the account binding")));
        }
        if (!config.entryRef().equals(delivery.routing().entryRef())) {
            return completed(new DeliveryReceipt.Failed(deliveryId,
                ChannelErrorCode.INVALID_ARGUMENT.error("delivery routing belongs to another entry")));
        }
        // 平台只能发文本：动作、图片、附件一律显式拒绝（INV-38）
        String body;
        try {
            body = PlainTextRenderer.body(delivery);
        } catch (PlainTextRenderer.UnsupportedContent e) {
            return completed(new DeliveryReceipt.Failed(deliveryId,
                ChannelErrorCode.UNSUPPORTED_CONTENT.error(e.getMessage())));
        }
        String text = PlainTextRenderer.withSourceLabel(delivery.presentation().sourceLabel(), body);
        try {
            return CompletableFuture.supplyAsync(() -> send(delivery, text, attempt), executor);
        } catch (RejectedExecutionException e) {
            return completed(new DeliveryReceipt.Failed(deliveryId,
                ChannelErrorCode.UNAVAILABLE.error("weixin instance is deactivated")));
        }
    }

    private DeliveryReceipt send(ChannelDelivery delivery, String text, DeliveryAttempt attempt) {
        String deliveryId = delivery.deliveryId();
        try {
            WeixinProtocol.Secret secret = protocol.refreshSecret();
            if (!secret.hasToken()) {
                return new DeliveryReceipt.Failed(deliveryId,
                    ChannelErrorCode.AUTH_REQUIRED.error("weixin credential is missing"));
            }
            applyCredential(secret);
            if (accountMismatch) {
                return new DeliveryReceipt.Failed(deliveryId,
                    ChannelErrorCode.ACCOUNT_MISMATCH.error("weixin bot account does not match the account binding"));
            }
            // 固定目的地：会话与 context_token 只来自 L2 登记的目的地，不按当前用户重寻址（INV-34）
            ResolvedDestination destination = host.destinations()
                .resolve(delivery.routing().destinationRef(), delivery.routing().destinationRevision())
                .toCompletableFuture().join();
            JsonNode data = destination.protocolData();
            String conversation = data == null ? "" : data.path("conversation").asText("");
            if (conversation.isBlank()) {
                return new DeliveryReceipt.Failed(deliveryId,
                    ChannelErrorCode.INVALID_ARGUMENT.error("weixin destination has no conversation"));
            }
            text+=replies.delivered(accountKey,conversation,delivery.packet());
            String contextToken = data.path("contextToken").asText("");
            String receiptRef = protocol.sendMessage(conversation, contextToken, deliveryId, text,
                Duration.ofMillis(attempt.timeoutMs()));
            return new DeliveryReceipt.Accepted(deliveryId, receiptRef);
        } catch (CompletionException e) {
            ChannelErrorCode code = e.getCause() instanceof ChannelContractException contract
                ? contract.code() : ChannelErrorCode.DESTINATION_STALE;
            return new DeliveryReceipt.Failed(deliveryId, ChannelError.of(code, "weixin destination cannot be resolved"));
        } catch (WeixinProtocol.PlatformFailure e) {
            ChannelError error = e.outcomeUnknown()
                ? ChannelErrorCode.OUTCOME_UNKNOWN.error("weixin send outcome is unknown")
                : ChannelError.of(e.code(), e.getMessage());
            return e.outcomeUnknown()
                ? new DeliveryReceipt.Unknown(deliveryId, error)
                : new DeliveryReceipt.Failed(deliveryId, error);
        } catch (RuntimeException e) {
            // 无平台回执：不能当成未发送（INV-35）
            return new DeliveryReceipt.Unknown(deliveryId,
                ChannelErrorCode.OUTCOME_UNKNOWN.error("weixin send outcome is unknown"));
        }
    }

    // ---------------------------------------------------------------- 授权

    @Override
    public CompletionStage<AuthSession> beginAuth() {
        String authRef = Ids.next("weixin-auth");
        Instant expiresAt = Instant.now().plusSeconds(QR_TTL_SECONDS);
        try {
            return CompletableFuture.supplyAsync(() -> beginAuth(authRef, expiresAt), executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(new AuthSession(authRef, AuthSession.Status.FAILED, expiresAt,
                null, null, ChannelErrorCode.UNAVAILABLE.error("weixin instance is deactivated")));
        }
    }

    private AuthSession beginAuth(String authRef, Instant expiresAt) {
        try {
            JsonNode result = protocol.requestQr();
            String challenge = result.path("qrcode").asText("");
            if (result.path("ret").asInt(0) != 0 || challenge.isBlank()) {
                return new AuthSession(authRef, AuthSession.Status.FAILED, expiresAt, null, null,
                    ChannelErrorCode.UNAVAILABLE.error("weixin did not issue a QR challenge"));
            }
            String image = result.path("qrcode_img_content").asText("");
            AuthSession.Presentation presentation = new AuthSession.Presentation.Qr(
                image.isBlank() ? challenge : image);
            logins.put(authRef, new Login(challenge, presentation, expiresAt, AuthSession.Status.PENDING, null, null));
            return new AuthSession(authRef, AuthSession.Status.PENDING, expiresAt, null, presentation, null);
        } catch (WeixinProtocol.PlatformFailure e) {
            logins.put(authRef, new Login("", null, expiresAt, AuthSession.Status.FAILED, null,
                ChannelError.of(e.code(), e.getMessage())));
            return new AuthSession(authRef, AuthSession.Status.FAILED, expiresAt, null, null,
                ChannelError.of(e.code(), e.getMessage()));
        }
    }

    @Override
    public CompletionStage<AuthSession> authStatus(String authRef) {
        if (authRef == null || authRef.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("authRef is required"));
        }
        try {
            return CompletableFuture.supplyAsync(() -> authStatusNow(authRef), executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(new IllegalStateException("weixin instance is deactivated"));
        }
    }

    private AuthSession authStatusNow(String authRef) {
        Login login = logins.get(authRef);
        if (login == null) {
            // 未在本实例发起的 authRef 不返回任何凭证信息（INV-52）
            return new AuthSession(authRef, AuthSession.Status.FAILED, Instant.now(), null, null,
                ChannelErrorCode.INVALID_ARGUMENT.error("unknown weixin auth session"));
        }
        if (login.status() != AuthSession.Status.PENDING) {
            return session(authRef, login);
        }
        if (Instant.now().isAfter(login.expiresAt())) {
            Login expired = login.next(AuthSession.Status.EXPIRED, null,
                ChannelErrorCode.AUTH_REQUIRED.error("weixin QR code has expired"));
            logins.put(authRef, expired);
            return session(authRef, expired);
        }
        try {
            JsonNode result = protocol.qrStatus(login.challenge());
            String status = result.path("status").asText("wait");
            if (!"confirmed".equals(status)) {
                Login next = switch (status) {
                    case "expired" -> login.next(AuthSession.Status.EXPIRED, null,
                        ChannelErrorCode.AUTH_REQUIRED.error("weixin QR code has expired"));
                    case "cancel", "cancelled", "canceled" -> login.next(AuthSession.Status.CANCELLED, null,
                        ChannelErrorCode.AUTH_REQUIRED.error("weixin QR login was cancelled"));
                    default -> login;
                };
                logins.put(authRef, next);
                return session(authRef, next);
            }
            String token = result.path("bot_token").asText("");
            if (token.isBlank()) {
                Login failed = login.next(AuthSession.Status.FAILED, null,
                    ChannelErrorCode.UNAVAILABLE.error("weixin confirmation carried no credential"));
                logins.put(authRef, failed);
                return session(authRef, failed);
            }
            saveCredential(result);
            WeixinProtocol.Secret saved = protocol.refreshSecret();
            String key = accountKey(saved);
            applyCredential(saved);
            bindAccount(key);
            Login completed = login.next(AuthSession.Status.COMPLETED, key, null);
            logins.put(authRef, completed);
            return session(authRef, completed);
        } catch (WeixinProtocol.PlatformFailure e) {
            return new AuthSession(authRef, AuthSession.Status.PENDING, login.expiresAt(), null,
                login.presentation(), null);
        }
    }

    @Override
    public CompletionStage<Void> cancelAuth(String authRef) {
        if (authRef == null || authRef.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("authRef is required"));
        }
        logins.computeIfPresent(authRef, (ref, login) -> login.next(AuthSession.Status.CANCELLED, null,
            ChannelErrorCode.AUTH_REQUIRED.error("weixin QR login was cancelled")));
        return CompletableFuture.completedFuture(null);
    }

    private AuthSession session(String authRef, Login login) {
        return new AuthSession(authRef, login.status(), login.expiresAt(), login.externalAccountKey(),
            login.presentation(), login.error());
    }

    /** 只保存平台返回的凭据字段；bot_token 不进入 authStatus 响应（INV-52）。 */
    private void saveCredential(JsonNode confirmation) {
        ObjectNode credential = Json.obj();
        for (String field : List.of("bot_token", "ilink_bot_id", "ilink_user_id", "baseurl")) {
            if (confirmation.hasNonNull(field) && !confirmation.path(field).asText().isBlank()) {
                credential.set(field, confirmation.get(field));
            }
        }
        protocol.writeSecret(credential);
    }

    // ---------------------------------------------------------------- 长轮询

    private void startLoop() {
        synchronized (lifecycle) {
            if (loopRunning || state != State.ACTIVE) {
                return;
            }
            loopRunning = true;
            poller = Thread.startVirtualThread(this::pollLoop);
        }
    }

    private void stopLoop() {
        Thread thread;
        synchronized (lifecycle) {
            loopRunning = false;
            thread = poller;
            poller = null;
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** 本实例唯一的重连调度者（SDK_MANAGED，INV-48）。 */
    private void pollLoop() {
        long backoff = MIN_BACKOFF_MS;
        try {
            while (loopRunning && state == State.ACTIVE) {
                long waitMs;
                try {
                    WeixinProtocol.Secret secret = protocol.refreshSecret();
                    applyCredential(secret);
                    if (!secret.hasToken()) {
                        connected = false;
                        if (!authReported) {
                            authReported = true;
                            report(new ConnectionEvent.AuthRequired(generation, sequence.incrementAndGet(),
                                ChannelErrorCode.AUTH_REQUIRED.error("weixin credential is missing")));
                        }
                        waitMs = AUTH_WAIT_MS;
                    } else if (accountMismatch) {
                        connected = false;
                        if (!authReported) {
                            authReported = true;
                            report(new ConnectionEvent.AuthRequired(generation, sequence.incrementAndGet(),
                                ChannelErrorCode.ACCOUNT_MISMATCH.error(
                                    "weixin bot account does not match the account binding")));
                        }
                        waitMs = AUTH_WAIT_MS;
                    } else {
                        authReported = false;
                        waitMs = pollOnce();
                        onPollSucceeded();
                        backoff = MIN_BACKOFF_MS;
                    }
                } catch (WeixinProtocol.PlatformFailure e) {
                    onPollFailed(e);
                    waitMs = backoff;
                    backoff = Math.min(MAX_BACKOFF_MS, backoff * 2);
                } catch (RuntimeException e) {
                    onPollFailed(new WeixinProtocol.PlatformFailure(ChannelErrorCode.UNAVAILABLE, true,
                        "weixin long poll failed"));
                    waitMs = backoff;
                    backoff = Math.min(MAX_BACKOFF_MS, backoff * 2);
                }
                sleep(waitMs);
            }
        } finally {
            synchronized (lifecycle) {
                loopRunning = false;
                poller = null;
            }
        }
    }

    /**
     * 一次 getupdates：入站消息逐条 resolve + submit，全部受理后才推进游标。
     * 平台限流（ret=-14）按协议等待一小时，不重放游标。
     */
    long pollOnce() {
        if (state != State.ACTIVE) {
            throw new IllegalStateException("weixin instance is not active");
        }
        String cursor = readCursor();
        JsonNode result = protocol.getUpdates(cursor);
        int ret = result.path("ret").asInt(0);
        if (ret == -14) {
            return RATE_LIMITED_WAIT_MS;
        }
        if (ret != 0) {
            throw new WeixinProtocol.PlatformFailure(ChannelErrorCode.UNAVAILABLE, true,
                "weixin getupdates was rejected (ret=" + ret + ")");
        }
        for (JsonNode message : result.path("msgs")) {
            if (message.path("message_type").asInt() == 2) {
                continue;
            }
            String text = inboundText(message);
            if (text.isBlank()) {
                // 平台只提供文本；其他 item 不作为内容提交，也不伪造替代文本
                continue;
            }
            submitInbound(message, text);
        }
        if (result.hasNonNull("get_updates_buf")) {
            writeCursor(result.path("get_updates_buf").asText(""));
        }
        return POLL_INTERVAL_MS;
    }

    private void submitInbound(JsonNode message, String text) {
        String messageId = message.path("message_id").asText("");
        String sender = message.path("from_user_id").asText("");
        if (message.has("group_id") && !message.get("group_id").isTextual()) {
            throw new WeixinProtocol.PlatformFailure(ChannelErrorCode.INVALID_ARGUMENT, false,
                "weixin inbound group identity is malformed");
        }
        String group = message.path("group_id").asText("");
        if (messageId.isBlank() || sender.isBlank()) {
            throw new WeixinProtocol.PlatformFailure(ChannelErrorCode.INVALID_ARGUMENT, false,
                "weixin inbound message lacks identity");
        }
        String contentDigest = Digests.sha256(messageId + "\u0000" + text);
        // 原处置必须先复用：重投不因身份后来可用而重新执行，异内容隔离，超出保留边界转人工核对
        // （S70～S72）。这里的查询失败按暂时失败处理，不确认游标。
        InboundDisposition previous;
        try {
            previous = host.dispositions().find(messageId).toCompletableFuture().join();
        } catch (RuntimeException e) {
            throw new WeixinProtocol.PlatformFailure(ChannelErrorCode.UNAVAILABLE, false,
                "weixin inbound disposition lookup failed");
        }
        if (previous != null) {
            if (previous.expired()) {
                host.dispositions().markGap(messageId, "retention expired").toCompletableFuture().join();
                log.warn("weixin inbound {} is beyond the disposition retention window; manual review required",
                    messageId);
                return;
            }
            if (previous.sameContent(contentDigest)) {
                log.info("weixin inbound {} replays a disposed message; skipped without new execution", messageId);
                return;
            }
            log.warn("weixin inbound {} conflicts with an existing disposition; quarantined", messageId);
            return;
        }

        String contextToken = message.path("context_token").asText("");
        String conversation = group.isBlank() ? sender : group;
        ObjectNode protocolData = Json.obj().put("conversation", conversation);
        if (!contextToken.isBlank()) {
            protocolData.put("contextToken", contextToken);
        }
        // requestId 由平台消息标识确定：重投得到同一 requestId（INV-14）
        String requestId = "weixin-" + messageId;
        VerifiedIngress verified = new VerifiedIngress(requestId, accountKey,
            new VerifiedIngress.Subject(VerifiedIngress.SubjectKind.USER, accountKey + ":user:" + sender),
            new VerifiedIngress.ExternalScope(group.isBlank() ? "weixin" : "weixin-group", conversation),
            new VerifiedIngress.ExternalReplyTarget(group.isBlank()
                ? VerifiedIngress.Audience.PERSONAL : VerifiedIngress.Audience.SHARED, conversation, protocolData),
            messageId, null);
        String command = text.strip();
        if (command.equals("/bind") || command.startsWith("/bind ")) {
            try {
                if (!group.isBlank()) throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,
                    "Owner binding must be completed in a private Weixin message");
                host.ingress().completeOwnerWeixinBinding(verified,
                    command.length() > 6 ? command.substring(6).strip() : "")
                    .toCompletableFuture().join();
                return;
            } catch (RuntimeException error) {
                ChannelErrorCode code = failureCode(error);
                if (code != null && permanentRejection(code)) {
                    if (!recordDisposition(messageId, contentDigest, code))
                        throw new WeixinProtocol.PlatformFailure(ChannelErrorCode.UNAVAILABLE, false,
                            "weixin binding rejection could not be persisted");
                    return;
                }
                throw new WeixinProtocol.PlatformFailure(ChannelErrorCode.UNAVAILABLE, false,
                    "weixin binding outcome is unavailable");
            }
        }
        IngressResolution resolution;
        ChannelResponse response;
        try {
            resolution = host.ingress().resolve(verified).toCompletableFuture().join();
            response = host.ingress()
                .submit(resolution.admissionRef(), request(resolution, requestId, conversation, text))
                .toCompletableFuture().join();
        } catch (RuntimeException e) {
            // resolve 阶段同样可能确定性拒绝（身份未绑定等）：必须分类，不能一律阻塞整批。
            ChannelErrorCode code = failureCode(e);
            if (code != null && permanentRejection(code)) {
                if(text.startsWith("#") && code!=ChannelErrorCode.IDENTITY_UNBOUND && code!=ChannelErrorCode.PERMISSION_DENIED)
                    protocol.sendMessage(sender,contextToken,requestId+"-feedback","回应未受理，请按原待办消息的 #编号、题号和选项序号整组回复。",Duration.ofSeconds(10));
                log.warn("weixin inbound {} rejected permanently during resolve/submit: {}", requestId, code);
                if (!recordDisposition(messageId, contentDigest, code)) {
                    throw new WeixinProtocol.PlatformFailure(ChannelErrorCode.UNAVAILABLE, false,
                        "weixin inbound rejection could not be persisted");
                }
                return;
            }
            throw new WeixinProtocol.PlatformFailure(code == null ? ChannelErrorCode.UNAVAILABLE : code, false,
                "weixin inbound could not be accepted by L2");
        }
        if (accepted(response)) {
            return;
        }
        ChannelErrorCode code = rejectionCode(response);
        if (code != null && permanentRejection(code)) {
            // 确定性拒绝（身份未绑定、参数错误等）：重投同一条消息不会变好；先可靠落盘处置再确认游标。
            log.warn("weixin inbound {} rejected permanently by L2: {}", requestId, code);
            if (!recordDisposition(messageId, contentDigest, code)) {
                throw new WeixinProtocol.PlatformFailure(ChannelErrorCode.UNAVAILABLE, false,
                    "weixin inbound rejection could not be persisted");
            }
            return;
        }
        // 暂时失败（不可达、限流、结果未知）：不能确认平台游标，交给平台重投（INV-35）。
        throw new WeixinProtocol.PlatformFailure(ChannelErrorCode.UNAVAILABLE, false,
            "weixin inbound was not accepted by L2");
    }

    /**
     * 落盘确定性拒绝。只有 RECORDED/DUPLICATE 才算处置完成；FAILED 时调用方必须保留重投，
     * 不允许"记不下来但照常确认"（S69）。
     */
    private boolean recordDisposition(String messageId, String contentDigest, ChannelErrorCode code) {
        DispositionResult result;
        try {
            result = host.dispositions().recordRejected(messageId, contentDigest, code.name())
                .toCompletableFuture().join();
        } catch (RuntimeException e) {
            result = DispositionResult.FAILED;
        }
        return switch (result) {
            case RECORDED, DUPLICATE -> true;
            case CONFLICT -> {
                // 同键异内容：原处置不被覆盖，本次也不执行；隔离由处置表体现（S71）。
                log.warn("weixin inbound {} conflicts with an existing disposition; not overwritten", messageId);
                yield true;
            }
            case FAILED -> {
                log.warn("weixin inbound {} rejection could not be persisted; keeping it for platform retry",
                    messageId);
                yield false;
            }
        };
    }

    /** 从异常链中取业务错误码；没有结构化错误时返回 null（按可重投处理）。 */
    private static ChannelErrorCode failureCode(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ChannelContractException contract) {
                return contract.code();
            }
            current = current.getCause();
        }
        return null;
    }

    /** 从入口响应中取错误码；没有结构化错误时返回 null。 */
    private static ChannelErrorCode rejectionCode(ChannelResponse response) {
        return switch (response) {
            case ChannelResponse.Rejected rejected -> rejected.error().errorCode();
            case ChannelResponse.Unknown unknown -> unknown.error().errorCode();
            default -> null;
        };
    }

    /** 只有确定性拒绝才允许跳过；其余一律按可重投失败处理，避免丢消息。 */
    private static boolean permanentRejection(ChannelErrorCode code) {
        return switch (code) {
            case IDENTITY_UNBOUND, INVALID_ARGUMENT, UNSUPPORTED_VERSION, UNSUPPORTED_CAPABILITY,
                 UNSUPPORTED_CONTENT, PERMISSION_DENIED, ACCOUNT_MISMATCH, REQUEST_CONFLICT,
                 INTERACTION_STALE, BINDING_CONFLICT, DESTINATION_STALE -> true;
            default -> false;
        };
    }

    private IngressRequest request(IngressResolution resolution, String requestId, String sender, String text) {
        var reply=replies.reply(accountKey,sender,requestId,text);
        return new IngressRequest(IngressRequest.SUPPORTED_VERSION, requestId,
            resolution.identity(), resolution.routing(),
            reply!=null?reply:new ChannelOperation.InputSubmit(null, ChannelOperation.Interpretation.COMMAND_AWARE,
                List.of(new ContentPart.Text("p1", text)), null));
    }

    private static boolean accepted(ChannelResponse response) {
        return response instanceof ChannelResponse.Accepted || response instanceof ChannelResponse.Completed;
    }

    private static String inboundText(JsonNode message) {
        StringBuilder text = new StringBuilder();
        for (JsonNode item : message.path("item_list")) {
            if (item.path("type").asInt() == 1) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(item.path("text_item").path("text").asText(""));
            }
        }
        return text.toString();
    }

    private String readCursor() {
        ProtocolStateEntry entry = host.protocolState().get(KEY_CURSOR).toCompletableFuture().join();
        return entry == null ? "" : entry.value().path("cursor").asText("");
    }

    private void writeCursor(String cursor) {
        ProtocolStateEntry entry = host.protocolState().get(KEY_CURSOR).toCompletableFuture().join();
        host.protocolState().put(KEY_CURSOR, entry == null ? null : entry.revision(),
            Json.obj().put("cursor", cursor)).toCompletableFuture().join();
    }

    private void onPollSucceeded() {
        lastError = null;
        lastSuccessNanos = System.nanoTime();
        if (connected) {
            report(new ConnectionEvent.Heartbeat(generation, sequence.incrementAndGet()));
        } else {
            connected = true;
            report(new ConnectionEvent.Connected(generation, sequence.incrementAndGet(), accountKey));
        }
    }

    private void onPollFailed(WeixinProtocol.PlatformFailure failure) {
        ChannelError error = ChannelError.of(failure.code(), failure.getMessage());
        lastError = error;
        connected = false;
        report(failure.code() == ChannelErrorCode.AUTH_REQUIRED
            ? new ConnectionEvent.AuthRequired(generation, sequence.incrementAndGet(), error)
            : new ConnectionEvent.Disconnected(generation, sequence.incrementAndGet(), error));
    }

    // ---------------------------------------------------------------- 账号绑定

    private void applyCredential(WeixinProtocol.Secret secret) {
        String key = secret.hasToken() ? accountKey(secret) : null;
        accountKey = key;
        String expected = config.expectedExternalAccountKey();
        // 换绑账号必须递增代次；这里只阻断业务并报告不符（INV-12）
        accountMismatch = expected != null && key != null && !expected.equals(key);
    }

    /** 账号键来自平台 bot 标识；只有令牌时用摘要，避免把凭据写进状态或日志（INV-52）。 */
    static String accountKey(WeixinProtocol.Secret secret) {
        if (secret.botId() != null && !secret.botId().isBlank()) {
            return "weixin:" + secret.botId();
        }
        return "weixin:token:" + Digests.sha256(secret.botToken()).substring(0, 32);
    }

    private CompletionStage<Long> bindAccount(String key) {
        if (key == null) {
            return CompletableFuture.completedFuture(null);
        }
        TextNode value = TextNode.valueOf(key);
        return host.protocolState().get(KEY_ACCOUNT)
            .thenCompose(entry -> host.protocolState().put(KEY_ACCOUNT, entry == null ? null : entry.revision(), value))
            .<Long>handle((revision, error) -> {
                if (error != null) {
                    // 协议状态写入失败不改变协议动作；凭据文件仍是账号事实来源
                    return null;
                }
                return revision;
            });
    }

    // ---------------------------------------------------------------- 工具

    private void report(ConnectionEvent event) {
        try {
            host.events().report(event).toCompletableFuture().get(REPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 事实上报失败不改变协议动作；代次与序号由 L2 判定（INV-49）
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
