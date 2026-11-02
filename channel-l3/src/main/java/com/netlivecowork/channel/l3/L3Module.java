package com.netlivecowork.channel.l3;

import com.netlivecowork.channel.contract.FileMetadata;
import com.netlivecowork.channel.contract.port.ApplicationIntakePort;
import com.netlivecowork.channel.contract.port.BackendAccessPort;
import com.netlivecowork.channel.infra.Db;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * L3 后端访问层装配入口（l3-backend.md）。L2 只经 {@link #access()} 调用，事件经
 * {@link #events()} 接入；服务地址与凭证不出本层（INV-11、52）。
 *
 * <p>生命周期：{@link #start()} 启动虚拟线程回传泵；{@link #close()} 只停止本模块自有的后台线程与
 * 执行器，不关闭注入的数据库或 L2 端口。
 */
public final class L3Module implements AutoCloseable {

    private final BackendProtocol protocol;
    private final DeviceRegistry devices;
    private final ConnectorHub connectors;
    public DeviceRegistry devices(){return devices;}
    public ConnectorHub connectors(){return connectors;}
    private final DispatchStore dispatches;
    private final BackendRegistry registry;
    private final EventIngestService events;
    private final BackendAccessService access;
    private final ForwardPump pump;
    private final InteractionStore interactions;
    private final ExecutorService executor;

    public L3Module(Db db, Clock clock, L3Settings settings, ApplicationIntakePort intake) {
        this(db, clock, settings, intake, ServiceCredentialResolver.NOOP);
    }

    public L3Module(Db db, Clock clock, L3Settings settings, ApplicationIntakePort intake,
                    ServiceCredentialResolver credentials) {
        this(db, clock, settings, intake, credentials, BackendIdentityResolver.UNAVAILABLE);
    }

    public L3Module(Db db, Clock clock, L3Settings settings, ApplicationIntakePort intake,
                    ServiceCredentialResolver credentials, BackendIdentityResolver identities) {
        this(db,clock,settings,intake,credentials,identities,DeviceSecrets.DISABLED);
    }

    public L3Module(Db db,Clock clock,L3Settings settings,ApplicationIntakePort intake,
                    ServiceCredentialResolver credentials,BackendIdentityResolver identities,DeviceSecrets secrets) {
        Objects.requireNonNull(db, "db");
        this.registry = new BackendRegistry(db, Objects.requireNonNull(clock, "clock"));
        L3Settings resolvedSettings = settings == null ? L3Settings.defaults() : settings;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.dispatches = new DispatchStore(db, clock);
        this.interactions = new InteractionStore(db, clock, registry);
        SessionWatchStore watches = new SessionWatchStore(db, clock);
        this.events = new EventIngestService(db, clock, resolvedSettings, registry, dispatches);
        this.devices = new DeviceRegistry(db,clock,registry,secrets);
        this.connectors = new ConnectorHub(devices,registry,events,credentials,clock,resolvedSettings);
        BackendTransport client = new RoutedBackendTransport(new PythonBackendClient(resolvedSettings, credentials),connectors);
        this.protocol = new BackendProtocol(client, identities, clock, db);
        this.access = new BackendAccessService(db, clock, resolvedSettings, registry, dispatches, interactions,
            watches, client, executor, protocol);
        this.access.bindInteractionEvents(this.events);
        this.access.bindDevices(this.devices,this.connectors);
        this.pump = new ForwardPump(events.store(), intake, resolvedSettings);
    }

    public com.fasterxml.jackson.databind.JsonNode registerRemoteSink(String serviceRef, String sinkRef,
            String requestKey, long expectedRevision, String receiverId, String callbackUrl,
            String callbackAuthBindingRef, boolean enabled) {
        try { return protocol.registerSink(registry.service(serviceRef), sinkRef, requestKey, expectedRevision,
            receiverId, callbackUrl, callbackAuthBindingRef, enabled); }
        catch (java.io.IOException e) { throw new IllegalStateException("backend sink registration unavailable"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("backend sink registration interrupted"); }
    }

    public com.fasterxml.jackson.databind.JsonNode replayEvents(String operationRef,
            com.netlivecowork.channel.contract.TrustedCall call, String replayKey, String afterCursor, int limit) {
        Dispatch row = dispatches.load(operationRef);
        if (row == null) throw new IllegalArgumentException("unknown operation");
        try { return protocol.replay(registry.service(row.serviceRef()), row, call, replayKey, afterCursor, limit); }
        catch (java.io.IOException e) { throw new IllegalStateException("backend replay outcome unknown"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("backend replay interrupted"); }
    }

    public BackendAccessPort access() {
        return access;
    }

    public BackendRegistry registry() {
        return registry;
    }

    public EventIngestService events() {
        return events;
    }

    /** 后台回传泵的一次 tick；返回本次确认送达的事件数。 */
    public int forwardOnce(int limit) {
        connectors.tick();
        return pump.forwardOnce(limit);
    }

    public void start() {
        pump.start();
    }

    /** 登记 L2 签发的 fileRef 到不透明后端文件关联（INV-42）；读取经 {@code access().readFile}。 */
    public void registerFile(String fileRef, String targetRef, String backendFileId, FileMetadata metadata) {
        registry.registerFile(fileRef, targetRef, backendFileId, metadata);
    }

    /**
     * 登记内部 interactionRef → 后端事项身份与 revision（INV-59、61）：后端只认自己的
     * backendInteractionId，L2 只认内部引用，这一步是 {@code interaction.respond} 的必要衔接。
     */
    public void registerInteraction(String interactionRef, String targetRef, String backendInteractionId,
                                    long revision) {
        interactions.register(interactionRef, targetRef, backendInteractionId, revision);
    }

    @Override
    public void close() {
        connectors.close();
        pump.close();
        executor.shutdownNow();
    }
}
