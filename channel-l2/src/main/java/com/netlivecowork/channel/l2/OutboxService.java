package com.netlivecowork.channel.l2;

import com.netlivecowork.channel.contract.ChannelDelivery;
import com.netlivecowork.channel.contract.ChannelError;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.DeliveryAttempt;
import com.netlivecowork.channel.contract.DeliveryReceipt;
import com.netlivecowork.channel.contract.adapter.AdapterCapabilities;
import com.netlivecowork.channel.contract.adapter.EntryConfig;
import com.netlivecowork.channel.contract.json.PacketJson;
import com.netlivecowork.channel.contract.port.DeliveryPort;
import com.netlivecowork.channel.contract.port.DeliveryQueryPort;
import com.netlivecowork.channel.l2.store.DeliveryRepository;
import com.netlivecowork.channel.l2.store.DirectoryRepository;
import com.netlivecowork.channel.l2.store.EntryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * outbox 投递：worker 领取 → 发送前复验目的地版本与匹配依据 → 事务外调用 L1 → 保存回执
 * （INV-20、INV-35、INV-36、INV-39）。
 *
 * <p>可靠性口径：领取是排他的（status + attempt 乐观栅栏），回执写回带 owner + attempt 围栏；
 * 领取后发现上一次可能已发送（租约过期回收）且平台没有供应商幂等时，不重发而转 unknown 待核对。
 */
public final class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    /** 由连接管理器提供当前实例的投递端口与平台幂等能力；未激活时端口为 null。 */
    public interface AdapterDeliveryAccess {
        DeliveryPort deliveryPort(String entryRef);

        DeliveryQueryPort queryPort(String entryRef);

        /** 平台是否提供可复用的幂等键；没有则崩溃后只能核对、不能盲目重发。 */
        AdapterCapabilities.Idempotency idempotency(String entryRef);
        default AdapterCapabilities.Handover handover(String entryRef) { return AdapterCapabilities.Handover.PROVIDER; }
    }

    private final L2Settings settings;
    private final Clock clock;
    private final EntryRepository entries;
    private final DirectoryRepository directory;
    private final DeliveryRepository deliveries;
    private final AdapterDeliveryAccess adapters;
    private final GrantService grants;
    private GroupWorkspaceGrantService groupGrants;
    void bindGroupGrants(GroupWorkspaceGrantService groupGrants) { this.groupGrants=groupGrants; }
    private DurableInboxService inbox;
    void bindInbox(DurableInboxService inbox) { this.inbox=inbox; }

    public OutboxService(L2Settings settings, Clock clock, EntryRepository entries, DirectoryRepository directory,
                         DeliveryRepository deliveries, AdapterDeliveryAccess adapters, GrantService grants) {
        this.settings = settings;
        this.clock = clock;
        this.entries = entries;
        this.directory = directory;
        this.deliveries = deliveries;
        this.adapters = adapters;
        this.grants = grants;
    }

    public int deliverPending(int limit) {
        int delivered = 0;
        for (DeliveryRepository.DeliveryRow row : deliveries.claimable(limit)) {
            if (attempt(row)) {
                delivered++;
            }
        }
        return delivered;
    }

    /**
     * 核对未知投递：仅当平台具备查询能力时自动核对；核对为 accepted 则更新结论，
     * 否则保留 UNKNOWN 待人工处理。核对永不带出新的发送（S55～S57）。
     */
    public int reconcileUnknown(int limit) {
        int resolved = 0;
        for (DeliveryRepository.DeliveryRow row : deliveries.unknown(limit)) {
            if(inbox!=null && inbox.custody(row.deliveryId()) instanceof DeliveryReceipt.Accepted accepted) {
                if(deliveries.markReconciled(row.deliveryId(),DeliveryRepository.DeliveryStatus.ACCEPTED,accepted.receiptRef(),null))resolved++;
                continue;
            }
            if(inbox!=null && adapters.handover(row.entryRef())==AdapterCapabilities.Handover.DURABLE_INBOX
                && row.attemptCount()<settings.deliveryMaxAttempts() && deliveries.retryInboxUnknown(row.deliveryId())) {
                resolved++;continue;
            }
            DeliveryQueryPort queryPort = adapters.queryPort(row.entryRef());
            if (queryPort == null) {
                continue;
            }
            try {
                DeliveryReceipt receipt = queryPort.queryDelivery(row.deliveryId())
                    .toCompletableFuture().get(settings.deliveryTimeoutMs(), TimeUnit.MILLISECONDS);
                if (receipt instanceof DeliveryReceipt.Accepted accepted
                    && deliveries.markReconciled(row.deliveryId(), DeliveryRepository.DeliveryStatus.ACCEPTED,
                    accepted.receiptRef(), null)) {
                    resolved++;
                }
            } catch (Exception ignored) {
                // 查询失败保持 UNKNOWN，不猜测、不重发。
            }
        }
        return resolved;
    }

    public boolean attempt(String deliveryId) {
        DeliveryRepository.DeliveryRow row = deliveries.delivery(deliveryId);
        return row != null && attempt(row);
    }

    private boolean attempt(DeliveryRepository.DeliveryRow row) {
        String owner = ownerId();
        Instant lease = clock.instant().plusSeconds(settings.deliveryLeaseSeconds());
        int attemptNumber = row.attemptCount() + 1;
        boolean reclaimed = row.status() == DeliveryRepository.DeliveryStatus.SENDING;
        if (!deliveries.markSending(row, owner, lease)) {
            // 另一个 worker 已经领取同一条记录（INV-49 单发送者）
            return false;
        }
        // 先处理"上一次可能已发送"：必须在任何"可重试/入口状态"判断之前，
        // 否则入口停用时的重试会把 unknown 改写成可重发（INV-35、评审复现）。
        if (reclaimed && adapters.idempotency(row.entryRef()) != AdapterCapabilities.Idempotency.PROVIDER) {
            finish(row, owner, attemptNumber, DeliveryRepository.DeliveryStatus.UNKNOWN,
                ChannelErrorCode.OUTCOME_UNKNOWN
                    .error("a previous attempt may have been delivered; reconcile before resending").message());
            return true;
        }
        EntryConfig entry = entries.find(row.entryRef());
        if (entry == null || !entry.enabled()) {
            transientFailure(row, owner, attemptNumber, "entry is disabled or removed");
            return true;
        }
        // 账号换绑后旧代次的目的地不得继续出站：保留可见暂停记录，不投给新账号（S58～S60）。
        DirectoryRepository.DestinationRow destination = directory.destination(row.destinationRef());
        if (destination == null || destination.accountBindingEpoch() != entry.accountBindingEpoch()) {
            finish(row, owner, attemptNumber, DeliveryRepository.DeliveryStatus.PAUSED,
                ChannelErrorCode.ACCOUNT_MISMATCH
                    .error("destination belongs to an older account binding epoch").message());
            return true;
        }
        if (!deliveries.destinationRevisionCurrent(row.destinationRef(), row.destinationRevision())) {
            finish(row, owner, attemptNumber, DeliveryRepository.DeliveryStatus.PAUSED,
                ChannelErrorCode.DESTINATION_STALE.error("destination identity version changed").message());
            return true;
        }
        if (!deliveries.hasActiveRelation(row.deliveryId())) {
            finish(row, owner, attemptNumber, DeliveryRepository.DeliveryStatus.CANCELLED,
                "no active reply or subscription relation");
            return true;
        }
        DeliveryPort port = adapters.deliveryPort(row.entryRef());
        if (port == null) {
            transientFailure(row, owner, attemptNumber, "entry has no active delivery port");
            return true;
        }
        if(!deliveries.freezeHandover(row.deliveryId(),adapters.handover(row.entryRef()).name())) {
            finish(row,owner,attemptNumber,DeliveryRepository.DeliveryStatus.PAUSED,"Receiver handover mode changed");return true;
        }
        ChannelDelivery delivery;
        try {
            delivery = PacketJson.decDelivery(row.deliveryJson());
        } catch (RuntimeException e) {
            finish(row, owner, attemptNumber, DeliveryRepository.DeliveryStatus.FAILED,
                ChannelErrorCode.INTERNAL_ERROR.error("stored delivery is unreadable").message());
            return true;
        }
        // Complete relation must retain the original operation basis; new grants cannot revive it.
        boolean disclosure;
        try {
            disclosure=destination.callerRef()!=null
                ? grants.beginDelivery(row.deliveryId(), destination.callerRef(), entry.entryRef())
                : groupGrants!=null && groupGrants.currentDelivery(row.deliveryId(),grants);
        } catch (com.netlivecowork.channel.contract.ChannelContractException unavailable) {
            transientFailure(row, owner, attemptNumber, unavailable.getMessage());
            return true;
        }
        if (!disclosure) {
            finish(row, owner, attemptNumber, DeliveryRepository.DeliveryStatus.PAUSED,
                "original disclosure authorization is no longer valid");
            return true;
        }
        DeliveryReceipt receipt;
        try {
            receipt = port.deliver(delivery, new DeliveryAttempt(settings.deliveryTimeoutMs()))
                .toCompletableFuture().get(settings.deliveryTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            receipt = new DeliveryReceipt.Unknown(row.deliveryId(),
                ChannelError.of(ChannelErrorCode.OUTCOME_UNKNOWN, "delivery result is unknown"));
        }
        apply(row, receipt, owner, attemptNumber);
        return true;
    }

    private void apply(DeliveryRepository.DeliveryRow row, DeliveryReceipt receipt, String owner,
                       int attemptNumber) {
        switch (receipt) {
            case DeliveryReceipt.Accepted accepted ->
                fenced(row, owner, attemptNumber, deliveries.markAccepted(row.deliveryId(),
                    accepted.receiptRef(), owner, attemptNumber));
            case DeliveryReceipt.Failed failed -> {
                ChannelError error = failed.error();
                boolean retryable = error.retryable() && attemptNumber < settings.deliveryMaxAttempts();
                if (retryable) {
                    long backoff = error.retryAfterMs() != null ? error.retryAfterMs()
                        : settings.deliveryBackoff(attemptNumber);
                    fenced(row, owner, attemptNumber, deliveries.markRetry(row.deliveryId(),
                        clock.instant().plusMillis(backoff), error.message(), owner, attemptNumber));
                } else {
                    finish(row, owner, attemptNumber, DeliveryRepository.DeliveryStatus.FAILED,
                        error.message());
                }
            }
            case DeliveryReceipt.Unknown unknown -> {
                DeliveryQueryPort queryPort = adapters.queryPort(row.entryRef());
                if (queryPort != null) {
                    try {
                        DeliveryReceipt queried = queryPort.queryDelivery(row.deliveryId())
                            .toCompletableFuture().get(settings.deliveryTimeoutMs(), TimeUnit.MILLISECONDS);
                        if (queried instanceof DeliveryReceipt.Accepted accepted) {
                            fenced(row, owner, attemptNumber, deliveries.markAccepted(row.deliveryId(),
                                accepted.receiptRef(), owner, attemptNumber));
                            return;
                        }
                    } catch (Exception ignored) {
                        // 查询失败仍保留 unknown：不换新 ID 重发（INV-35）。
                    }
                }
                finish(row, owner, attemptNumber, DeliveryRepository.DeliveryStatus.UNKNOWN,
                    unknown.error().message());
            }
        }
    }

    /** 可自动恢复的不可用（接入未激活、启动窗口、断线）按退避重试，只有耗尽才暂停。 */
    private void transientFailure(DeliveryRepository.DeliveryRow row, String owner, int attemptNumber,
                                  String reason) {
        if (attemptNumber < settings.deliveryMaxAttempts()) {
            fenced(row, owner, attemptNumber, deliveries.markRetry(row.deliveryId(),
                clock.instant().plusMillis(settings.deliveryBackoff(attemptNumber)), reason, owner,
                attemptNumber));
            return;
        }
        finish(row, owner, attemptNumber, DeliveryRepository.DeliveryStatus.PAUSED,
            reason + " (attempts exhausted)");
    }

    private void finish(DeliveryRepository.DeliveryRow row, String owner, int attemptNumber,
                        DeliveryRepository.DeliveryStatus status, String reason) {
        fenced(row, owner, attemptNumber, deliveries.markTerminal(row.deliveryId(), status, reason, owner,
            attemptNumber));
    }

    private void fenced(DeliveryRepository.DeliveryRow row, String owner, int attemptNumber, boolean updated) {
        if (!updated) {
            // 记录已被新的领取者接管：旧结论不得覆盖（INV-49）。
            log.debug("delivery {} attempt {} fence lost; result discarded", row.deliveryId(), attemptNumber);
        }
    }

    private String ownerId() {
        return "l2-outbox-" + ProcessHandle.current().pid() + "-" + Thread.currentThread().threadId();
    }
}
