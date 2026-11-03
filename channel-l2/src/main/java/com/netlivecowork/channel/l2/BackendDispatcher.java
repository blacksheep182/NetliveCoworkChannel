package com.netlivecowork.channel.l2;

import com.netlivecowork.channel.contract.ChannelError;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.ChannelResponse;
import com.netlivecowork.channel.contract.CommandOutcome;
import com.netlivecowork.channel.contract.CommandPacket;
import com.netlivecowork.channel.contract.CommandType;
import com.netlivecowork.channel.contract.DispatchStatus;
import com.netlivecowork.channel.contract.ReceiptPacket;
import com.netlivecowork.channel.contract.ReceiptPayload;
import com.netlivecowork.channel.contract.TrustedCall;
import com.netlivecowork.channel.contract.adapter.EntryConfig;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.json.PacketJson;
import com.netlivecowork.channel.contract.port.BackendAccessPort;
import com.netlivecowork.channel.infra.Digests;
import com.netlivecowork.channel.l2.store.DirectoryRepository;
import com.netlivecowork.channel.l2.store.EntryRepository;
import com.netlivecowork.channel.l2.store.RequestRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 指令派发：写操作后台派发，查询同步派发；网络调用在事务外（INV-20）。
 * 派发结果区分确定未发送、发送结果未知、后端确认接收（l3-routing-and-recovery.md §5）。
 */
public final class BackendDispatcher {

    private final L2Settings settings;
    private final Clock clock;
    private final EntryRepository entries;
    private final DirectoryRepository directory;
    private final RequestRepository requests;
    private final BackendAccessPort backend;
    private final GrantService grants;
    private GroupWorkspaceGrantService groupGrants;
    void bindGroupGrants(GroupWorkspaceGrantService groupGrants) { this.groupGrants=groupGrants; }
    private EventIntakeService interactionIntake;
    void bindInteractionIntake(EventIntakeService intake) { this.interactionIntake=intake; }
    private final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();

    public BackendDispatcher(L2Settings settings, Clock clock, EntryRepository entries,
                             DirectoryRepository directory, RequestRepository requests,
                             BackendAccessPort backend, GrantService grants) {
        this.settings = settings;
        this.clock = clock;
        this.entries = entries;
        this.directory = directory;
        this.requests = requests;
        this.backend = backend;
        this.grants = grants;
    }

    public List<com.netlivecowork.channel.contract.SessionWorkspace> creationWorkspaces(EntryConfig entry,String caller) {
        return backend.creationWorkspaces(trustedCall(entry,caller)).toCompletableFuture().join();
    }
    public com.netlivecowork.channel.contract.SessionCreation.Created createSession(EntryConfig entry,String caller,String request,String workspace,String prompt,boolean lookupOnly) {
        try { return backend.createSession(trustedCall(entry,caller),request,workspace,prompt,lookupOnly).toCompletableFuture().get(settings.deliveryTimeoutMs(),TimeUnit.MILLISECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw new java.util.concurrent.CompletionException(e); }
        catch(Exception e) {throw new java.util.concurrent.CompletionException(e);}
    }

    /** 后台派发：只安排一次，重复调用无副作用（claim 保证）。 */
    public void schedule(String operationRef) {
        async.submit(() -> {
            if (requests.claimDispatch(operationRef)) {
                RequestRepository.InboxRow row = requests.inboxByOperation(operationRef);
                if (row != null) {
                    dispatchRow(row);
                }
            }
        });
    }

    /** 周期性派发待处理记录。 */
    public int dispatchPending(int limit) {
        int dispatched = 0;
        for (RequestRepository.InboxRow row : requests.pendingDispatch(limit)) {
            if (requests.claimDispatch(row.operationRef())) {
                dispatchRow(row);
                dispatched++;
            }
        }
        return dispatched;
    }

    /** Read original acceptance facts. No branch in this recovery path calls submitCommand. */
    public int reconcileUnknown(int limit) {
        int resolved = 0;
        for (var row : requests.unknownOperations(limit)) {
            if (!requests.deferReconciliation(row.operationRef(),clock.instant().plusSeconds(5))) continue;
            EntryConfig entry = entries.find(row.entryRef());
            if (entry == null) continue;
            try {
                CommandPacket packet = com.netlivecowork.channel.l2.store.StoredCommandCodec.decode(row.commandJson());
                var result = backend.lookupOperation(row.operationRef(),trustedCall(entry,packet.actor().principalRef()))
                    .toCompletableFuture().get(settings.deliveryTimeoutMs(),TimeUnit.MILLISECONDS);
                if (!row.operationRef().equals(result.operationRef()) || result.receipt() == null
                    || !row.operationRef().equals(result.receipt().context().operationRef())) continue;
                boolean accepted = result.status() == DispatchStatus.AGENT_ACCEPTED
                    && (result.receipt().payload() instanceof ReceiptPayload.Accepted receipt
                        && receipt.acceptedBy() == ReceiptPayload.AcceptedBy.AGENT
                        || result.receipt().payload() instanceof ReceiptPayload.Completed);
                boolean rejected = result.status() == DispatchStatus.NOT_SENT
                    && result.receipt().payload() instanceof ReceiptPayload.Rejected;
                if (!accepted && !rejected) continue;
                applyOutcome(row,entry,packet,new CommandOutcome(result.status(),row.operationRef(),result.receipt(),result.error()));
                resolved++;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();break;
            } catch (Exception ignored) {
                // Failed reads keep the original operation unknown; they never authorize a resend.
            }
        }
        return resolved;
    }

    private void dispatchRow(RequestRepository.InboxRow row) {
        EntryConfig entry = entries.find(row.entryRef());
        if (entry == null) {
            requests.updateInbox(RequestRepository.OperationStatus.REJECTED,
                Json.write(PacketJson.enc(new ChannelResponse.Rejected(row.requestId(),
                    ChannelError.of(ChannelErrorCode.UNAVAILABLE, "entry is no longer configured")))),
                row.operationRef());
            return;
        }
        CommandPacket packet;
        // 派发前按“原绑定引用 + 原版本”复验（S67、S68）：不得用 caller 聚合版本，也不得因 caller
        // 重新存在而放行旧快照。
        String subjectKind = row.identitySubjectKind();
        String subjectKey = row.identitySubjectKey();
        if (row.accountBindingEpoch() != entry.accountBindingEpoch()) {
            // 账号换绑（代次递增）后，旧代次的排队工作一律不得借用新绑定派发（SM07 §9.3）。
            requests.updateInbox(RequestRepository.OperationStatus.REJECTED,
                Json.write(PacketJson.enc(new ChannelResponse.Rejected(row.requestId(),
                    ChannelError.of(ChannelErrorCode.IDENTITY_UNBOUND,
                        "account binding epoch changed; queued work is not dispatched")))),
                row.operationRef());
            return;
        }
        if (subjectKind == null || subjectKey == null) {
            requests.updateInbox(RequestRepository.OperationStatus.QUARANTINED,
                Json.write(PacketJson.enc(new ChannelResponse.Unknown(row.requestId(), row.operationRef(),
                    ChannelError.of(ChannelErrorCode.OUTCOME_UNKNOWN,
                        "queued work lacks its original identity binding; quarantined for review")))),
                row.operationRef());
            return;
        }
        DirectoryRepository.IdentityBinding binding = directory.identity(entry.entryRef(),
            entry.accountBindingEpoch(), subjectKind, subjectKey);
        boolean bindingCurrent = binding != null && binding.callerRef().equals(row.callerRef())
            && binding.revision() == row.identityBindingRevision();
        if (!bindingCurrent) {
            requests.updateInbox(RequestRepository.OperationStatus.REJECTED,
                Json.write(PacketJson.enc(new ChannelResponse.Rejected(row.requestId(),
                    ChannelError.of(ChannelErrorCode.IDENTITY_UNBOUND,
                        "identity binding changed; queued work is not dispatched")))),
                row.operationRef());
            return;
        }
        try {
            packet = com.netlivecowork.channel.l2.store.StoredCommandCodec.decode(row.commandJson());
        } catch (RuntimeException e) {
            requests.updateInbox(RequestRepository.OperationStatus.REJECTED,
                Json.write(PacketJson.enc(new ChannelResponse.Rejected(row.requestId(),
                    ChannelError.of(ChannelErrorCode.INTERNAL_ERROR, "stored command is unreadable")))),
                row.operationRef());
            return;
        }
        if ((groupGrants!=null && !groupGrants.currentOperation(row.operationRef()))
            || !grants.begin(row.operationRef(), () -> true)) {
            requests.updateInbox(RequestRepository.OperationStatus.REJECTED,
                Json.write(PacketJson.enc(new ChannelResponse.Rejected(row.requestId(),
                    ChannelError.of(ChannelErrorCode.PERMISSION_DENIED, "original authorization is no longer valid")))), row.operationRef());
            return;
        }
        TrustedCall call = trustedCall(entry, packet.actor().principalRef());
        if (packet.type().isReadOnly()) {
            dispatchRead(row, entry, packet);
            return;
        }
        CommandOutcome outcome;
        try {
            outcome = backend.submitCommand(packet, call).toCompletableFuture().get(settings.deliveryTimeoutMs(),
                TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            outcome = CommandOutcome.unknown(null,
                ChannelError.of(ChannelErrorCode.OUTCOME_UNKNOWN, "backend dispatch did not confirm"));
        }
        applyOutcome(row, entry, packet, outcome);
    }

    // ---- 查询 ------------------------------------------------------------------------

    /**
     * 待恢复的读命令走查询端口：读操作不能复用写提交，否则 L3 会按“写类型”拒绝且永远无法完成。
     */
    private void dispatchRead(RequestRepository.InboxRow row, EntryConfig entry, CommandPacket packet) {
        String operationRef = row.operationRef();
        ReceiptPacket receipt;
        try {
            receipt = backend.query(packet, trustedCall(entry, packet.actor().principalRef())).toCompletableFuture()
                .get(settings.deliveryTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            retryRead(row, "query did not confirm");
            return;
        }
        requests.saveReceipt(receipt.packetId(), operationRef, packet.packetId(),
            Digests.sha256(Json.write(PacketJson.enc(receipt))), Json.write(PacketJson.enc(receipt)));
        requests.markReceiptApplied(receipt.packetId());
        switch (receipt.payload()) {
            case ReceiptPayload.Completed completed -> requests.updateInbox(
                RequestRepository.OperationStatus.COMPLETED,
                Json.write(PacketJson.enc(new ChannelResponse.Completed(row.requestId(), completed.result()))),
                operationRef);
            case ReceiptPayload.Rejected rejected -> requests.updateInbox(
                RequestRepository.OperationStatus.REJECTED,
                Json.write(PacketJson.enc(new ChannelResponse.Rejected(row.requestId(), rejected.error()))),
                operationRef);
            case ReceiptPayload.Unknown ignored -> retryRead(row, "query result is unknown");
            case ReceiptPayload.Accepted ignored -> retryRead(row, "query returned no result");
        }
    }

    private void retryRead(RequestRepository.InboxRow row, String reason) {
        int attempts = row.dispatchAttempts() + 1;
        if (attempts > settings.dispatchMaxAttempts()) {
            requests.updateInbox(RequestRepository.OperationStatus.UNKNOWN,
                Json.write(PacketJson.enc(new ChannelResponse.Unknown(row.requestId(), row.operationRef(),
                    ChannelError.of(ChannelErrorCode.OUTCOME_UNKNOWN, reason)))), row.operationRef());
            return;
        }
        requests.scheduleDispatch(row.operationRef(), attempts,
            clock.instant().plusMillis(settings.dispatchBackoff(attempts)));
    }

    /** Authorized metadata read for navigation; no model invocation or write dispatch. */
    public com.netlivecowork.channel.contract.SessionSummary readSession(EntryConfig entry, String caller, String target) {
        var packet = new CommandPacket("1",com.netlivecowork.channel.infra.Ids.next("cmd"),CommandType.SESSION_GET,
            clock.instant(),com.netlivecowork.channel.contract.Correlation.of(com.netlivecowork.channel.infra.Ids.next("flow")),
            new com.netlivecowork.channel.contract.PacketContext(entry.tenantRef(),entry.workspaceRef(),target,
                com.netlivecowork.channel.infra.Ids.next("navread"),null),
            new com.netlivecowork.channel.contract.Producer(com.netlivecowork.channel.contract.Layer.L2,settings.componentRef()),
            com.netlivecowork.channel.contract.Actor.of(caller),new com.netlivecowork.channel.contract.CommandPayload.SessionGet());
        grants.capture(packet);
        if (!grants.begin(packet.context().operationRef(),()->true)) throw new com.netlivecowork.channel.contract.ChannelContractException(
            ChannelErrorCode.PERMISSION_DENIED,"navigation query is no longer authorized");
        try {
            var receipt=backend.query(packet,trustedCall(entry,caller)).toCompletableFuture().get(settings.deliveryTimeoutMs(),TimeUnit.MILLISECONDS);
            if (!grants.current(packet.context().operationRef())) throw new com.netlivecowork.channel.contract.ChannelContractException(
                ChannelErrorCode.PERMISSION_DENIED,"authorization changed during navigation query");
            if (receipt.payload() instanceof ReceiptPayload.Completed completed
                && completed.result() instanceof com.netlivecowork.channel.contract.OperationResult.SessionGet get
                && target.equals(get.session().targetRef())) return get.session();
            if (receipt.payload() instanceof ReceiptPayload.Rejected rejected) throw new com.netlivecowork.channel.contract.ChannelContractException(
                rejected.error().errorCode(),rejected.error().message());
        } catch (com.netlivecowork.channel.contract.ChannelContractException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception ignored) { }
        throw new com.netlivecowork.channel.contract.ChannelContractException(ChannelErrorCode.UNAVAILABLE,"Backend session metadata could not be verified");
    }

    public ChannelResponse dispatchQuery(EntryConfig entry, String callerRef, CommandPacket packet,
                                         String requestId) {
        String operationRef = packet.context().operationRef();
        if ((groupGrants!=null && !groupGrants.currentOperation(operationRef))
            || !grants.begin(operationRef, () -> requests.claimDispatch(operationRef))) {
            RequestRepository.InboxRow current = requests.inboxByOperation(operationRef);
            return current != null && current.responseJson() != null
                ? PacketJson.decChannelResponse(current.responseJson())
                : new ChannelResponse.Unknown(requestId, operationRef,
                    ChannelError.of(ChannelErrorCode.OUTCOME_UNKNOWN, "query is already in flight"));
        }
        ReceiptPacket receipt;
        try {
            receipt = backend.query(packet, trustedCall(entry, packet.actor().principalRef())).toCompletableFuture()
                .get(settings.deliveryTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            receipt = unknownReceipt(packet);
        }
        if (!grants.current(operationRef) || groupGrants!=null && !groupGrants.currentOperation(operationRef)) return new ChannelResponse.Rejected(requestId,
            ChannelError.of(ChannelErrorCode.PERMISSION_DENIED, "authorization changed during query"));
        if(interactionIntake!=null && receipt.payload() instanceof ReceiptPayload.Completed completed
            && completed.result() instanceof com.netlivecowork.channel.contract.OperationResult.SessionGet get) {
            try {
                var observed=backend.readInteractionEvents(packet.context().targetRef(),trustedCall(entry,packet.actor().principalRef())).toCompletableFuture()
                    .get(settings.deliveryTimeoutMs(),TimeUnit.MILLISECONDS);
                if(!grants.current(operationRef))throw new IllegalStateException("Interaction read authorization changed");
                for(var event:observed)interactionIntake.receiveEvent(event).toCompletableFuture().join();
                interactionIntake.reprocessPending(1000);
                receipt=new ReceiptPacket(receipt.version(),receipt.packetId(),receipt.createdAt(),receipt.correlation(),receipt.context(),receipt.producer(),
                    new ReceiptPayload.Completed(new com.netlivecowork.channel.contract.OperationResult.SessionGet(get.session(),interactionIntake.pendingInteractions(packet.context().targetRef()))));
            } catch(Exception ignored) {
                // Failed/unsupported recovery is absent, never an authoritative empty pending list.
            }
        }
        if (!grants.current(operationRef) || groupGrants!=null && !groupGrants.currentOperation(operationRef))return new ChannelResponse.Rejected(requestId,ChannelErrorCode.PERMISSION_DENIED.error("Authorization changed during interaction recovery"));
        ChannelResponse response = switch (receipt.payload()) {
            case ReceiptPayload.Completed completed ->
                new ChannelResponse.Completed(requestId, completed.result());
            case ReceiptPayload.Rejected rejected ->
                new ChannelResponse.Rejected(requestId, rejected.error());
            case ReceiptPayload.Unknown unknown ->
                new ChannelResponse.Unknown(requestId, operationRef, unknown.error());
            case ReceiptPayload.Accepted ignored -> new ChannelResponse.Unknown(requestId, operationRef,
                ChannelError.of(ChannelErrorCode.OUTCOME_UNKNOWN, "query returned no result"));
        };
        requests.saveReceipt(receipt.packetId(), operationRef, packet.packetId(),
            Digests.sha256(Json.write(PacketJson.enc(receipt))), Json.write(PacketJson.enc(receipt)));
        requests.updateInbox(statusOf(receipt), Json.write(PacketJson.enc(response)), operationRef);
        return response;
    }

    private RequestRepository.OperationStatus statusOf(ReceiptPacket receipt) {
        return switch (receipt.payload()) {
            case ReceiptPayload.Completed ignored -> RequestRepository.OperationStatus.COMPLETED;
            case ReceiptPayload.Rejected ignored -> RequestRepository.OperationStatus.REJECTED;
            case ReceiptPayload.Unknown ignored -> RequestRepository.OperationStatus.UNKNOWN;
            case ReceiptPayload.Accepted ignored -> RequestRepository.OperationStatus.DISPATCHED;
        };
    }

    private ReceiptPacket unknownReceipt(CommandPacket packet) {
        return new ReceiptPacket("1", com.netlivecowork.channel.infra.Ids.next("receipt"), clock.instant(),
            new com.netlivecowork.channel.contract.Correlation(packet.correlation().flowId(),
                packet.packetId()), packet.context(),
            new com.netlivecowork.channel.contract.Producer(com.netlivecowork.channel.contract.Layer.L3,
                "l3-backend"),
            new ReceiptPayload.Unknown(
                ChannelError.of(ChannelErrorCode.OUTCOME_UNKNOWN, "query result is unknown")));
    }

    // ---- 写操作结果 ------------------------------------------------------------------

    private void applyOutcome(RequestRepository.InboxRow row, EntryConfig entry, CommandPacket packet,
                              CommandOutcome outcome) {
        String operationRef = row.operationRef();
        if (interactionIntake != null && outcome.receipt() != null
            && outcome.receipt().payload() instanceof ReceiptPayload.Completed) {
            interactionIntake.receiveReceipt(outcome.receipt()).toCompletableFuture().join();
            return;
        }
        if (outcome.receipt() != null) {
            requests.saveReceipt(outcome.receipt().packetId(), operationRef, packet.packetId(),
                Digests.sha256(Json.write(PacketJson.enc(outcome.receipt()))),
                Json.write(PacketJson.enc(outcome.receipt())));
        }
        switch (outcome.status()) {
            case AGENT_ACCEPTED -> {
                // 受理事实写入投影：重放时不需要重新派发，陈旧派发回收也不再命中该记录。
                requests.updateInbox(RequestRepository.OperationStatus.DISPATCHED,
                    Json.write(PacketJson.enc(new ChannelResponse.Accepted(row.requestId(), operationRef, null,
                        ReceiptPayload.AcceptedBy.AGENT))),
                    operationRef);
                if (outcome.receipt() != null && outcome.receipt().payload() instanceof ReceiptPayload.Completed
                    && isInteraction(packet)) {
                    RequestRepository.ResponseRow response = requests.responseByOperation(operationRef);
                    if (response != null) {
                        requests.updateResponseStatus(response.responseRef(), "APPLIED");
                    }
                }
            }
            case OUTCOME_UNKNOWN -> {
                ChannelError error = outcome.error() != null ? outcome.error()
                    : ChannelError.of(ChannelErrorCode.OUTCOME_UNKNOWN, "dispatch outcome is unknown");
                ChannelResponse response = new ChannelResponse.Unknown(row.requestId(), operationRef, error);
                requests.updateInbox(RequestRepository.OperationStatus.UNKNOWN,
                    Json.write(PacketJson.enc(response)), operationRef);
                if (isInteraction(packet)) {
                    markResponse(operationRef, "UNKNOWN");
                }
            }
            case NOT_SENT -> applyNotSent(row, packet, outcome);
        }
    }

    private void applyNotSent(RequestRepository.InboxRow row, CommandPacket packet, CommandOutcome outcome) {
        String operationRef = row.operationRef();
        boolean rejected = outcome.receipt() != null
            && outcome.receipt().payload() instanceof ReceiptPayload.Rejected;
        if (rejected) {
            ChannelError error = ((ReceiptPayload.Rejected) outcome.receipt().payload()).error();
            ChannelResponse response = new ChannelResponse.Rejected(row.requestId(), error);
            requests.updateInbox(RequestRepository.OperationStatus.REJECTED,
                Json.write(PacketJson.enc(response)), operationRef);
            if (isInteraction(packet)) {
                markResponse(operationRef, "REJECTED");
                RequestRepository.ResponseRow stored = requests.responseByOperation(operationRef);
                if (stored != null) {
                    requests.resetInteractionToPending(stored.interactionRef());
                }
            }
            return;
        }
        // 确定未发送：保留待派发记录，在有效期内按退避重试（l3-routing-and-recovery.md §5）。
        Instant created = createdAt(row.operationRef());
        boolean expired = created != null
            && created.plus(settings.pendingDispatchTtl()).isBefore(clock.instant());
        int attempts = row.dispatchAttempts() + 1;
        if (expired || attempts >= settings.dispatchMaxAttempts()) {
            boolean closed=false;
            EntryConfig entry=entries.find(row.entryRef());
            try{if(entry!=null)closed=backend.closeUnsent(packet,trustedCall(entry,packet.actor().principalRef())).toCompletableFuture().get(settings.deliveryTimeoutMs(),TimeUnit.MILLISECONDS);}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
            catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException|RuntimeException ignored){}
            if(!closed){
                ChannelResponse unknown=new ChannelResponse.Unknown(row.requestId(),operationRef,
                    ChannelErrorCode.OUTCOME_UNKNOWN.error("Cannot prove the original operation was closed without sending"));
                requests.updateInbox(RequestRepository.OperationStatus.UNKNOWN,Json.write(PacketJson.enc(unknown)),operationRef);
                return;
            }
            ChannelError error = ChannelError.of(ChannelErrorCode.UNAVAILABLE,
                expired ? "target stayed offline; the message was not submitted"
                    : "target is unreachable; the message was not submitted");
            ChannelResponse response = new ChannelResponse.Unknown(row.requestId(), operationRef, error);
            requests.updateInbox(RequestRepository.OperationStatus.UNKNOWN,
                Json.write(PacketJson.enc(response)), operationRef);
            if (isInteraction(packet)) {
                markResponse(operationRef, "NOT_SENT");
                RequestRepository.ResponseRow stored = requests.responseByOperation(operationRef);
                if (stored != null) {
                    requests.resetInteractionToPending(stored.interactionRef());
                }
            }
            return;
        }
        requests.scheduleDispatch(operationRef, attempts,
            clock.instant().plusMillis(settings.dispatchBackoff(attempts)));
    }

    private void markResponse(String operationRef, String status) {
        RequestRepository.ResponseRow response = requests.responseByOperation(operationRef);
        if (response != null) {
            requests.updateResponseStatus(response.responseRef(), status);
        }
    }

    private boolean isInteraction(CommandPacket packet) {
        return packet.type() == CommandType.INTERACTION_RESPOND;
    }

    private Instant createdAt(String operationRef) {
        RequestRepository.InboxRow row = requests.inboxByOperation(operationRef);
        return row == null ? null : row.createdAt();
    }

    /**
     * 可信调用携带实际操作者：后端执行委托按 W3 主体签发并绑定本次调用（v2.11 §2.6），
     * 组件身份不代替用户。缺少操作者主体的内部补偿调用退回组件标识，只可用于无委托路径。
     */
    private TrustedCall trustedCall(EntryConfig entry, String principalRef) {
        Instant now = clock.instant();
        String principal = principalRef == null || principalRef.isBlank() ? "system:l2" : principalRef;
        return new TrustedCall(entry.tenantRef(), entry.workspaceRef(), entry.entryRef(), principal,
            null, entry.revision(), settings.dispatchDeadline(now));
    }

    /** 供管理端核对原操作时构造同样的可信调用。 */
    public TrustedCall trustedCallForOperation(String entryRef, String principalRef) {
        EntryConfig entry = entries.find(entryRef);
        if (entry == null) {
            throw new IllegalStateException("unknown entry: " + entryRef);
        }
        return new TrustedCall(entry.tenantRef(), entry.workspaceRef(), entry.entryRef(), principalRef,
            null, entry.revision(), settings.dispatchDeadline(clock.instant()));
    }

    public List<RequestRepository.InboxRow> pending(int limit) {
        return requests.pendingDispatch(limit);
    }

    public void shutdown() {
        async.shutdownNow();
    }
}
