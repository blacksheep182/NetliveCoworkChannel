package com.netlivecowork.channel.l2.store;

import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.TaskStatus;
import com.netlivecowork.channel.infra.Db;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** 受理记录、回复关系、任务、消息、回执与人工事项（l2-application.md §2.2、§4）。 */
public final class RequestRepository {

    private final Db db;
    private final Clock clock;

    public RequestRepository(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    // ---- inbox operations ------------------------------------------------------------

    public enum OperationStatus {
        /** 已持久受理，等待派发。 */
        PENDING_DISPATCH,
        /** 已被某个 worker 领取，正在调用后端（不代表后端已受理）。 */
        DISPATCHING,
        /** 后端已确认受理（acceptedBy=agent）。 */
        DISPATCHED,
        COMPLETED,
        REJECTED,
        UNKNOWN,
        /** 缺少精确身份绑定依据，隔离待人工核对（S68），不得按 caller 聚合放行。 */
        QUARANTINED,
        LOCAL_DONE
    }

    public record InboxRow(String dedupeKey, String tenantRef, String workspaceRef, String entryRef,
                           long accountBindingEpoch, String callerRef, String requestId, String requestDigest,
                           String packetId, String operationRef, String flowId, String commandType,
                           String commandJson, OperationStatus status, String responseJson,
                           int dispatchAttempts, Instant nextDispatchAt, Instant createdAt,
                           String identitySubjectKind, String identitySubjectKey,
                           long identityBindingRevision) {
    }

    public InboxRow inboxByDedupe(String dedupeKey) {
        return db.queryOne("SELECT * FROM l2_inbox_operations WHERE dedupe_key=?", this::mapInbox, dedupeKey);
    }

    public InboxRow inboxByOperation(String operationRef) {
        return db.queryOne("SELECT * FROM l2_inbox_operations WHERE operation_ref=?", this::mapInbox,
            operationRef);
    }

    public InboxRow inboxByRequest(String entryRef, long epoch, String callerRef, String requestId) {
        return db.queryOne("""
            SELECT * FROM l2_inbox_operations
            WHERE entry_ref=? AND account_binding_epoch=? AND caller_ref=? AND request_id=?
            """, this::mapInbox, entryRef, epoch, callerRef, requestId);
    }

    public void saveInbox(InboxRow row) {
        db.update("""
            INSERT INTO l2_inbox_operations(dedupe_key, tenant_ref, workspace_ref, entry_ref,
              account_binding_epoch, caller_ref, request_id, request_digest, packet_id, operation_ref,
              flow_id, command_type, command_json, status, response_json, dispatch_attempts,
              next_dispatch_at, created_at, identity_subject_kind, identity_subject_key,
              identity_binding_revision, updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            row.dedupeKey(), row.tenantRef(), row.workspaceRef(), row.entryRef(), row.accountBindingEpoch(),
            row.callerRef(), row.requestId(), row.requestDigest(), row.packetId(), row.operationRef(),
            row.flowId(), row.commandType(), row.commandJson(), row.status().name(), row.responseJson(),
            row.dispatchAttempts(), row.nextDispatchAt() == null ? null : row.nextDispatchAt().toString(),
            row.createdAt().toString(), row.identitySubjectKind(), row.identitySubjectKey(),
            row.identityBindingRevision(), clock.instant().toString());
    }

    public boolean updateInbox(OperationStatus status, String responseJson, String operationRef) {
        // 终态不可回退：晚到的 accepted/unknown 回执不得把已完成的操作改回 DISPATCHED/UNKNOWN。
        return db.update("""
            UPDATE l2_inbox_operations SET status=?, response_json=COALESCE(?, response_json),
              updated_at=?
            WHERE operation_ref=? AND status NOT IN ('COMPLETED','REJECTED','QUARANTINED','LOCAL_DONE')
            """, status.name(), responseJson, clock.instant().toString(), operationRef) == 1;
    }

    /** 派发退避：确定未发送时保留待派发记录，按退避重试直至期限（l3-routing-and-recovery.md §5）。 */
    public void scheduleDispatch(String operationRef, int attempts, Instant nextAttemptAt) {
        db.update("""
            UPDATE l2_inbox_operations SET status='PENDING_DISPATCH', dispatch_attempts=?, next_dispatch_at=?,
              updated_at=? WHERE operation_ref=?
            """, attempts, nextAttemptAt == null ? null : nextAttemptAt.toString(),
            clock.instant().toString(), operationRef);
    }

    public List<InboxRow> pendingDispatch(int limit) {
        return db.query("""
            SELECT * FROM l2_inbox_operations
            WHERE status='PENDING_DISPATCH' AND (next_dispatch_at IS NULL OR next_dispatch_at<=?)
            ORDER BY created_at LIMIT ?
            """, this::mapInbox, clock.instant().toString(), limit);
    }

    /** Unknown writes are reconciled against their original backend key, never resubmitted. */
    public List<InboxRow> unknownOperations(int limit) {
        return db.query("SELECT * FROM l2_inbox_operations WHERE status='UNKNOWN' AND command_type IN ('INPUT_SUBMIT','INTERACTION_RESPOND') "
            + "AND (next_dispatch_at IS NULL OR next_dispatch_at<=?) ORDER BY updated_at LIMIT ?",
            this::mapInbox,clock.instant().toString(),limit);
    }

    public boolean deferReconciliation(String operationRef, Instant nextAttempt) {
        return db.update("UPDATE l2_inbox_operations SET next_dispatch_at=? WHERE operation_ref=? AND status='UNKNOWN' "
            + "AND (next_dispatch_at IS NULL OR next_dispatch_at<=?)",nextAttempt.toString(),operationRef,
            clock.instant().toString()) == 1;
    }

    /** 领取派发：只有一次成功，避免并发重复调用后端（INV-67）。 */
    public boolean claimDispatch(String operationRef) {
        return db.update("""
            UPDATE l2_inbox_operations SET status='DISPATCHING', updated_at=?
            WHERE operation_ref=? AND status='PENDING_DISPATCH'
            """, clock.instant().toString(), operationRef) == 1;
    }

    /** 重启后回收长时间停留在 DISPATCHING 的记录：本机记录不能证明未发送（INV-67）。 */
    public int requeueStaleDispatches(Instant olderThan) {
        return db.update("""
            UPDATE l2_inbox_operations SET status='PENDING_DISPATCH', updated_at=?
            WHERE status='DISPATCHING' AND updated_at<?
            """, clock.instant().toString(), olderThan.toString());
    }

    private InboxRow mapInbox(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InboxRow(Db.str(rs, "dedupe_key"), Db.str(rs, "tenant_ref"), Db.str(rs, "workspace_ref"),
            Db.str(rs, "entry_ref"), rs.getLong("account_binding_epoch"), Db.str(rs, "caller_ref"),
            Db.str(rs, "request_id"), Db.str(rs, "request_digest"), Db.str(rs, "packet_id"),
            Db.str(rs, "operation_ref"), Db.str(rs, "flow_id"), Db.str(rs, "command_type"),
            Db.str(rs, "command_json"), OperationStatus.valueOf(Db.str(rs, "status")),
            Db.str(rs, "response_json"), (int) rs.getLong("dispatch_attempts"),
            Db.instant(rs, "next_dispatch_at"), Db.instant(rs, "created_at"),
            Db.str(rs, "identity_subject_kind"), Db.str(rs, "identity_subject_key"),
            rs.getLong("identity_binding_revision"));
    }

    // ---- reply relations -------------------------------------------------------------

    public record ReplyRelation(String relationId, String flowId, String operationRef, String tenantRef,
                                String workspaceRef, String entryRef, String destinationRef,
                                long destinationRevision, String authorizedBy, String scopeRef, boolean shared,
                                String source, String status) {
    }

    public void saveReplyRelation(ReplyRelation relation) {
        String now = clock.instant().toString();
        db.update("""
            INSERT INTO l2_reply_relations(relation_id, flow_id, operation_ref, tenant_ref, workspace_ref,
              entry_ref, destination_ref, destination_revision, authorized_by, scope_ref, shared, source,
              status, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            relation.relationId(), relation.flowId(), relation.operationRef(), relation.tenantRef(),
            relation.workspaceRef(), relation.entryRef(), relation.destinationRef(),
            relation.destinationRevision(), relation.authorizedBy(), relation.scopeRef(), relation.shared(),
            relation.source(), relation.status(), now, now);
    }

    public List<ReplyRelation> replyRelationsForFlow(String flowId) {
        return db.query("SELECT * FROM l2_reply_relations WHERE flow_id=? AND status='ACTIVE'",
            this::mapRelation, flowId);
    }

    public List<ReplyRelation> replyRelationsForOperation(String operationRef) {
        return db.query("SELECT * FROM l2_reply_relations WHERE operation_ref=? AND status='ACTIVE'",
            this::mapRelation, operationRef);
    }

    public void closeReplyRelationsForFlow(String flowId, String status) {
        db.update("UPDATE l2_reply_relations SET status=?, updated_at=? WHERE flow_id=?",
            status, clock.instant().toString(), flowId);
    }

    private ReplyRelation mapRelation(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReplyRelation(Db.str(rs, "relation_id"), Db.str(rs, "flow_id"),
            Db.str(rs, "operation_ref"), Db.str(rs, "tenant_ref"), Db.str(rs, "workspace_ref"),
            Db.str(rs, "entry_ref"), Db.str(rs, "destination_ref"), rs.getLong("destination_revision"),
            Db.str(rs, "authorized_by"), Db.str(rs, "scope_ref"), Db.bool(rs, "shared"),
            Db.str(rs, "source"), Db.str(rs, "status"));
    }

    // ---- tasks -----------------------------------------------------------------------

    public record TaskRow(String taskRef, String tenantRef, String workspaceRef, String targetRef,
                          String flowId, String operationRef, String backendTaskId, TaskStatus status,
                          long revision) {
    }

    public TaskRow task(String taskRef) {
        return db.queryOne("SELECT * FROM l2_tasks WHERE task_ref=?", this::mapTask, taskRef);
    }

    public TaskRow taskByBackend(String targetRef, String backendTaskId) {
        return db.queryOne("SELECT * FROM l2_tasks WHERE target_ref=? AND backend_task_id=?",
            this::mapTask, targetRef, backendTaskId);
    }

    public List<TaskRow> tasksForFlow(String flowId) {
        return db.query("SELECT * FROM l2_tasks WHERE flow_id=? ORDER BY created_at", this::mapTask, flowId);
    }

    public TaskRow latestTaskForTarget(String targetRef) {
        return db.queryOne("""
            SELECT * FROM l2_tasks WHERE target_ref=? ORDER BY updated_at DESC LIMIT 1
            """, this::mapTask, targetRef);
    }

    /** 可信任务映射登记：同目标 + 后端任务身份唯一（INV-30）。 */
    public TaskRow ensureTask(String tenantRef, String workspaceRef, String targetRef, String flowId,
                              String operationRef, String backendTaskId) {
        return db.tx(connection -> {
            TaskRow existing = taskByBackend(targetRef, backendTaskId);
            if (existing != null) {
                return existing;
            }
            String taskRef = com.netlivecowork.channel.infra.Ids.next("task");
            String now = clock.instant().toString();
            db.update("""
                INSERT INTO l2_tasks(task_ref, tenant_ref, workspace_ref, target_ref, flow_id, operation_ref,
                  backend_task_id, status, revision, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, taskRef, tenantRef, workspaceRef, targetRef, flowId, operationRef, backendTaskId,
                TaskStatus.QUEUED.name(), 0L, now, now);
            return new TaskRow(taskRef, tenantRef, workspaceRef, targetRef, flowId, operationRef,
                backendTaskId, TaskStatus.QUEUED, 0L);
        });
    }

    /** 按业务版本更新；晚到旧状态不覆盖新状态（INV-27）。返回是否被采用。 */
    public boolean updateTaskStatus(String taskRef, TaskStatus status, long revision) {
        // 任务终态不可被更高 revision 的中间态或另一终态改写：完成后到达的 RUNNING/WAITING/FAILED 只登记不采用。
        int rows = db.update("""
            UPDATE l2_tasks SET status=?, revision=?, updated_at=?
            WHERE task_ref=? AND revision<?
              AND status NOT IN ('COMPLETED','FAILED','CANCELLED')
            """, status.name(), revision, clock.instant().toString(), taskRef, revision);
        return rows == 1;
    }

    private TaskRow mapTask(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TaskRow(Db.str(rs, "task_ref"), Db.str(rs, "tenant_ref"), Db.str(rs, "workspace_ref"),
            Db.str(rs, "target_ref"), Db.str(rs, "flow_id"), Db.str(rs, "operation_ref"),
            Db.str(rs, "backend_task_id"), TaskStatus.parse(Db.str(rs, "status")), rs.getLong("revision"));
    }

    // ---- messages --------------------------------------------------------------------

    public record MessageRow(String messageRef, String targetRef, String flowId, String backendMessageId,
                             String taskRef, String contentJson) {
    }

    /** 后端消息身份到内部 messageRef 的稳定映射；同后端消息不因回传路径不同重新编号（INV-28）。 */
    public String ensureMessage(String targetRef, String flowId, String backendMessageId, String taskRef,
                                String contentJson) {
        return db.tx(connection -> {
            MessageRow existing = messageByBackend(targetRef, backendMessageId);
            if (existing != null) {
                if("[]".equals(existing.contentJson()) && !"[]".equals(contentJson))
                    db.update("UPDATE l2_messages SET content=? WHERE message_ref=? AND content='[]'",contentJson,existing.messageRef());
                return existing.messageRef();
            }
            String messageRef = com.netlivecowork.channel.infra.Ids.next("msg");
            db.update("""
                INSERT INTO l2_messages(message_ref, target_ref, flow_id, backend_message_id, task_ref,
                  content, created_at) VALUES(?,?,?,?,?,?,?)
                """, messageRef, targetRef, flowId, backendMessageId, taskRef, contentJson,
                clock.instant().toString());
            return messageRef;
        });
    }

    public MessageRow messageByBackend(String targetRef, String backendMessageId) {
        return db.queryOne("SELECT * FROM l2_messages WHERE target_ref=? AND backend_message_id=?",
            this::mapMessage, targetRef, backendMessageId);
    }

    public MessageRow message(String messageRef) {
        return db.queryOne("SELECT * FROM l2_messages WHERE message_ref=?", this::mapMessage, messageRef);
    }

    private MessageRow mapMessage(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MessageRow(Db.str(rs, "message_ref"), Db.str(rs, "target_ref"), Db.str(rs, "flow_id"),
            Db.str(rs, "backend_message_id"), Db.str(rs, "task_ref"), Db.str(rs, "content"));
    }

    // ---- operation receipts ----------------------------------------------------------

    public record ReceiptRow(String packetId, String operationRef, String commandPacketId, String payloadDigest,
                             String payloadJson, Instant receivedAt) {
    }

    /** 同 packetId 幂等接管；返回 false 表示已接管过（INV-17、INV-22）。 */
    public record ReceiptSave(boolean fresh, boolean applied, boolean conflict) {
    }

    public ReceiptSave saveReceipt(String packetId, String operationRef, String commandPacketId,
                                   String payloadDigest, String payloadJson) {
        StoredReceipt existing = receipt(packetId);
        if (existing != null) {
            boolean same = existing.operationRef().equals(operationRef)
                && existing.commandPacketId().equals(commandPacketId)
                && existing.payloadDigest().equals(payloadDigest);
            // 同一 packetId 指向另一操作/另一命令/另一内容：冲突回执不得应用到当前操作。
            return new ReceiptSave(false, existing.applied(), !same);
        }
        db.update("""
            INSERT INTO l2_operation_receipts(packet_id, operation_ref, command_packet_id,
              payload_digest, payload_json, applied, received_at) VALUES(?,?,?,?,?,0,?)
            """, packetId, operationRef, commandPacketId, payloadDigest, payloadJson,
            clock.instant().toString());
        return new ReceiptSave(true, false, false);
    }

    private record StoredReceipt(String packetId, String operationRef, String commandPacketId,
                                 String payloadDigest, String payloadJson, boolean applied, Instant receivedAt) {
    }

    private StoredReceipt receipt(String packetId) {
        return db.queryOne("SELECT * FROM l2_operation_receipts WHERE packet_id=?", rs -> new StoredReceipt(
            Db.str(rs, "packet_id"), Db.str(rs, "operation_ref"), Db.str(rs, "command_packet_id"),
            Db.str(rs, "payload_digest"), Db.str(rs, "payload_json"), rs.getInt("applied") == 1,
            Instant.parse(Db.str(rs, "received_at"))), packetId);
    }

    /** 回执的业务投影完成标记：落盘后中断的投影在重投时继续执行（不重复落盘）。 */
    public void markReceiptApplied(String packetId) {
        db.update("UPDATE l2_operation_receipts SET applied=1 WHERE packet_id=?", packetId);
    }

    public List<ReceiptRow> receiptsForOperation(String operationRef) {
        return db.query("SELECT * FROM l2_operation_receipts WHERE operation_ref=? ORDER BY received_at",
            rs -> new ReceiptRow(Db.str(rs, "packet_id"), Db.str(rs, "operation_ref"),
                Db.str(rs, "command_packet_id"), Db.str(rs, "payload_digest"), Db.str(rs, "payload_json"),
                Instant.parse(Db.str(rs, "received_at"))), operationRef);
    }

    // ---- interactions ----------------------------------------------------------------

    public record InteractionRow(String interactionRef, String tenantRef, String workspaceRef, String targetRef,
                                 String taskRef, String flowId, String backendInteractionId, String promptJson,
                                 long revision, String status, Instant expiresAt, String scopeRef,
                                 String destinationRef, String originPacketId) {
    }

    public InteractionRow interaction(String interactionRef) {
        return db.queryOne("SELECT * FROM l2_interactions WHERE interaction_ref=?", this::mapInteraction,
            interactionRef);
    }

    public InteractionRow interactionByBackend(String targetRef, String backendInteractionId) {
        return db.queryOne("""
            SELECT * FROM l2_interactions WHERE target_ref=? AND backend_interaction_id=?
            """, this::mapInteraction, targetRef, backendInteractionId);
    }

    /** 新事项版本使旧 actionRef 与草稿失效；同版本重复登记保持原 interactionRef（INV-59）。 */
    public InteractionRow ensureInteraction(String tenantRef, String workspaceRef, String targetRef, String taskRef,
                                            String flowId, String backendInteractionId, long revision,
                                            String promptJson, Instant expiresAt, String scopeRef,
                                            String destinationRef, String originPacketId) {
        return db.tx(connection -> {
            InteractionRow existing = interactionByBackend(targetRef, backendInteractionId);
            String now = clock.instant().toString();
            if (existing == null) {
                String interactionRef = com.netlivecowork.channel.infra.Ids.next("itx");
                db.update("""
                    INSERT INTO l2_interactions(interaction_ref, tenant_ref, workspace_ref, target_ref, task_ref,
                      flow_id, backend_interaction_id, prompt_json, revision, status, expires_at, scope_ref,
                      destination_ref, origin_packet_id, created_at, updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, interactionRef, tenantRef, workspaceRef, targetRef, taskRef, flowId,
                    backendInteractionId, promptJson, revision, "PENDING",
                    expiresAt == null ? null : expiresAt.toString(), scopeRef, destinationRef, originPacketId,
                    now, now);
                closeActions(interactionRef);
                return new InteractionRow(interactionRef, tenantRef, workspaceRef, targetRef, taskRef, flowId,
                    backendInteractionId, promptJson, revision, "PENDING", expiresAt, scopeRef, destinationRef,
                    originPacketId);
            }
            if (revision < existing.revision()) {
                // 旧版本 required：不覆盖、不复活，交由事件层隔离（S 系列事项版本守卫）。
                return existing;
            }
            if (revision == existing.revision()) {
                if (existing.promptJson().equals(promptJson)) {
                    return existing;
                }
                throw new ChannelContractException(ChannelErrorCode.REQUEST_CONFLICT,
                    "interaction revision was already registered with a different prompt");
            }
            if (isTerminalInteraction(existing.status())) {
                throw new ChannelContractException(ChannelErrorCode.REQUEST_CONFLICT,
                    "interaction is already terminal; a new interaction instance is required");
            }
            db.update("""
                UPDATE l2_interactions SET prompt_json=?, revision=?, status='PENDING', expires_at=?,
                  origin_packet_id=COALESCE(?, origin_packet_id), updated_at=? WHERE interaction_ref=?
                """, promptJson, revision, expiresAt == null ? null : expiresAt.toString(), originPacketId,
                now, existing.interactionRef());
            closeActions(existing.interactionRef());
            return new InteractionRow(existing.interactionRef(), existing.tenantRef(), existing.workspaceRef(),
                existing.targetRef(), taskRef == null ? existing.taskRef() : taskRef, flowId,
                backendInteractionId, promptJson, revision, "PENDING", expiresAt,
                scopeRef == null ? existing.scopeRef() : scopeRef,
                destinationRef == null ? existing.destinationRef() : destinationRef,
                originPacketId == null ? existing.originPacketId() : originPacketId);
        });
    }

    public static boolean isTerminalInteraction(String status) {
        return "RESOLVED".equals(status) || "EXPIRED".equals(status) || "CANCELLED".equals(status)
            || "FAILED".equals(status);
    }

    /** 本地 CAS：只协调 Channel 并发；后端仍须处理其他客户端竞争（INV-62）。 */
    public boolean claimInteraction(String interactionRef, long expectedRevision) {
        int rows = db.update("""
            UPDATE l2_interactions SET status='SUBMITTING', updated_at=?
            WHERE interaction_ref=? AND revision=? AND status='PENDING'
            """, clock.instant().toString(), interactionRef, expectedRevision);
        return rows == 1;
    }

    public boolean finishInteraction(String interactionRef, String status, long revision) {
        // 终态不可复活、旧版本不能覆盖新版本：只允许从 PENDING/SUBMITTING 且 revision<= 当前版本迁移。
        boolean updated = db.update("""
            UPDATE l2_interactions SET status=?, revision=MAX(revision, ?), updated_at=?
            WHERE interaction_ref=? AND status IN ('PENDING','SUBMITTING') AND revision<=?
            """, status, revision, clock.instant().toString(), interactionRef, revision) == 1;
        if (updated && !"PENDING".equals(status) && !"SUBMITTING".equals(status)) {
            closeActions(interactionRef);
        }
        return updated;
    }

    public void resetInteractionToPending(String interactionRef) {
        db.update("UPDATE l2_interactions SET status='PENDING', updated_at=? WHERE interaction_ref=?",
            clock.instant().toString(), interactionRef);
    }

    private InteractionRow mapInteraction(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InteractionRow(Db.str(rs, "interaction_ref"), Db.str(rs, "tenant_ref"),
            Db.str(rs, "workspace_ref"), Db.str(rs, "target_ref"), Db.str(rs, "task_ref"),
            Db.str(rs, "flow_id"), Db.str(rs, "backend_interaction_id"), Db.str(rs, "prompt_json"),
            rs.getLong("revision"), Db.str(rs, "status"), Db.instant(rs, "expires_at"),
            Db.str(rs, "scope_ref"), Db.str(rs, "destination_ref"), Db.str(rs, "origin_packet_id"));
    }

    public record ActionRow(String actionRef, String interactionRef, long revision, String responseKind,
                            String entryRef, String scopeRef, String destinationRef, boolean consumed,
                            Instant expiresAt) {
    }

    public void saveAction(ActionRow action) {
        db.update("""
            INSERT OR IGNORE INTO l2_interaction_actions(action_ref, interaction_ref, revision, response_kind,
              entry_ref, scope_ref, destination_ref, consumed, expires_at, created_at)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            """, action.actionRef(), action.interactionRef(), action.revision(), action.responseKind(),
            action.entryRef(), action.scopeRef(), action.destinationRef(), action.consumed(),
            action.expiresAt() == null ? null : action.expiresAt().toString(), clock.instant().toString());
    }

    public ActionRow action(String actionRef) {
        return db.queryOne("SELECT * FROM l2_interaction_actions WHERE action_ref=?", rs -> new ActionRow(
            Db.str(rs, "action_ref"), Db.str(rs, "interaction_ref"), rs.getLong("revision"),
            Db.str(rs, "response_kind"), Db.str(rs, "entry_ref"), Db.str(rs, "scope_ref"),
            Db.str(rs, "destination_ref"), Db.bool(rs, "consumed"), Db.instant(rs, "expires_at")), actionRef);
    }

    /** 原子消耗动作：并发点击只有一次成功（INV-05）。 */
    public boolean consumeAction(String actionRef) {
        return db.update("""
            UPDATE l2_interaction_actions SET consumed=1 WHERE action_ref=? AND consumed=0
            """, actionRef) == 1;
    }

    public void closeActions(String interactionRef) {
        db.update("UPDATE l2_interaction_actions SET consumed=1 WHERE interaction_ref=? AND consumed=0",
            interactionRef);
    }

    public record ResponseRow(String responseRef, String interactionRef, String operationRef,
                              String responderRef, long promptRevision, String responseJson, String status) {
    }

    public void saveResponse(ResponseRow response) {
        String now = clock.instant().toString();
        db.update("""
            INSERT INTO l2_interaction_responses(response_ref, interaction_ref, operation_ref, responder_ref,
              prompt_revision, response_json, status, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?)
            """, response.responseRef(), response.interactionRef(), response.operationRef(),
            response.responderRef(), response.promptRevision(), response.responseJson(), response.status(),
            now, now);
    }

    public ResponseRow response(String responseRef) {
        return db.queryOne("SELECT * FROM l2_interaction_responses WHERE response_ref=?", rs -> new ResponseRow(
            Db.str(rs, "response_ref"), Db.str(rs, "interaction_ref"), Db.str(rs, "operation_ref"),
            Db.str(rs, "responder_ref"), rs.getLong("prompt_revision"), Db.str(rs, "response_json"),
            Db.str(rs, "status")), responseRef);
    }

    public ResponseRow responseByOperation(String operationRef) {
        return db.queryOne("SELECT * FROM l2_interaction_responses WHERE operation_ref=?", rs -> new ResponseRow(
            Db.str(rs, "response_ref"), Db.str(rs, "interaction_ref"), Db.str(rs, "operation_ref"),
            Db.str(rs, "responder_ref"), rs.getLong("prompt_revision"), Db.str(rs, "response_json"),
            Db.str(rs, "status")), operationRef);
    }

    public void updateResponseStatus(String responseRef, String status) {
        db.update("UPDATE l2_interaction_responses SET status=?, updated_at=? WHERE response_ref=?",
            status, clock.instant().toString(), responseRef);
    }

    public List<ResponseRow> responsesForInteraction(String interactionRef) {
        return db.query("SELECT * FROM l2_interaction_responses WHERE interaction_ref=? ORDER BY created_at",
            rs -> new ResponseRow(Db.str(rs, "response_ref"), Db.str(rs, "interaction_ref"),
                Db.str(rs, "operation_ref"), Db.str(rs, "responder_ref"), rs.getLong("prompt_revision"),
                Db.str(rs, "response_json"), Db.str(rs, "status")), interactionRef);
    }
}
