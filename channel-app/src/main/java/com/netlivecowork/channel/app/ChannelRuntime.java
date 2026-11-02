package com.netlivecowork.channel.app;

import com.netlivecowork.channel.contract.IntakeAck;
import com.netlivecowork.channel.contract.ReceiptPacket;
import com.netlivecowork.channel.contract.TargetSummary;
import com.netlivecowork.channel.contract.VerifiedBackendEvent;
import com.netlivecowork.channel.contract.adapter.ConnectionPolicy;
import com.netlivecowork.channel.contract.adapter.EntryConfig;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.port.ApplicationIntakePort;
import com.netlivecowork.channel.infra.Db;
import com.netlivecowork.channel.l1.L1Registry;
import com.netlivecowork.channel.l1.fake.FakeAdapterFactory;
import com.netlivecowork.channel.l1.web.WebAdapterFactory;
import com.netlivecowork.channel.l1.weixin.WeixinAdapterFactory;
import com.netlivecowork.channel.l2.L2Module;
import com.netlivecowork.channel.l2.L2Settings;
import com.netlivecowork.channel.l2.SecretBox;
import com.netlivecowork.channel.l3.AccessType;
import com.netlivecowork.channel.l3.BackendService;
import com.netlivecowork.channel.l3.L3Module;
import com.netlivecowork.channel.l3.L3Settings;
import com.netlivecowork.channel.l3.TargetRegistration;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程内装配：按 L1 → L2 → L3 的装配顺序构造三层，并把 L2 的持久接管端口回交给 L3。
 * 层间只经公共契约协作，装配层不实现业务规则（INV-47）。
 */
public final class ChannelRuntime implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChannelRuntime.class);

    private final ChannelProperties properties;
    private final Db db;
    private final L1Registry l1;
    private final L2Module l2;
    private final L3Module l3;
    private final java.util.List<ChannelProperties.Sink> pendingConnectorSinks=new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile long nextSinkRetry;
    private W3BackendIdentityResolver identities;
    private DeploymentCredentials credentials;
    com.netlivecowork.channel.contract.SessionAccess.Manager coworkManager(String serviceRef,String authorization,String protectedProof) {
        var service=l3.registry().service(serviceRef);
        String secret=service==null?null:credentials.resolve(service.credentialRef());
        if(secret==null || secret.isBlank() || authorization==null || !java.security.MessageDigest.isEqual(("Bearer "+secret).getBytes(java.nio.charset.StandardCharsets.UTF_8),authorization.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            throw new com.netlivecowork.channel.contract.ChannelContractException(com.netlivecowork.channel.contract.ChannelErrorCode.AUTH_REQUIRED,"CoWork service and actual W3 authentication are required");
        try {
            var manager=l3.access().verifySessionManager(serviceRef,protectedProof).toCompletableFuture().get(20,java.util.concurrent.TimeUnit.SECONDS);
            identities.useVerifiedDesktopProof(serviceRef,manager,protectedProof);return manager;
        }catch(java.util.concurrent.ExecutionException e){if(e.getCause()instanceof RuntimeException r)throw r;throw new IllegalStateException(e);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Authentication interrupted");}
        catch(java.util.concurrent.TimeoutException e){throw new com.netlivecowork.channel.contract.ChannelContractException(com.netlivecowork.channel.contract.ChannelErrorCode.UNAVAILABLE,"CoWork authentication unavailable");}
    }

    private ChannelRuntime(ChannelProperties properties, Db db, L1Registry l1, L2Module l2, L3Module l3) {
        this.properties = properties;
        this.db = db;
        this.l1 = l1;
        this.l2 = l2;
        this.l3 = l3;
    }

    public static ChannelRuntime create(ChannelProperties properties) {
        Db db = Db.file(Path.of(properties.dataFile()));
        Clock clock = Clock.systemUTC();
        L1Registry l1 = new L1Registry(List.of(new WeixinAdapterFactory(), new WebAdapterFactory(),
            new FakeAdapterFactory()));
        SecretBox secretBox = SecretBox.fromEnvironment("CHANNEL_MASTER_KEY", properties.masterKeyId());

        // L3 需要把已验证事件转交 L2，而 L2 需要 L3 的访问端口：用委托切断构造环。
        DelegatingIntake intake = new DelegatingIntake();
        L3Settings l3Settings = new L3Settings(properties.l3().filesSupported(),
            properties.l3().sessionWatchSupported(), properties.l3().httpTimeoutMs(),
            properties.l3().forwardMaxAttempts(), properties.l3().forwardInitialBackoffMs(),
            properties.l3().forwardMaxBackoffMs(), properties.l3().forwardLeaseSeconds(),
            properties.l3().sinkRef());
        DeploymentCredentials credentials = new DeploymentCredentials();
        W3BackendIdentityResolver identities=new W3BackendIdentityResolver(properties, credentials, clock);
        L3Module l3 = new L3Module(db, clock, l3Settings, intake, credentials,
            identities,secretBox==null?com.netlivecowork.channel.l3.DeviceSecrets.DISABLED:new com.netlivecowork.channel.l3.DeviceSecrets(){
                public String seal(String v){return secretBox.encrypt(v);}
                public String open(String v){return secretBox.decrypt(v,secretBox.keyId());}
            });
        L2Settings l2Settings = new L2Settings(properties.tenantRef(), properties.workspaceRef(), "channel-l2",
            300, 10, 2_000, 60_000, 900, 10_000, 5, 2_000, 300_000, 60, 20, 20,
            properties.autoProvisionIdentities());
        L2Module l2 = new L2Module(db, clock, l2Settings, l1, l3.access(), secretBox);
        intake.bind(l2.intake());
        var runtime=new ChannelRuntime(properties, db, l1, l2, l3);runtime.identities=identities;runtime.credentials=credentials;return runtime;
    }

    /** 启动：登记服务/目标/事件接收端，按配置建立接入，再从 L3 同步逻辑目标。 */
    public void start() {
        // 事件接收端引用后端服务身份，必须先登记服务与目标。
        registerServicesAndTargets();
        registerSinks();
        registerEntries();
        l3.start();
        l2.start();
        int targets = l2.management().syncTargets();
        log.info("channel started: tenant={} workspace={} entries={} targets={}",
            properties.tenantRef(), properties.workspaceRef(), properties.entries().size(), targets);
    }

    private void registerSinks() {
        String defaultService = properties.services().isEmpty() ? null
            : properties.services().get(0).serviceRef();
        for (ChannelProperties.Sink sink : properties.sinks()) {
            String token = sink.token() == null ? properties.l3().sinkToken() : sink.token();
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("sink " + sink.sinkRef()
                    + " has no token; set CHANNEL_L3_SINK_TOKEN or channel.sinks[].token");
            }
            long revision = 1;
            if (sink.callbackUrl() != null && !sink.callbackUrl().isBlank()
                    && l3.registry().service(sink.serviceRef()).accessType()==AccessType.CONNECTOR) {
                revision=sink.expectedRevision()+1;pendingConnectorSinks.add(sink);
            } else if (sink.callbackUrl() != null && !sink.callbackUrl().isBlank()) {
                var registered = l3.registerRemoteSink(sink.serviceRef(), sink.sinkRef(), sink.requestKey(),
                    sink.expectedRevision(), sink.receiverId(), sink.callbackUrl(),
                    sink.callbackAuthBindingRef(), true);
                revision = registered.path("revision").longValue();
            }
            l3.events().registerSink(sink.sinkRef(), sink.serviceRef(), token, revision);
        }
        String defaultSink = properties.l3().sinkRef();
        boolean registered = properties.sinks().stream()
            .anyMatch(sink -> sink.sinkRef().equals(defaultSink));
        if (!registered) {
            if (defaultService == null) {
                log.warn("no backend service configured; inbound events cannot be verified until registration");
                return;
            }
            if (properties.l3().sinkToken() == null || properties.l3().sinkToken().isBlank()) {
                log.warn("channel.l3.sink-token is not configured; backend event delivery will be rejected");
                return;
            }
            l3.events().registerSink(defaultSink, defaultService, properties.l3().sinkToken());
        }
    }

    private void registerServicesAndTargets() {
        for (ChannelProperties.Service service : properties.services()) {
            l3.registry().upsertService(new BackendService(service.serviceRef(), properties.tenantRef(),
                properties.workspaceRef(), service.label(), service.baseUrl(),
                parseAccessType(service.accessType()), service.credentialRef(), service.enabled(),service.deviceRef()));
        }
        for (ChannelProperties.Target target : properties.targets()) {
            l3.registry().upsertTarget(new TargetRegistration(target.targetRef(), properties.tenantRef(),
                properties.workspaceRef(), target.ownerPrincipalRef(), target.serviceRef(), target.sessionId(),
                target.resourceEpoch(), target.label(), target.agentRef(), target.capabilities(),
                parseAvailability(target.availability()), target.bindingRevision(), target.enabled()));
        }
    }

    private void registerEntries() {
        for (ChannelProperties.Entry entry : properties.entries()) {
            EntryConfig config = new EntryConfig(properties.tenantRef(), properties.workspaceRef(),
                entry.entryRef(), entry.name(), entry.adapterType(), entry.configVersion(), 1L,
                entry.accountBindingEpoch(), entry.enabled(),
                entry.settings().isEmpty() ? Json.obj()
                    : Json.mapper().valueToTree(entry.settings()),
                entry.credentials(), connectionPolicy(entry), entry.channelPolicyRef());
            EntryConfig existing = l2.management().findEntry(entry.entryRef());
            if (existing == null) {
                l2.management().createEntry(config, entry.expectedExternalAccountKey());
            } else if (!existing.adapterType().equals(config.adapterType())) {
                log.warn("entry {} adapter type changed in configuration; update it through the management API",
                    entry.entryRef());
            }
        }
    }

    private ConnectionPolicy connectionPolicy(ChannelProperties.Entry entry) {
        if (entry.connectionPolicy() == null || entry.connectionPolicy().isEmpty()) {
            return ConnectionPolicy.defaults();
        }
        var node = Json.mapper().valueToTree(entry.connectionPolicy());
        var defaults = ConnectionPolicy.defaults();
        var reconnectNode = node.path("reconnect");
        Integer maxAttempts = reconnectNode.hasNonNull("maxAttempts")
            ? reconnectNode.path("maxAttempts").asInt() : null;
        return new ConnectionPolicy(node.path("connectTimeoutMs").asLong(defaults.connectTimeoutMs()),
            node.path("probeIntervalMs").asLong(defaults.probeIntervalMs()),
            node.path("probeTimeoutMs").asLong(defaults.probeTimeoutMs()),
            new ConnectionPolicy.Reconnect(reconnectNode.path("enabled").asBoolean(true),
                reconnectNode.path("initialDelayMs").asLong(defaults.reconnect().initialDelayMs()),
                reconnectNode.path("maxDelayMs").asLong(defaults.reconnect().maxDelayMs()), maxAttempts));
    }

    private static AccessType parseAccessType(String value) {
        try {
            return AccessType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown backend access type: "+value,e);
        }
    }

    private static TargetSummary.Availability parseAvailability(String value) {
        try {
            return TargetSummary.Availability.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return TargetSummary.Availability.UNKNOWN;
        }
    }

    /** 周期任务：L2 派发/投递/重试与连接监督，L3 回传泵。 */
    public int tick() {
        if(!pendingConnectorSinks.isEmpty() && System.currentTimeMillis()>=nextSinkRetry){
            nextSinkRetry=System.currentTimeMillis()+5_000;
            for(var sink:pendingConnectorSinks){
                var service=l3.registry().service(sink.serviceRef());
                if(service==null || !"online".equals(l3.connectors().state(service.deviceRef())))continue;
                try{
                    var result=l3.registerRemoteSink(sink.serviceRef(),sink.sinkRef(),sink.requestKey(),sink.expectedRevision(),
                        sink.receiverId(),sink.callbackUrl(),sink.callbackAuthBindingRef(),true);
                    l3.events().registerSink(sink.sinkRef(),sink.serviceRef(),sink.token()==null?properties.l3().sinkToken():sink.token(),result.path("revision").asLong());
                    pendingConnectorSinks.remove(sink);
                }catch(RuntimeException ignored){ /* Keep the original registration request for recovery. */ }
            }
        }
        int actions = l2.tick();
        actions += l3.forwardOnce(50);
        return actions;
    }

    public ChannelProperties properties() {
        return properties;
    }

    public L1Registry l1() {
        return l1;
    }

    public L2Module l2() {
        return l2;
    }

    public L3Module l3() {
        return l3;
    }

    public Db db() {
        return db;
    }

    @Override
    public void close() {
        l2.close();
        l3.close();
        db.close();
    }

    /** 可注入的委托端口：L3 在启动后才持有 L2 的持久接管实现。 */
    private static final class DelegatingIntake implements ApplicationIntakePort {
        private volatile ApplicationIntakePort delegate;

        void bind(ApplicationIntakePort delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletionStage<IntakeAck> receiveEvent(VerifiedBackendEvent event) {
            ApplicationIntakePort target = delegate;
            if (target == null) {
                return CompletableFuture.completedFuture(IntakeAck.rejected(
                    com.netlivecowork.channel.contract.ChannelError.of(
                        com.netlivecowork.channel.contract.ChannelErrorCode.UNAVAILABLE,
                        "application intake is not ready")));
            }
            return target.receiveEvent(event);
        }

        @Override
        public CompletionStage<IntakeAck> receiveReceipt(ReceiptPacket receipt) {
            ApplicationIntakePort target = delegate;
            if (target == null) {
                return CompletableFuture.completedFuture(IntakeAck.rejected(
                    com.netlivecowork.channel.contract.ChannelError.of(
                        com.netlivecowork.channel.contract.ChannelErrorCode.UNAVAILABLE,
                        "application intake is not ready")));
            }
            return target.receiveReceipt(receipt);
        }
    }
}
