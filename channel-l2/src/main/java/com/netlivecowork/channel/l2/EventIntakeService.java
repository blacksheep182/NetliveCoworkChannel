package com.netlivecowork.channel.l2;

import com.netlivecowork.channel.contract.AgentEventPayload;
import com.netlivecowork.channel.contract.BackendInteraction;
import com.netlivecowork.channel.contract.BackendInteractionRef;
import com.netlivecowork.channel.contract.ChannelDelivery;
import com.netlivecowork.channel.contract.ChannelError;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.ChannelMessage;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.Correlation;
import com.netlivecowork.channel.contract.EventOrder;
import com.netlivecowork.channel.contract.EventPacket;
import com.netlivecowork.channel.contract.EventPayload;
import com.netlivecowork.channel.contract.EventSource;
import com.netlivecowork.channel.contract.EventType;
import com.netlivecowork.channel.contract.IntakeAck;
import com.netlivecowork.channel.contract.InteractionPrompt;
import com.netlivecowork.channel.contract.InteractionRequiredPayload;
import com.netlivecowork.channel.contract.Layer;
import com.netlivecowork.channel.contract.NormalizedContentPart;
import com.netlivecowork.channel.contract.PacketContext;
import com.netlivecowork.channel.contract.Producer;
import com.netlivecowork.channel.contract.ReceiptPacket;
import com.netlivecowork.channel.contract.ReceiptPayload;
import com.netlivecowork.channel.contract.TaskStatus;
import com.netlivecowork.channel.contract.VerifiedBackendEvent;
import com.netlivecowork.channel.contract.json.ContentJson;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.json.PacketJson;
import com.netlivecowork.channel.contract.port.ApplicationIntakePort;
import com.netlivecowork.channel.infra.Db;
import com.netlivecowork.channel.infra.Digests;
import com.netlivecowork.channel.infra.Ids;
import com.netlivecowork.channel.l2.store.DeliveryRepository;
import com.netlivecowork.channel.l2.store.DirectoryRepository;
import com.netlivecowork.channel.l2.store.EventRepository;
import com.netlivecowork.channel.l2.store.RegistrationRepository;
import com.netlivecowork.channel.l2.store.RequestRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 验证事件接管与规范化（INV-28～31）：先持久接管（或保存待关联记录）才确认 L3；
 * 完成内部引用映射后生成不可变 EventPacket，并在同事务安排投递。
 */
public final class EventIntakeService implements ApplicationIntakePort {

    private final Db db;
    private final Clock clock;
    private com.netlivecowork.channel.contract.port.AdapterCatalog catalog;
    void bindCatalog(com.netlivecowork.channel.contract.port.AdapterCatalog catalog) { this.catalog=catalog; }
    private HostSubscriptionDeliveries hostSubscriptions;
    void bindHostSubscriptions(HostSubscriptionDeliveries subscriptions) { this.hostSubscriptions = subscriptions; }
    private final L2Settings settings;
    private final DirectoryRepository directory;
    private final RequestRepository requests;
    private final EventRepository events;
    private final DeliveryRepository deliveries;
    private final com.netlivecowork.channel.contract.port.BackendAccessPort backend;
    private final com.netlivecowork.channel.l2.store.RegistrationRepository registrations;

    public EventIntakeService(Db db, Clock clock, L2Settings settings, DirectoryRepository directory,
                              RequestRepository requests, EventRepository events,
                              DeliveryRepository deliveries,
                              com.netlivecowork.channel.contract.port.BackendAccessPort backend,
                              com.netlivecowork.channel.l2.store.RegistrationRepository registrations) {
        this.db = db;
        this.clock = clock;
        this.settings = settings;
        this.directory = directory;
        this.requests = requests;
        this.events = events;
        this.deliveries = deliveries;
        this.backend = backend;
        this.registrations = registrations;
    }

    @Override
    public CompletionStage<IntakeAck> receiveEvent(VerifiedBackendEvent event) {
        if (event.observation() != null) {
            return CompletableFuture.completedFuture(receiveObservation(event));
        }
        String digest = Digests.sha256(Json.write(PacketJson.enc(event)));
        EventRepository.EventRow existing = events.find(event.source().resourceRef(),
            event.source().sourceEventId());
        if (existing != null) {
            if (existing.payloadDigest().equals(digest)) {
                return CompletableFuture.completedFuture(IntakeAck.ok());
            }
            events.markState(existing.resourceRef(), existing.sourceEventId(),
                EventRepository.EventState.QUARANTINED, existing.packetId(), existing.packetJson(),
                "同一来源事件 ID 出现不同内容，已隔离待核对");
            return CompletableFuture.completedFuture(IntakeAck.rejected(ChannelError.of(
                ChannelErrorCode.REQUEST_CONFLICT, "source event ID was already received with different content")));
        }
        boolean inserted = events.insert(new EventRepository.EventRow(event.source().resourceRef(),
            event.source().sourceEventId(), event.backendEventRef(), digest,
            Json.write(PacketJson.enc(event)), null, null,
            EventRepository.EventState.PENDING_RELATION, null));
        if (!inserted) {
            return CompletableFuture.completedFuture(IntakeAck.ok());
        }
        try {
            normalize(event, digest);
        } catch (RuntimeException e) {
            // 已持久保存待关联记录：确认接管，等待后续核对（不丢事件，也不提前宣称已投递）。
            events.markState(event.source().resourceRef(), event.source().sourceEventId(),
                EventRepository.EventState.PENDING_RELATION, null, null,
                sanitize(e.getMessage()));
        }
        return CompletableFuture.completedFuture(IntakeAck.ok());
    }

    private IntakeAck receiveObservation(VerifiedBackendEvent observed) {
        if (hostSubscriptions == null || observed.type().terminalTaskStatus() == null)
            return IntakeAck.rejected(ChannelError.of(ChannelErrorCode.UNSUPPORTED_CAPABILITY,
                "Cowork completion subscriptions are unavailable"));
        var basis = hostSubscriptions.authorize(observed);
        if (basis == null) return IntakeAck.ok();
        var target = directory.target(observed.targetRef());
        if (target == null) return IntakeAck.ok();
        return db.tx(connection -> {
            var source = observed.source();
            var row = events.find(source.resourceRef(), source.sourceEventId());
            var canonical = new VerifiedBackendEvent(row == null ? observed.backendEventRef() : row.backendEventRef(),
                source, observed.operationRef(), observed.targetRef(), observed.taskRef(),
                observed.type(), observed.occurredAt(), observed.order(), observed.payload());
            String body = Json.write(PacketJson.enc(canonical));
            String digest = Digests.sha256(body);
            if (row == null) {
                events.insert(new EventRepository.EventRow(source.resourceRef(), source.sourceEventId(),
                    canonical.backendEventRef(), digest, body, null, null, EventRepository.EventState.PENDING_RELATION, null));
            } else if (!row.payloadDigest().equals(digest)) {
                return IntakeAck.rejected(ChannelError.of(ChannelErrorCode.REQUEST_CONFLICT,
                    "Observed completion conflicts with the original source event"));
            } else if (row.state() == EventRepository.EventState.PACKETIZED) {
                var packet = PacketJson.decEvent(row.packetJson());
                scheduleDeliveries(packet, target, target.tenantRef(), target.workspaceRef(), packet.context().taskRef(), basis);
                return IntakeAck.ok();
            }
            normalizeInTx(canonical, target, target.tenantRef(), target.workspaceRef(), new ArrayList<>(), basis);
            return IntakeAck.ok();
        });
    }

    @Override
    public CompletionStage<IntakeAck> receiveReceipt(ReceiptPacket receipt) {
        String operationRef = receipt.context().operationRef();
        if (operationRef == null) {
            return CompletableFuture.completedFuture(IntakeAck.rejected(ChannelError.of(
                ChannelErrorCode.INVALID_ARGUMENT, "receipt requires context.operationRef")));
        }
        RequestRepository.InboxRow inbox = requests.inboxByOperation(operationRef);
        if (inbox == null) {
            return CompletableFuture.completedFuture(IntakeAck.rejected(ChannelError.of(
                ChannelErrorCode.REQUEST_NOT_FOUND, "unknown operation for this receipt")));
        }
        RequestRepository.ReceiptSave saved = requests.saveReceipt(receipt.packetId(), operationRef,
            inbox.packetId(), Digests.sha256(Json.write(PacketJson.enc(receipt))), Json.write(PacketJson.enc(receipt)));
        if (saved.conflict()) {
            return CompletableFuture.completedFuture(IntakeAck.rejected(ChannelError.of(
                ChannelErrorCode.REQUEST_CONFLICT,
                "receipt packet id was already used by another operation")));
        }
        if (saved.applied()) {
            return CompletableFuture.completedFuture(IntakeAck.ok());
        }
        // 已落盘但投影未完成（进程在落盘后、投影前退出）：重投时继续投影，而不是直接当作重复忽略。
        applyReceipt(operationRef, inbox, receipt);
        requests.markReceiptApplied(receipt.packetId());
        return CompletableFuture.completedFuture(IntakeAck.ok());
    }

    private void applyReceipt(String operationRef, RequestRepository.InboxRow inbox, ReceiptPacket receipt) {
        switch (receipt.payload()) {
            case ReceiptPayload.Completed completed -> {
                com.netlivecowork.channel.contract.ChannelResponse response =
                    new com.netlivecowork.channel.contract.ChannelResponse.Completed(inbox.requestId(),
                        completed.result());
                // 只有 inbox 真正接受本次迁移时才更新响应与事项，避免“状态守住了、响应仍回退”。
                if (requests.updateInbox(RequestRepository.OperationStatus.COMPLETED,
                    Json.write(PacketJson.enc(response)), operationRef)
                    && completed.result()
                    instanceof com.netlivecowork.channel.contract.OperationResult.InteractionRespond) {
                    RequestRepository.ResponseRow stored = requests.responseByOperation(operationRef);
                    if (stored != null) {
                        requests.updateResponseStatus(stored.responseRef(), "APPLIED");
                    }
                }
            }
            case ReceiptPayload.Rejected rejected -> {
                if (requests.updateInbox(RequestRepository.OperationStatus.REJECTED,
                    Json.write(PacketJson.enc(new com.netlivecowork.channel.contract.ChannelResponse.Rejected(
                        inbox.requestId(), rejected.error()))), operationRef)) {
                    RequestRepository.ResponseRow stored = requests.responseByOperation(operationRef);
                    if (stored != null) {
                        requests.updateResponseStatus(stored.responseRef(), "REJECTED");
                        requests.resetInteractionToPending(stored.interactionRef());
                    }
                }
            }
            case ReceiptPayload.Unknown unknown -> {
                if (requests.updateInbox(RequestRepository.OperationStatus.UNKNOWN,
                    Json.write(PacketJson.enc(new com.netlivecowork.channel.contract.ChannelResponse.Unknown(
                        inbox.requestId(), operationRef, unknown.error()))), operationRef)) {
                    RequestRepository.ResponseRow stored = requests.responseByOperation(operationRef);
                    if (stored != null) {
                        requests.updateResponseStatus(stored.responseRef(), "UNKNOWN");
                    }
                }
            }
            case ReceiptPayload.Accepted ignored ->
                requests.updateInbox(RequestRepository.OperationStatus.DISPATCHED, null, operationRef);
        }
    }

    /** 待关联记录恢复：关联条件具备时补生成规范事件与投递。 */
    public int reprocessPending(int limit) {
        int processed = 0;
        for (EventRepository.EventRow row : events.pendingRelation(limit)) {
            try {
                VerifiedBackendEvent event = PacketJson.decVerifiedEvent(row.eventJson());
                if (normalize(event, row.payloadDigest())) {
                    processed++;
                }
            } catch (RuntimeException e) {
                events.markState(row.resourceRef(), row.sourceEventId(),
                    EventRepository.EventState.PENDING_RELATION, null, null, sanitize(e.getMessage()));
            }
        }
        return processed;
    }

    // ---- 规范化 ----------------------------------------------------------------------

    /** 仅供同包测试核对事件状态与规范包身份。 */
    EventRepository events() {
        return events;
    }

    /** 仅供同包测试：验证已规范化事件不会被二次规范化覆盖。 */
    boolean normalize(VerifiedBackendEvent event, String digest) {
        String targetRef = event.targetRef();
        if (targetRef == null) {
            return false;
        }
        DirectoryRepository.TargetRow target = directory.target(targetRef);
        if (target == null) {
            return false;
        }
        String tenant = target.tenantRef();
        String workspace = target.workspaceRef();
        // 跨层登记（L2 → L3）必须在本层事务提交之后执行：端口是异步契约，且两层不得共享事务。
        List<Runnable> afterCommit = new ArrayList<>();
        Boolean normalized = db.tx(connection -> normalizeInTx(event, target, tenant, workspace, afterCommit, null));
        if (Boolean.TRUE.equals(normalized)) {
            afterCommit.forEach(Runnable::run);
            return true;
        }
        return false;
    }

    private Boolean normalizeInTx(VerifiedBackendEvent event, DirectoryRepository.TargetRow target, String tenant,
                                  String workspace, List<Runnable> afterCommit, HostSubscriptionDeliveries.Basis observed) {
        // 只允许未完成规范化的状态进入：已 PACKETIZED/SKIPPED_STALE/QUARANTINED 的事件不得被
        // 二次规范化覆盖（否则会清空 packetId 并重复投递）。
        EventRepository.EventRow current = events.find(event.source().resourceRef(),
            event.source().sourceEventId());
        if (current == null
            || (current.state() != EventRepository.EventState.PENDING_RELATION
                && current.state() != EventRepository.EventState.PACKETIZING)) {
            return false;
        }
        String targetRef = target.targetRef();
        RequestRepository.InboxRow operation = event.operationRef() == null
            ? null : requests.inboxByOperation(event.operationRef());
        String flowId = operation != null ? operation.flowId() : null;
        String causationId = operation != null ? operation.packetId() : null;
        String taskRef = null;

        String backendTaskId = backendTaskId(event.payload());
        if (backendTaskId != null) {
            RequestRepository.TaskRow task = requests.taskByBackend(targetRef, backendTaskId);
            if (task == null) {
                String resolvedFlow = flowId != null ? flowId : derivedFlow(targetRef, backendTaskId);
                task = requests.ensureTask(tenant, workspace, targetRef, resolvedFlow, event.operationRef(),
                    backendTaskId);
            }
            taskRef = task.taskRef();
            if (flowId == null) {
                flowId = task.flowId();
            }
        }
        if (flowId == null) {
            flowId = derivedFlow(targetRef, event.source().sourceEventId());
        }

        EventPayload payload = buildPayload(event, tenant, workspace, targetRef, flowId, taskRef, afterCommit);
        if (payload == null) {
            return false;
        }
        if (event.type().isTaskEvent() && taskRef != null
            && !updateTask(event.type(), event.payload(), taskRef)) {
            // 晚到旧状态：记录事件但不投递（INV-27）；保留已生成的规范包，不清空包身份。
            events.markState(event.source().resourceRef(), event.source().sourceEventId(),
                EventRepository.EventState.SKIPPED_STALE, current.packetId(), current.packetJson(),
                "stale revision");
            return false;
        }

        // 单 worker 领取规范化：并发（后台泵 + 周期 tick）不得为同一事件生成两个规范包与两份投递（INV-31）。
        if (!events.claimPacketize(event.source().resourceRef(), event.source().sourceEventId())) {
            return false;
        }
        String packetId = Ids.next("event");
        EventPacket packet = new EventPacket("1", packetId, event.type(), clock.instant(),
            causationId == null ? Correlation.of(flowId) : new Correlation(flowId, causationId),
            new PacketContext(tenant, workspace, targetRef, event.operationRef(), taskRef),
            new Producer(Layer.L2, settings.componentRef()), new EventSource(event.source().resourceRef(),
            event.source().sourceEventId()), event.occurredAt(), event.order(), payload);
        events.markState(event.source().resourceRef(), event.source().sourceEventId(),
            EventRepository.EventState.PACKETIZED, packetId, Json.write(PacketJson.enc(packet)), null);
        scheduleDeliveries(packet, target, tenant, workspace, taskRef, observed);
        return true;
    }

    /** Recovery returns the same persisted EventPackets as active delivery, never another UI DTO. */
    List<EventPacket> pendingInteractions(String target) {
        return db.query("""
            SELECT e.packet_json FROM l2_backend_events e JOIN l2_interactions i
              ON json_extract(e.packet_json,'$.payload.interactionRef')=i.interaction_ref
            WHERE json_extract(e.packet_json,'$.type')='interaction.required'
              AND json_extract(e.packet_json,'$.payload.revision')=i.revision AND i.target_ref=? AND i.status IN ('PENDING','SUBMITTING') AND e.state='PACKETIZED'
            ORDER BY e.created_at
            """,r->PacketJson.decEvent(r.getString(1)),target);
    }

    private String derivedFlow(String targetRef, String seed) {
        return "flow-" + Digests.sha256(targetRef + "\u0000" + seed).substring(0, 24);
    }

    private String backendTaskId(AgentEventPayload payload) {
        return switch (payload) {
            case AgentEventPayload.InteractionRequired value -> value.interaction().backendTaskId();
            case AgentEventPayload.TaskStarted value -> value.backendTaskId();
            case AgentEventPayload.TaskProgress value -> value.backendTaskId();
            case AgentEventPayload.TaskWaiting value -> value.backendTaskId();
            case AgentEventPayload.TaskCompleted value -> value.backendTaskId();
            case AgentEventPayload.TaskFailed value -> value.backendTaskId();
            case AgentEventPayload.TaskCancelled value -> value.backendTaskId();
            case AgentEventPayload.TaskArtifact value -> value.backendTaskId();
            default -> null;
        };
    }

    private boolean updateTask(EventType type, AgentEventPayload payload, String taskRef) {
        TaskStatus status = switch (type) {
            case TASK_STARTED, TASK_PROGRESS -> TaskStatus.RUNNING;
            case TASK_WAITING -> TaskStatus.WAITING;
            case TASK_COMPLETED -> TaskStatus.COMPLETED;
            case TASK_FAILED -> TaskStatus.FAILED;
            case TASK_CANCELLED -> TaskStatus.CANCELLED;
            default -> null;
        };
        if (status == null) {
            return true;
        }
        long revision = switch (payload) {
            case AgentEventPayload.TaskStarted value -> value.revision();
            case AgentEventPayload.TaskProgress value -> value.revision();
            case AgentEventPayload.TaskWaiting value -> value.revision();
            case AgentEventPayload.TaskCompleted value -> value.revision();
            case AgentEventPayload.TaskFailed value -> value.revision();
            case AgentEventPayload.TaskCancelled value -> value.revision();
            default -> 0L;
        };
        return requests.updateTaskStatus(taskRef, status, revision);
    }

    private EventPayload buildPayload(VerifiedBackendEvent event, String tenant, String workspace,
                                      String targetRef, String flowId, String taskRef,
                                      List<Runnable> afterCommit) {
        AgentEventPayload payload = event.payload();
        return switch (payload) {
            case AgentEventPayload.MessageReady ready -> {
                List<ContentPart> parts = mapContent(tenant, workspace, targetRef, flowId, taskRef,
                    ready.content());
                String messageRef = requests.ensureMessage(targetRef, flowId, ready.backendMessageId(), taskRef,
                    Json.write(Json.mapper().valueToTree(parts)));
                String replyTo = ready.replyToBackendMessageId() == null ? null
                    : requests.ensureMessage(targetRef, flowId, ready.replyToBackendMessageId(), taskRef, "[]");
                yield new EventPayload.MessageReady(new ChannelMessage(messageRef, parts, replyTo));
            }
            case AgentEventPayload.TaskStarted value -> new EventPayload.TaskStarted(value.revision());
            case AgentEventPayload.TaskProgress value -> new EventPayload.TaskProgress(value.revision(),
                value.summary(), value.progress());
            case AgentEventPayload.TaskWaiting value -> new EventPayload.TaskWaiting(value.revision(),
                value.reason());
            case AgentEventPayload.TaskCompleted value -> new EventPayload.TaskCompleted(value.revision(),
                resolveMessages(targetRef, flowId, taskRef, value.backendResultMessageIds()),
                mapFiles(tenant, workspace, targetRef, value.files(), afterCommit), value.completion());
            case AgentEventPayload.TaskFailed value -> new EventPayload.TaskFailed(value.revision(),
                value.error(), resolveMessages(targetRef, flowId, taskRef, value.backendResultMessageIds()), value.completion());
            case AgentEventPayload.TaskCancelled value -> new EventPayload.TaskCancelled(value.revision(),
                value.reason(), value.completion());
            case AgentEventPayload.TaskArtifact value -> new EventPayload.TaskArtifact(
                mapFiles(tenant, workspace, targetRef, value.files(), afterCommit));
            case AgentEventPayload.InteractionRequired value -> {
                BackendInteraction interaction = value.interaction();
                String promptJson = Json.write(ContentJson.encode(interaction.prompt()));
                RequestRepository.InteractionRow row = requests.ensureInteraction(tenant, workspace, targetRef,
                    taskRef, flowId, interaction.backendInteractionId(), interaction.revision(), promptJson,
                    interaction.expiresAt(), null, null, null);
                // 后端只认自己的事项 ID：映射意图与规范化同事务落库，提交后由泵调用 L3（INV-59、61）。
                registrations.enqueue(RegistrationRepository.KIND_INTERACTION, targetRef, row.interactionRef(),
                    interaction.backendInteractionId(), interaction.revision(), null);
                yield new EventPayload.InteractionRequired(new InteractionRequiredPayload(row.interactionRef(),
                    row.revision(), row.expiresAt(), interaction.prompt()));
            }
            case AgentEventPayload.InteractionResolved value -> {
                RequestRepository.InteractionRow row = resolveInteraction(targetRef, value.ref());
                if (row == null) {
                    yield null;
                }
                requests.finishInteraction(row.interactionRef(), statusFor(value.outcome()), value.ref().revision());
                yield new EventPayload.InteractionResolved(row.interactionRef(), value.ref().revision(),
                    value.outcome());
            }
            case AgentEventPayload.InteractionExpired value -> {
                RequestRepository.InteractionRow row = resolveInteraction(targetRef, value.ref());
                if (row == null) {
                    yield null;
                }
                requests.finishInteraction(row.interactionRef(), "EXPIRED", value.ref().revision());
                yield new EventPayload.InteractionExpired(row.interactionRef(), value.ref().revision(),
                    value.reason());
            }
            case AgentEventPayload.InteractionCancelled value -> {
                RequestRepository.InteractionRow row = resolveInteraction(targetRef, value.ref());
                if (row == null) {
                    yield null;
                }
                requests.finishInteraction(row.interactionRef(), "CANCELLED", value.ref().revision());
                yield new EventPayload.InteractionCancelled(row.interactionRef(), value.ref().revision(),
                    value.reason());
            }
        };
    }

    private String statusFor(EventPayload.Outcome outcome) {
        return switch (outcome) {
            case APPROVED -> "RESOLVED";
            case REJECTED -> "RESOLVED";
            case ANSWERED -> "RESOLVED";
        };
    }

    private RequestRepository.InteractionRow resolveInteraction(String targetRef, BackendInteractionRef ref) {
        RequestRepository.InteractionRow row = requests.interactionByBackend(targetRef, ref.backendInteractionId());
        if (row == null || ref.revision() < row.revision()
            || RequestRepository.isTerminalInteraction(row.status())) {
            // 旧版本或已终态的事项事件：只登记不投递，不复活旧事项。
            return null;
        }
        return row;
    }

    private List<ContentPart> mapContent(String tenant, String workspace, String targetRef, String flowId,
                                         String taskRef, List<NormalizedContentPart> parts) {
        List<ContentPart> mapped = new ArrayList<>();
        for (NormalizedContentPart part : parts) {
            mapped.add(switch (part) {
                case NormalizedContentPart.Text text -> new ContentPart.Text(text.partId(), text.text(),
                    text.format());
                case NormalizedContentPart.Image image -> new ContentPart.Image(image.partId(),
                    directory.ensureFile(tenant, workspace, targetRef, image.file().backendFileId(),
                        image.file().metadata()), image.alt());
                case NormalizedContentPart.FilePart file -> new ContentPart.FilePart(file.partId(),
                    directory.ensureFile(tenant, workspace, targetRef, file.file().backendFileId(),
                        file.file().metadata()));
                case NormalizedContentPart.MessageReference reference -> {
                    String messageRef = requests.ensureMessage(targetRef, flowId,
                        reference.backendMessageId(), taskRef, "[]");
                    List<com.netlivecowork.channel.contract.BasicContentPart> preview = new ArrayList<>();
                    for (NormalizedContentPart.Text text : reference.preview()) {
                        preview.add(new ContentPart.Text(text.partId(), text.text(), text.format()));
                    }
                    yield new ContentPart.MessageReference(reference.partId(), messageRef,
                        new ContentPart.MessageReference.Preview(preview, reference.truncated()));
                }
            });
        }
        return mapped;
    }

    private List<String> mapFiles(String tenant, String workspace, String targetRef,
                                 List<com.netlivecowork.channel.contract.BackendFileGrant> grants,
                                 List<Runnable> afterCommit) {
        List<String> refs = new ArrayList<>();
        for (com.netlivecowork.channel.contract.BackendFileGrant grant : grants) {
            String fileRef = directory.ensureFile(tenant, workspace, targetRef, grant.backendFileId(),
                grant.metadata());
            String payload = Json.write(Json.obj().put("name", grant.metadata().name())
                .put("mediaType", grant.metadata().mediaType()).put("sizeBytes", grant.metadata().sizeBytes()));
            registrations.enqueue(RegistrationRepository.KIND_FILE, targetRef, fileRef, grant.backendFileId(),
                0L, payload);
            refs.add(fileRef);
        }
        return refs;
    }

    private List<String> resolveMessages(String targetRef, String flowId, String taskRef,
                                         List<String> backendMessageIds) {
        List<String> refs = new ArrayList<>();
        for (String backendMessageId : backendMessageIds) {
            refs.add(requests.ensureMessage(targetRef, flowId, backendMessageId, taskRef, "[]"));
        }
        return refs;
    }

    // ---- 投递安排 --------------------------------------------------------------------

    private void scheduleDeliveries(EventPacket packet, DirectoryRepository.TargetRow target, String tenant,
                                    String workspace, String taskRef, HostSubscriptionDeliveries.Basis observed) {
        Map<String, DeliveryPlan> plans = new LinkedHashMap<>();
        String flowId = packet.correlation().flowId();
        for (RequestRepository.ReplyRelation relation : requests.replyRelationsForFlow(flowId)) {
            plans.computeIfAbsent(key(relation.entryRef(), relation.destinationRef(),
                    relation.destinationRevision()),
                ignored -> new DeliveryPlan(relation.entryRef(), relation.destinationRef(),
                    relation.destinationRevision()))
                .relations.add(new DeliveryRepository.RelationRow(null,
                    DeliveryRepository.RelationType.REPLY, relation.relationId(), 1L,
                    relation.authorizedBy(), true));
        }
        List<DeliveryRepository.SubscriptionRow> subscriptions = new ArrayList<>(
            deliveries.subscriptionsForResource("task", taskRef == null ? "" : taskRef));
        if (packet.context().targetRef() != null) {
            subscriptions.addAll(deliveries.subscriptionsForResource("session", packet.context().targetRef()));
        }
        for (DeliveryRepository.SubscriptionRow subscription : subscriptions) {
            if (!subscription.eventTypes().contains(packet.type().name())) {
                continue;
            }
            plans.computeIfAbsent(key(subscription.entryRef(), subscription.destinationRef(),
                    subscription.destinationRevision()),
                ignored -> new DeliveryPlan(subscription.entryRef(), subscription.destinationRef(),
                    subscription.destinationRevision()))
                .relations.add(new DeliveryRepository.RelationRow(null,
                    DeliveryRepository.RelationType.SUBSCRIPTION, subscription.subscriptionRef(),
                    subscription.generation(), subscription.subscriberRef(), true));
        }
        DeliveryPlan observedPlan = null;
        if (observed != null) {
            observedPlan = plans.computeIfAbsent(key(observed.entryRef(), observed.destinationRef(),
                    observed.destinationRevision()), ignored -> new DeliveryPlan(observed.entryRef(),
                    observed.destinationRef(), observed.destinationRevision()));
            observedPlan.relations.add(new DeliveryRepository.RelationRow(null,
                DeliveryRepository.RelationType.SUBSCRIPTION, observed.subscriptionRef(), observed.revision(),
                observed.callerRef(), true));
        }
        for (DeliveryPlan plan : plans.values()) {
            String deliveryId = "delivery-" + Digests.sha256(String.join("\u0000", tenant, workspace,
                plan.entryRef, plan.destinationRef, String.valueOf(plan.destinationRevision),
                packet.packetId())).substring(0, 24);
            boolean currentDestination = deliveries.destinationRevisionCurrent(plan.destinationRef,
                plan.destinationRevision);
            List<ChannelDelivery.Action> actions = currentDestination
                ? actionsFor(packet, plan) : List.of();
            ChannelDelivery delivery = new ChannelDelivery("1", deliveryId, packet,
                new ChannelDelivery.Routing(plan.entryRef, plan.destinationRef, plan.destinationRevision),
                presentation(packet,target,taskRef,plan,plan == observedPlan), actions);
            if (!currentDestination) {
                // 目的地身份版本已变：不转发给新身份，但保留可见的暂停记录（INV-13、INV-39）。
                deliveries.insertDelivery(new DeliveryRepository.DeliveryRow(deliveryId, tenant, workspace,
                    plan.entryRef, plan.destinationRef, plan.destinationRevision, packet.packetId(),
                    Json.write(PacketJson.enc(delivery)), DeliveryRepository.DeliveryStatus.PAUSED, 0, null,
                    null, null, null, ChannelErrorCode.DESTINATION_STALE
                        .error("destination identity version changed; delivery was not sent").message()));
                continue;
            }
            boolean inserted = deliveries.insertDelivery(new DeliveryRepository.DeliveryRow(deliveryId,
                tenant, workspace, plan.entryRef, plan.destinationRef, plan.destinationRevision,
                packet.packetId(), Json.write(PacketJson.enc(delivery)),
                DeliveryRepository.DeliveryStatus.PENDING, 0, null, null, null, null, null));
            for (DeliveryRepository.RelationRow relation : plan.relations) {
                deliveries.addRelation(new DeliveryRepository.RelationRow(deliveryId, relation.type(),
                    relation.relationRef(), relation.generation(), relation.authorizedBy(), true));
            }
            if (plan == observedPlan) hostSubscriptions.remember(deliveryId, observed);
            if (inserted) {
                deliveries.cancelUnbackedDeliveries();
            }
        }
    }

    private ChannelDelivery.Presentation presentation(EventPacket packet,DirectoryRepository.TargetRow target,String taskRef,
                                                       DeliveryPlan plan, boolean sessionSubscription) {
        if(catalog!=null) {
            var row=db.queryOne("SELECT adapter_type FROM l2_entries WHERE entry_ref=?",r->r.getString(1),plan.entryRef);
            var descriptor=row==null?null:catalog.descriptor(row);
            if(descriptor==null || descriptor.capabilities().delivery()==null || descriptor.capabilities().delivery().completionNotice()==com.netlivecowork.channel.contract.adapter.AdapterCapabilities.CompletionNotice.NONE)
                return new ChannelDelivery.Presentation(target.label());
        }
        var nav=db.queryOne("SELECT summary_json FROM l2_navigation_catalog WHERE target_ref=?",r->Json.read(r.getString(1)),target.targetRef());
        String summary=null;
        if(packet.context().operationRef()!=null) {
            var input=requests.inboxByOperation(packet.context().operationRef());
            if(input!=null) {
                var parts=Json.read(input.commandJson()).path("payload").path("message").path("content");
                var text=new StringBuilder();for(var part:parts)if("text".equals(part.path("type").asText()))text.append(part.path("text").asText()).append(' ');
                summary=text.toString().strip();if(summary.length()>100)summary=summary.substring(0,100)+"…";
                if(summary.isBlank())summary=null;
            }
        }
        var source=new ChannelDelivery.Source(nav==null?null:Json.optText(nav.path("workspace"),"sessionWorkspaceRef"),
            nav==null?null:Json.optText(nav.path("workspace"),"label"),target.label(),summary);
        var terminal = packet.type().terminalTaskStatus();
        var notice=terminal!=null && taskRef!=null
            ?new ChannelDelivery.Notification("task_" + terminal.wire(),plan.relations.stream().anyMatch(r->r.type()==DeliveryRepository.RelationType.REPLY)?"request_reply":
                (sessionSubscription ? "session_subscription" : "task_subscription"),new ChannelDelivery.Navigation("open_source_session")):null;
        return new ChannelDelivery.Presentation(target.label(),source,notice);
    }

    private String sourceLabel(DirectoryRepository.TargetRow target, String taskRef) {
        String label = target.label();
        return taskRef == null ? label : label + " · " + taskRef;
    }

    private List<ChannelDelivery.Action> actionsFor(EventPacket packet, DeliveryPlan plan) {
        if (!(packet.payload() instanceof EventPayload.InteractionRequired required)) {
            return List.of();
        }
        InteractionPrompt prompt = required.payload().prompt();
        List<ChannelDelivery.Action> actions = new ArrayList<>();
        for (InteractionPrompt.AllowedResponse allowed : prompt.allowedResponses()) {
            String actionRef = "act-" + Digests.sha256(String.join("\u0000",
                required.payload().interactionRef(), String.valueOf(required.payload().revision()),
                allowed.name(), plan.destinationRef)).substring(0, 24);
            requests.saveAction(new RequestRepository.ActionRow(actionRef,
                required.payload().interactionRef(), required.payload().revision(), allowed.name(),
                plan.entryRef, null, plan.destinationRef, false, required.payload().expiresAt()));
            actions.add(new ChannelDelivery.Action(actionRef, labelFor(allowed)));
        }
        return actions;
    }

    private String labelFor(InteractionPrompt.AllowedResponse allowed) {
        return switch (allowed) {
            case APPROVE -> "批准";
            case REJECT -> "拒绝";
            case ANSWER -> "回答";
        };
    }

    private String key(String entryRef, String destinationRef, long revision) {
        return entryRef + "\u0000" + destinationRef + "\u0000" + revision;
    }

    private static final class DeliveryPlan {
        private final String entryRef;
        private final String destinationRef;
        private final long destinationRevision;
        private final List<DeliveryRepository.RelationRow> relations = new ArrayList<>();

        private DeliveryPlan(String entryRef, String destinationRef, long destinationRevision) {
            this.entryRef = entryRef;
            this.destinationRef = destinationRef;
            this.destinationRevision = destinationRevision;
        }
    }

    private String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "relation is incomplete";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
