package com.netlivecowork.channel.l2;

import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.PageRequest;
import com.netlivecowork.channel.contract.TargetSummary;
import com.netlivecowork.channel.contract.TrustedCall;
import com.netlivecowork.channel.contract.adapter.ActivationResult;
import com.netlivecowork.channel.contract.adapter.AdapterConfig;
import com.netlivecowork.channel.contract.adapter.AdapterDescriptor;
import com.netlivecowork.channel.contract.adapter.AdapterFactory;
import com.netlivecowork.channel.contract.adapter.AuthPort;
import com.netlivecowork.channel.contract.adapter.AuthSession;
import com.netlivecowork.channel.contract.adapter.ConnectionTestPort;
import com.netlivecowork.channel.contract.adapter.EntryConfig;
import com.netlivecowork.channel.contract.adapter.ProbeResult;
import com.netlivecowork.channel.contract.adapter.ValidationResult;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.port.AdapterCatalog;
import com.netlivecowork.channel.contract.port.BackendAccessPort;
import com.netlivecowork.channel.infra.Db;
import com.netlivecowork.channel.l2.store.DeliveryRepository;
import com.netlivecowork.channel.l2.store.DirectoryRepository;
import com.netlivecowork.channel.l2.store.EntryRepository;
import com.netlivecowork.channel.l2.store.RequestRepository;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 管理端能力（l2-application.md §5）：配置校验、启停、状态观测、连接测试与凭据写入。
 * 管理面不通过平台消息暴露，也不返回凭据（INV-52）。
 */
public final class ManagementService {

    private final Db db;
    private final Clock clock;
    private final L2Settings settings;
    private final EntryRepository entries;
    private final DirectoryRepository directory;
    private final DeliveryRepository deliveries;
    private final AdapterCatalog catalog;
    private final ConnectionManager connections;
    private final BackendAccessPort backend;
    private final HostFacilities hosts;

    public ManagementService(Db db, Clock clock, L2Settings settings, EntryRepository entries,
                             DirectoryRepository directory, DeliveryRepository deliveries,
                             AdapterCatalog catalog, ConnectionManager connections,
                             BackendAccessPort backend, HostFacilities hosts) {
        this.db = db;
        this.clock = clock;
        this.settings = settings;
        this.entries = entries;
        this.directory = directory;
        this.deliveries = deliveries;
        this.catalog = catalog;
        this.connections = connections;
        this.backend = backend;
        this.hosts = hosts;
    }

    public List<AdapterDescriptor> listAdapterDescriptors() {
        return catalog.descriptors();
    }

    /** 已登记的接入配置；供装配层/管理端展示，不暴露 Repository。 */
    public List<EntryConfig> listEntries() {
        return entries.all();
    }

    /** 已同步的逻辑目标视图；供管理端展示，不暴露 Repository。 */
    public List<DirectoryRepository.TargetRow> listTargets() {
        return directory.targets(settings.tenantRef(), settings.workspaceRef());
    }

    /** 供装配层判断接入是否已登记；不存在返回 null。 */
    public EntryConfig findEntry(String entryRef) {
        return entries.find(entryRef);
    }

    /** 同时校验公共字段与 Adapter 配置；不执行联网检测。 */
    public ValidationResult validateEntryConfig(EntryConfig draft) {
        if (draft.entryRef() == null || draft.entryRef().isBlank()) {
            return ValidationResult.failed("entryRef", "REQUIRED", "entryRef is required");
        }
        AdapterFactory factory = catalog.factory(draft.adapterType());
        if (factory == null) {
            return ValidationResult.failed("adapterType", "UNKNOWN_TYPE",
                "unknown adapter type: " + draft.adapterType());
        }
        ValidationResult adapterResult = factory.validateConfig(
            new AdapterConfig(draft.configVersion(), draft.settings(),
                List.copyOf(draft.credentials().keySet())));
        if (!adapterResult.valid()) {
            return adapterResult;
        }
        return ValidationResult.ok();
    }

    public EntryConfig createEntry(EntryConfig draft, String expectedExternalAccountKey) {
        ValidationResult validation = validateEntryConfig(draft);
        if (!validation.valid()) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "entry configuration is invalid: " + validation.issues().get(0).message());
        }
        if (entries.find(draft.entryRef()) != null) {
            throw new ChannelContractException(ChannelErrorCode.REQUEST_CONFLICT, "entry already exists");
        }
        EntryConfig stored = withRevision(draft, 1L);
        entries.save(stored, expectedExternalAccountKey);
        connections.applyEnabled(stored.entryRef(), stored.enabled());
        return stored;
    }

    public EntryConfig updateEntry(EntryConfig draft, long expectedRevision, String expectedExternalAccountKey) {
        EntryConfig existing = entries.find(draft.entryRef());
        if (existing == null) {
            throw new ChannelContractException(ChannelErrorCode.REQUEST_NOT_FOUND, "unknown entry");
        }
        if (existing.revision() != expectedRevision) {
            throw new ChannelContractException(ChannelErrorCode.BINDING_CONFLICT,
                "entry revision changed; re-read before updating");
        }
        ValidationResult validation = validateEntryConfig(draft);
        if (!validation.valid()) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "entry configuration is invalid: " + validation.issues().get(0).message());
        }
        EntryConfig stored = withRevision(draft, expectedRevision + 1);
        boolean instanceAffecting = !existing.adapterType().equals(stored.adapterType())
            || !existing.settings().equals(stored.settings())
            || !existing.credentials().equals(stored.credentials());
        entries.save(stored, expectedExternalAccountKey == null
            ? entries.expectedExternalAccountKey(stored.entryRef()) : expectedExternalAccountKey);
        if (instanceAffecting) {
            // 第一版按停旧建新应用配置变化；旧回调由代次检查拒绝（INV-12、INV-49）。
            connections.stopEntry(stored.entryRef(), com.netlivecowork.channel.contract.adapter.StopReason
                .RECONFIGURE);
            connections.applyEnabled(stored.entryRef(), stored.enabled());
        }
        return stored;
    }

    public EntryConfig setEntryEnabled(String entryRef, boolean enabled, long expectedRevision) {
        EntryConfig existing = entries.find(entryRef);
        if (existing == null) {
            throw new ChannelContractException(ChannelErrorCode.REQUEST_NOT_FOUND, "unknown entry");
        }
        if (existing.revision() != expectedRevision) {
            throw new ChannelContractException(ChannelErrorCode.BINDING_CONFLICT,
                "entry revision changed; re-read before updating");
        }
        EntryConfig stored = new EntryConfig(existing.tenantRef(), existing.workspaceRef(), existing.entryRef(),
            existing.name(), existing.adapterType(), existing.configVersion(), expectedRevision + 1,
            existing.accountBindingEpoch(), enabled, existing.settings(), existing.credentials(),
            existing.connectionPolicy(), existing.channelPolicyRef());
        entries.save(stored, entries.expectedExternalAccountKey(entryRef));
        connections.applyEnabled(entryRef, enabled);
        return stored;
    }

    public record EntryView(EntryConfig config, EntryRepository.Runtime runtime, AdapterDescriptor descriptor,
                            String effectiveAccountKey) {
    }

    /** 区分期望启用、入口就绪与连接在线（l2-application.md §5）。 */
    public EntryView entryStatus(String entryRef) {
        EntryConfig config = entries.find(entryRef);
        if (config == null) {
            throw new ChannelContractException(ChannelErrorCode.REQUEST_NOT_FOUND, "unknown entry");
        }
        return new EntryView(config, connections.runtime(entryRef), catalog.descriptor(config.adapterType()),
            entries.expectedExternalAccountKey(entryRef));
    }

    public void reconnectEntry(String entryRef) {
        connections.reconnect(entryRef);
    }

    /** 在线测试：绑定管理员、凭证范围与超时，不提交业务任务。 */
    public ProbeResult testEntryConnection(String entryRef) {
        EntryConfig config = entries.find(entryRef);
        if (config == null) {
            throw new ChannelContractException(ChannelErrorCode.REQUEST_NOT_FOUND, "unknown entry");
        }
        AdapterFactory factory = catalog.factory(config.adapterType());
        if (factory == null) {
            return ProbeResult.unknown("unknown adapter type");
        }
        ConnectionTestPort port = factory.connectionTest();
        if (port == null) {
            return ProbeResult.unknown("adapter does not support connection testing");
        }
        try {
            return port.testConnection(new AdapterConfig(config.configVersion(), config.settings(),
                    List.copyOf(config.credentials().keySet())),
                hosts.secretsFor(config), config.connectionPolicy().connectTimeoutMs())
                .toCompletableFuture().get(config.connectionPolicy().connectTimeoutMs(),
                    TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return ProbeResult.unhealthy(com.netlivecowork.channel.contract.ChannelError.of(
                ChannelErrorCode.UNAVAILABLE, "connection test did not complete"));
        }
    }

    public AuthSession beginEntryAuth(String entryRef) {
        return auth(entryRef).beginAuth().toCompletableFuture().join();
    }

    public AuthSession entryAuthStatus(String entryRef, String authRef) {
        return auth(entryRef).authStatus(authRef).toCompletableFuture().join();
    }

    public void cancelEntryAuth(String entryRef, String authRef) {
        auth(entryRef).cancelAuth(authRef).toCompletableFuture().join();
    }

    private AuthPort auth(String entryRef) {
        AuthPort port = connections.authPort(entryRef);
        if (port == null) {
            throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CAPABILITY,
                "entry does not support interactive authorization");
        }
        return port;
    }

    public List<DeliveryRepository.DeliveryRow> listDeliveries(String status, int limit) {
        return deliveries.list(status == null || status.isBlank() ? null : status,
            Math.clamp(limit, 1, 200));
    }

    public DeliveryRepository.DeliveryRow getDelivery(String deliveryId) {
        DeliveryRepository.DeliveryRow row = deliveries.delivery(deliveryId);
        if (row == null) {
            throw new ChannelContractException(ChannelErrorCode.REQUEST_NOT_FOUND, "unknown delivery");
        }
        return row;
    }

    /**
     * 人工核对未知投递：accepted 需要平台侧依据（例如人工在平台确认已收到），failed 需要明确未送达依据；
     * 只允许从 UNKNOWN 迁移，且不会触发重发（S55～S57）。
     */
    public DeliveryRepository.DeliveryRow reconcileDelivery(String deliveryId, boolean accepted,
                                                            String receiptRef, String note) {
        DeliveryRepository.DeliveryRow row = getDelivery(deliveryId);
        DeliveryRepository.DeliveryStatus status = accepted
            ? DeliveryRepository.DeliveryStatus.ACCEPTED : DeliveryRepository.DeliveryStatus.FAILED;
        boolean updated = deliveries.markReconciled(deliveryId, status, receiptRef,
            note == null ? "reconciled by operator" : note);
        if (!updated) {
            throw new ChannelContractException(ChannelErrorCode.BINDING_CONFLICT,
                "delivery is not in UNKNOWN state; it cannot be reconciled");
        }
        return deliveries.delivery(deliveryId);
    }

    /** 从 L3 可信登记同步逻辑目标，L2 只保存逻辑引用与名称（INV-11）。 */
    public int syncTargets() {
        TrustedCall call = new TrustedCall(settings.tenantRef(), settings.workspaceRef(), "system",
            "system:l2-registry", null, 0, clock.instant().plusSeconds(30));
        List<TargetSummary> targets;
        try {
            targets = backend.listTargets(call, null, PageRequest.first()).toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return 0;
        }
        for (TargetSummary summary : targets) {
            directory.upsertTarget(new DirectoryRepository.TargetRow(summary.targetRef(),
                settings.tenantRef(), settings.workspaceRef(), summary.agentRef(), summary.label(),
                summary.capabilities(), summary.availability(), null, 1L));
        }
        applyOwnershipFacts();
        return targets.size();
    }

    /**
     * 取回并应用 L3 核验的归属事实（v2.11 §2.6）：只有携带版本且仍在有效期内的后端事实才成为
     * 授权依据；legacy 无有效期记录不回填，保持门控。事实不可用时不缓存猜测值。
     *
     * @return 本次实际写入的目标数
     */
    int applyOwnershipFacts() {
        TrustedCall call = new TrustedCall(settings.tenantRef(), settings.workspaceRef(), "system",
            "system:l2-registry", null, 0, clock.instant().plusSeconds(30));
        int applied = 0;
        try {
            for (com.netlivecowork.channel.contract.TargetOwnership ownership
                : backend.targetOwnerships(call).toCompletableFuture().get(10, TimeUnit.SECONDS)) {
                if (ownership.ownerPrincipalRef() == null || ownership.ownerPrincipalRef().isBlank()
                    || ownership.validUntil() == null || ownership.revision() < 1
                    || !ownership.validUntil().isAfter(clock.instant())) {
                    continue;
                }
                if (directory.applyOwnership(ownership.targetRef(), settings.tenantRef(),
                    settings.workspaceRef(), ownership.ownerPrincipalRef(), ownership.resourceEpoch(),
                    ownership.revision(), ownership.validUntil())) {
                    applied++;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // 事实不可用时不回填、不缓存猜测值。
        }
        return applied;
    }

    /**
     * 账号换绑：递增账号代次、清理旧代次的身份映射，并返回新的接入配置。旧 admission 与旧代次
     * 排队工作由代次比较拦截（SM07 §9.1）。
     */
    public EntryConfig rebindAccount(String entryRef, long expectedRevision) {
        long epoch = entries.bumpAccountBindingEpoch(entryRef, expectedRevision);
        directory.purgeIdentitiesBelowEpoch(entryRef, epoch);
        // 旧实例携带旧账号上下文，必须停用：重启后旧宿主设施因代次过期而失效（S58～S60）。
        try {
            connections.reconnect(entryRef);
        } catch (RuntimeException e) {
            // 适配器暂时无法重连不应回滚账号换绑；下次连接周期按新代次继续。
        }
        return entries.find(entryRef);
    }

    /**
     * 绑定外部身份到内部主体。换绑（同一外部身份改绑到另一个 callerRef）时，撤销原绑定的目的地版本
     * 与范围绑定、提升绑定版本，全部在同一个事务内提交（SM07 §9.3 失效屏障）。
     */
    public long bindIdentity(String entryRef, String subjectKind, String subjectKey, String callerRef) {
        EntryConfig entry = entries.find(entryRef);
        if (entry == null) {
            throw new ChannelContractException(ChannelErrorCode.REQUEST_NOT_FOUND, "unknown entry");
        }
        return directory.rebindIdentity(entryRef, entry.accountBindingEpoch(), subjectKind, subjectKey,
            callerRef);
    }

    /** 只读协议状态（实例/代次/Adapter 版本限定），用于核对游标与确定性拒绝记录，不返回凭据。 */
    public com.fasterxml.jackson.databind.JsonNode protocolState(String entryRef, String key) {
        EntryConfig entry = entries.find(entryRef);
        if (entry == null) {
            throw new ChannelContractException(ChannelErrorCode.REQUEST_NOT_FOUND, "unknown entry");
        }
        AdapterDescriptor descriptor = catalog.descriptor(entry.adapterType());
        String version = descriptor == null ? "unknown" : descriptor.implementationVersion();
        var state = entries.state(entryRef, connections.generation(entryRef), version, key);
        if (state == null) {
            throw new ChannelContractException(ChannelErrorCode.REQUEST_NOT_FOUND,
                "protocol state is not present for this key");
        }
        return state.value();
    }

    /**
     * 登记/更新目的地。已有记录必须带当前版本，版本单调递增，不能被重置为 1（INV-13）。
     */
    public long registerDestination(String destinationRef, String entryRef, String audience,
                                    com.fasterxml.jackson.databind.JsonNode protocolData,
                                    long expectedRevision) {
        EntryConfig entry = entries.find(entryRef);
        if (entry == null) {
            throw new ChannelContractException(ChannelErrorCode.REQUEST_NOT_FOUND, "unknown entry");
        }
        return directory.registerDestination(destinationRef, entry.tenantRef(), entry.workspaceRef(), entryRef,
            entry.accountBindingEpoch(), audience, protocolData == null ? Json.obj() : protocolData,
            expectedRevision);
    }

    public void registerTarget(String targetRef, String label, String agentRef,
                               TargetSummary.Availability availability) {
        directory.upsertTarget(new DirectoryRepository.TargetRow(targetRef, settings.tenantRef(),
            settings.workspaceRef(), agentRef, label, List.of(), availability, null, 1L));
    }

    private EntryConfig withRevision(EntryConfig draft, long revision) {
        return new EntryConfig(draft.tenantRef(), draft.workspaceRef(), draft.entryRef(), draft.name(),
            draft.adapterType(), draft.configVersion(), revision, draft.accountBindingEpoch(), draft.enabled(),
            draft.settings(), draft.credentials(), draft.connectionPolicy(), draft.channelPolicyRef());
    }
}
