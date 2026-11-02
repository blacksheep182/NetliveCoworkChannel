package com.netlivecowork.channel.l2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netlivecowork.channel.contract.Actor;
import com.netlivecowork.channel.contract.AgentEventPayload;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.ChannelOperation;
import com.netlivecowork.channel.contract.ChannelResponse;
import com.netlivecowork.channel.contract.CommandPacket;
import com.netlivecowork.channel.contract.CommandPayload;
import com.netlivecowork.channel.contract.CommandType;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.Correlation;
import com.netlivecowork.channel.contract.EventSource;
import com.netlivecowork.channel.contract.EventType;
import com.netlivecowork.channel.contract.IngressRequest;
import com.netlivecowork.channel.contract.IngressResolution;
import com.netlivecowork.channel.contract.Layer;
import com.netlivecowork.channel.contract.NormalizedContentPart;
import com.netlivecowork.channel.contract.OperationResult;
import com.netlivecowork.channel.contract.PacketContext;
import com.netlivecowork.channel.contract.Producer;
import com.netlivecowork.channel.contract.ReceiptPacket;
import com.netlivecowork.channel.contract.ReceiptPayload;
import com.netlivecowork.channel.contract.VerifiedBackendEvent;
import com.netlivecowork.channel.contract.VerifiedIngress;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.json.PacketJson;
import com.netlivecowork.channel.l2.store.DeliveryRepository;
import com.netlivecowork.channel.l2.store.DirectoryRepository;
import com.netlivecowork.channel.l2.store.EventRepository;
import com.netlivecowork.channel.l2.store.RequestRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/**
 * 评审复现与 v2.9 验收（S65～S68）：
 * 精确原绑定 + 原版本；禁止 caller 聚合；缺依据隔离；崩溃+停用不重发。
 */
class L2ReviewRegressionTest {

    private void bind(TestModule test, String subjectKey, String callerRef) {
        test.module.management().bindIdentity(TestModule.ENTRY, "USER", subjectKey, callerRef);
    }

    private IngressResolution resolve(TestModule test, String subjectKey) {
        VerifiedIngress input = new VerifiedIngress("req-resolve-" + subjectKey, "acct-1",
            new VerifiedIngress.Subject(VerifiedIngress.SubjectKind.USER, subjectKey),
            new VerifiedIngress.ExternalScope("weixin", subjectKey),
            new VerifiedIngress.ExternalReplyTarget(VerifiedIngress.Audience.PERSONAL, subjectKey,
                Json.obj().put("conversation", subjectKey)),
            "req-resolve-" + subjectKey, null);
        return test.module.ingress(TestModule.ENTRY).resolve(input).toCompletableFuture().join();
    }

    private ChannelResponse submit(TestModule test, IngressResolution resolution, String requestId) {
        return test.module.ingress(TestModule.ENTRY).submit(resolution.admissionRef(),
            new IngressRequest("1", requestId, resolution.identity(), resolution.routing(),
                new ChannelOperation.InputSubmit("target-1", ChannelOperation.Interpretation.LITERAL,
                    List.of(new ContentPart.Text("p1", "你好")), null))).toCompletableFuture().join();
    }

    private void queueWork(TestModule test, String subjectKind, String subjectKey, long revision,
                           String callerRef, String operationRef) {
        CommandPacket command = new CommandPacket("1", "cmd-" + operationRef, CommandType.INPUT_SUBMIT,
            Instant.parse("2026-10-27T00:00:00Z"), Correlation.of("flow-" + operationRef),
            new PacketContext(TestModule.TENANT, TestModule.WORKSPACE, "target-1", operationRef, null),
            new Producer(Layer.L2, "test"), Actor.of(callerRef),
            new CommandPayload.InputSubmit(new com.netlivecowork.channel.contract.ChannelMessage(
                "msg-" + operationRef, List.of(new ContentPart.Text("p1", "排队中的旧工作")), null),
                ChannelOperation.Interpretation.LITERAL));
        test.module.grants().capture(command);
        test.module.requests().saveInbox(new RequestRepository.InboxRow(
            "dedupe-" + operationRef, TestModule.TENANT, TestModule.WORKSPACE, TestModule.ENTRY, 1L, callerRef,
            "req-" + operationRef, "digest-" + operationRef, "cmd-" + operationRef, operationRef,
            "flow-" + operationRef, CommandType.INPUT_SUBMIT.name(), Json.write(PacketJson.enc(command)),
            RequestRepository.OperationStatus.PENDING_DISPATCH, null, 0, null, test.clock.instant(),
            subjectKind, subjectKey, revision));
    }

    @Test
    void crashThenEntryDisabledStillDoesNotResend() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        IngressResolution resolution = resolve(test, "alice");
        ChannelResponse.Accepted accepted = assertInstanceOf(ChannelResponse.Accepted.class,
            submit(test, resolution, "req-1"));
        VerifiedBackendEvent event = new VerifiedBackendEvent("be-1", new EventSource("resource-1", "be-1"),
            accepted.operationRef(), "target-1", null, EventType.MESSAGE_READY,
            Instant.parse("2026-10-27T00:00:01Z"), null,
            new AgentEventPayload.MessageReady(List.of(new NormalizedContentPart.Text("p1", "完成",
                ContentPart.Text.Format.PLAIN)), "bm-1", null));
        assertTrue(test.module.intake().receiveEvent(event).toCompletableFuture().join().accepted());

        DeliveryRepository.DeliveryRow row = test.module.deliveries().claimable(10).get(0);
        assertTrue(test.module.deliveries().markSending(row, "crashed-worker",
            test.clock.instant().plusSeconds(60)));
        var entry = test.module.entries().find(TestModule.ENTRY);
        test.module.management().setEntryEnabled(TestModule.ENTRY, false, entry.revision());
        test.clock.advanceMillis(61_000);
        test.module.tick();

        assertEquals(DeliveryRepository.DeliveryStatus.UNKNOWN,
            test.module.deliveries().delivery(row.deliveryId()).status(),
            "a possibly delivered message must be unknown even when the entry is disabled");

        var disabled = test.module.entries().find(TestModule.ENTRY);
        test.module.management().setEntryEnabled(TestModule.ENTRY, true, disabled.revision());
        test.clock.advanceMillis(61_000);
        test.module.tick();

        assertEquals(DeliveryRepository.DeliveryStatus.UNKNOWN,
            test.module.deliveries().delivery(row.deliveryId()).status());
        assertTrue(test.adapter.deliveries.isEmpty(), "the platform must not receive the message twice");
    }

    @Test
    void oldAdmissionIsRejectedAfterIdentityRebind() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        IngressResolution resolution = resolve(test, "alice");
        assertInstanceOf(ChannelResponse.Accepted.class, submit(test, resolution, "req-1"));

        bind(test, "alice", "caller-bob");

        ChannelResponse reused = submit(test, resolution, "req-reused");
        ChannelResponse.Rejected rejected = assertInstanceOf(ChannelResponse.Rejected.class, reused,
            reused.toString());
        assertEquals(ChannelErrorCode.IDENTITY_UNBOUND.name(), rejected.error().code());
    }

    @Test
    void siblingBindingRevisionDoesNotAffectThisAdmission() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        bind(test, "x", "caller-a");
        bind(test, "y", "caller-a");
        IngressResolution x = resolve(test, "x");

        // 同一外部身份重复绑到同一 caller 是幂等操作，不升版本、不影响任何 admission（SM07 §9.3）
        bind(test, "y", "caller-a");
        assertEquals(1L, test.module.directory().identity(TestModule.ENTRY, 1L, "USER", "y").revision());

        // 同 caller 的另一个绑定被合法改绑到别处，Y 的新版本不能误伤 X（S66）
        bind(test, "y", "caller-b");
        ChannelResponse response = submit(test, x, "req-x");
        assertInstanceOf(ChannelResponse.Accepted.class, response, response.toString());
    }

    @Test
    void rebindingOneBindingDoesNotRevokeTheOtherOnesDestinations() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        bind(test, "x", "caller-a");
        bind(test, "y", "caller-a");
        IngressResolution x = resolve(test, "x");
        assertInstanceOf(ChannelResponse.Accepted.class, submit(test, x, "req-x-1"));
        IngressResolution y = resolve(test, "y");
        String yDestination = y.routing().replyTargetRef();
        long yRevision = test.module.directory().destination(yDestination).destinationRevision();

        // 只重绑 X：Y 的目的地版本不因 caller 相同被连带递增（SM07 §9.3）
        bind(test, "x", "caller-b");

        assertEquals(yRevision, test.module.directory().destination(yDestination).destinationRevision());
        assertInstanceOf(ChannelResponse.Accepted.class, submit(test, y, "req-y"));
    }

    @Test
    void rebindingOneBindingCannotBorrowTheOther() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        bind(test, "x", "caller-a");
        bind(test, "y", "caller-a");
        IngressResolution x = resolve(test, "x");

        // 只把 X 从 A 重绑到 B：X 的旧 admission 失效，Y 保持合法（S65）
        bind(test, "x", "caller-b");

        ChannelResponse reused = submit(test, x, "req-x");
        assertEquals(ChannelErrorCode.IDENTITY_UNBOUND.name(),
            assertInstanceOf(ChannelResponse.Rejected.class, reused).error().code());

        IngressResolution y = resolve(test, "y");
        assertInstanceOf(ChannelResponse.Accepted.class, submit(test, y, "req-y"));
    }

    @Test
    void staleQueuedBindingIsNotDispatched() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        bind(test, "x", "caller-a");
        DirectoryRepository.IdentityBinding first = test.module.directory()
            .identity(TestModule.ENTRY, 1L, "USER", "x");
        // 旧一代工作排队后，绑定 A→B→A（revision 变化，caller 重新存在）
        bind(test, "x", "caller-b");
        bind(test, "x", "caller-a");
        queueWork(test, "USER", "x", first.revision(), "caller-a", "op-stale");

        test.module.tick();

        assertEquals(RequestRepository.OperationStatus.REJECTED,
            test.module.requests().inboxByOperation("op-stale").status());
        assertTrue(test.backend.commands.isEmpty(), "caller re-existence must not revive stale queued work");
    }

    @Test
    void queuedWorkWithoutBindingEvidenceIsQuarantined() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        queueWork(test, null, null, 0L, "caller-a", "op-unknown");

        test.module.tick();

        assertEquals(RequestRepository.OperationStatus.QUARANTINED,
            test.module.requests().inboxByOperation("op-unknown").status());
        assertTrue(test.backend.commands.isEmpty());
    }

    @Test
    void concurrentIdenticalBindingKeepsOneRevision() throws Exception {
        TestModule test = new TestModule();
        test.createEntry();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        ConcurrentLinkedQueue<Long> revisions = new ConcurrentLinkedQueue<>();
        Runnable bind = () -> {
            try {
                start.await();
                revisions.add(test.module.management().bindIdentity(TestModule.ENTRY, "USER", "x", "caller-a"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        };
        Thread first = new Thread(bind, "bind-1");
        Thread second = new Thread(bind, "bind-2");
        first.start();
        second.start();
        start.countDown();
        done.await();

        assertEquals(1L, test.module.directory().identity(TestModule.ENTRY, 1L, "USER", "x").revision(),
            "concurrent identical binds must not bump the revision");
        assertTrue(revisions.stream().allMatch(revision -> revision == 1L), revisions.toString());
    }

    @Test
    void queuedWorkFromOldAccountEpochIsNotDispatched() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        bind(test, "x", "caller-a");
        DirectoryRepository.IdentityBinding binding = test.module.directory()
            .identity(TestModule.ENTRY, 1L, "USER", "x");
        queueWork(test, "USER", "x", binding.revision(), "caller-a", "op-epoch");

        var entry = test.module.entries().find(TestModule.ENTRY);
        test.module.management().rebindAccount(TestModule.ENTRY, entry.revision());
        test.module.tick();

        assertEquals(RequestRepository.OperationStatus.REJECTED,
            test.module.requests().inboxByOperation("op-epoch").status());
        assertTrue(test.backend.commands.isEmpty(), "epoch-1 work must not borrow the epoch-2 binding");
    }

    @Test
    void admissionFromOldAccountEpochIsRejected() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        IngressResolution resolution = resolve(test, "alice");

        var entry = test.module.entries().find(TestModule.ENTRY);
        test.module.management().rebindAccount(TestModule.ENTRY, entry.revision());

        ChannelResponse reused = submit(test, resolution, "req-old-epoch");
        assertEquals(ChannelErrorCode.IDENTITY_UNBOUND.name(),
            assertInstanceOf(ChannelResponse.Rejected.class, reused).error().code());
    }

    @Test
    void rebindingUpdatesBindingAndInvalidatesWorkAtomically() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        bind(test, "x", "caller-a");
        IngressResolution resolution = resolve(test, "x");
        assertInstanceOf(ChannelResponse.Accepted.class, submit(test, resolution, "req-1"));
        String destination = resolution.routing().replyTargetRef();
        long revisionBefore = test.module.directory().destination(destination).destinationRevision();

        bind(test, "x", "caller-b");

        // 撤销与版本更新同时可见：不存在“目的地已失效但绑定还是旧 caller”的窗口
        assertEquals(revisionBefore + 1,
            test.module.directory().destination(destination).destinationRevision());
        assertEquals("caller-b",
            test.module.directory().identity(TestModule.ENTRY, 1L, "USER", "x").callerRef());
        assertEquals(ChannelErrorCode.IDENTITY_UNBOUND.name(),
            assertInstanceOf(ChannelResponse.Rejected.class, submit(test, resolution, "req-2")).error().code());
    }

    @Test
    void packetizedEventIsNotOverwrittenByLateNormalization() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        IngressResolution resolution = resolve(test, "alice");
        ChannelResponse.Accepted accepted = assertInstanceOf(ChannelResponse.Accepted.class,
            submit(test, resolution, "req-1"));
        VerifiedBackendEvent event = new VerifiedBackendEvent("be-1", new EventSource("resource-1", "be-1"),
            accepted.operationRef(), "target-1", null, EventType.MESSAGE_READY,
            Instant.parse("2026-10-27T00:00:01Z"), null,
            new AgentEventPayload.MessageReady(List.of(new NormalizedContentPart.Text("p1", "完成",
                ContentPart.Text.Format.PLAIN)), "bm-1", null));
        assertTrue(test.module.intake().receiveEvent(event).toCompletableFuture().join().accepted());
        EventRepository.EventRow packetized = test.module.intake().events()
            .find("resource-1", "be-1");
        assertEquals(EventRepository.EventState.PACKETIZED, packetized.state());

        // 晚到的二次规范化不得清掉已生成的规范包（探针 4）
        assertFalse(test.module.intake().normalize(event, packetized.payloadDigest()));

        EventRepository.EventRow after = test.module.intake().events().find("resource-1", "be-1");
        assertEquals(EventRepository.EventState.PACKETIZED, after.state());
        assertEquals(packetized.packetId(), after.packetId());
        assertEquals(packetized.packetJson(), after.packetJson());
        assertEquals(1, test.module.deliveries().list(null, 10).size());
    }

    private ReceiptPacket receipt(String operationRef, String commandPacketId, ReceiptPayload payload) {
        String packetId = "rcpt-" + operationRef;
        return new ReceiptPacket("1", packetId, Instant.parse("2026-10-27T00:00:02Z"),
            new Correlation("flow-" + operationRef, commandPacketId),
            new PacketContext(TestModule.TENANT, TestModule.WORKSPACE, "target-1", operationRef, null),
            new Producer(Layer.L3, "test"), payload);
    }

    @Test
    void receiptPersistedButNotAppliedIsRecoveredOnRetry() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        queueWork(test, "USER", "x", 1L, "caller-a", "op-receipt");
        String operationRef = "op-receipt";
        RequestRepository.InboxRow inbox = test.module.requests().inboxByOperation(operationRef);
        ReceiptPacket completed = receipt(operationRef, inbox.packetId(),
            new ReceiptPayload.Completed(new OperationResult.InputSubmit("task-1")));

        // 模拟“回执已落盘、投影前中断”：先只落盘，再重投
        test.module.requests().saveReceipt(completed.packetId(), operationRef, inbox.packetId(),
            com.netlivecowork.channel.infra.Digests.sha256(Json.write(PacketJson.enc(completed))),
            Json.write(PacketJson.enc(completed)));
        assertTrue(test.module.requests().inboxByOperation(operationRef).status()
            != RequestRepository.OperationStatus.COMPLETED);

        assertTrue(test.module.intake().receiveReceipt(completed).toCompletableFuture().join().accepted());

        assertEquals(RequestRepository.OperationStatus.COMPLETED,
            test.module.requests().inboxByOperation(operationRef).status(),
            "a retried receipt must finish the interrupted projection");
    }

    @Test
    void lateReceiptDoesNotRegressCompletedOperation() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        queueWork(test, "USER", "x", 1L, "caller-a", "op-late");
        String operationRef = "op-late";
        test.module.requests().updateInbox(RequestRepository.OperationStatus.COMPLETED, null, operationRef);

        ReceiptPacket late = receipt(operationRef,
            test.module.requests().inboxByOperation(operationRef).packetId(),
            new ReceiptPayload.Accepted(ReceiptPayload.AcceptedBy.AGENT));
        test.module.intake().receiveReceipt(late).toCompletableFuture().join();

        assertEquals(RequestRepository.OperationStatus.COMPLETED,
            test.module.requests().inboxByOperation(operationRef).status(),
            "a late receipt must not move a completed operation backwards");
    }

    @Test
    void taskTerminalStateDoesNotRegressOnHigherRevision() {
        TestModule test = new TestModule();
        test.createEntry();
        RequestRepository.TaskRow task = test.module.requests().ensureTask(TestModule.TENANT,
            TestModule.WORKSPACE, "target-1", "flow-1", "op-1", "backend-task-1");
        assertTrue(test.module.requests().updateTaskStatus(task.taskRef(),
            com.netlivecowork.channel.contract.TaskStatus.RUNNING, 1L));
        assertTrue(test.module.requests().updateTaskStatus(task.taskRef(),
            com.netlivecowork.channel.contract.TaskStatus.COMPLETED, 2L));

        assertFalse(test.module.requests().updateTaskStatus(task.taskRef(),
            com.netlivecowork.channel.contract.TaskStatus.RUNNING, 3L));
        assertFalse(test.module.requests().updateTaskStatus(task.taskRef(),
            com.netlivecowork.channel.contract.TaskStatus.FAILED, 4L),
            "one terminal state must not be exchanged for another");
        assertEquals(com.netlivecowork.channel.contract.TaskStatus.COMPLETED,
            test.module.requests().task(task.taskRef()).status());
    }

    @Test
    void conflictingReceiptIsRejectedForAnotherOperation() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        queueWork(test, "USER", "x", 1L, "caller-a", "op-first");
        queueWork(test, "USER", "x", 1L, "caller-a", "op-second");

        RequestRepository.InboxRow first = test.module.requests().inboxByOperation("op-first");
        ReceiptPacket original = receipt("op-first", first.packetId(),
            new ReceiptPayload.Completed(new OperationResult.InputSubmit("task-1")));
        test.module.requests().saveReceipt(original.packetId(), "op-first", first.packetId(), "digest",
            Json.write(PacketJson.enc(original)));

        // 同一 packetId 指向另一操作：不得把这条回执应用到 op-second
        RequestRepository.InboxRow second = test.module.requests().inboxByOperation("op-second");
        ReceiptPacket forged = receipt("op-second", second.packetId(),
            new ReceiptPayload.Rejected(ChannelErrorCode.PERMISSION_DENIED.error("not allowed")));
        ReceiptPacket conflicting = new ReceiptPacket(forged.version(), original.packetId(),
            forged.createdAt(), forged.correlation(), forged.context(), forged.producer(), forged.payload());

        assertFalse(test.module.intake().receiveReceipt(conflicting).toCompletableFuture().join().accepted());
        assertEquals(RequestRepository.OperationStatus.PENDING_DISPATCH,
            test.module.requests().inboxByOperation("op-second").status(),
            "a receipt bound to another operation must not be applied");
    }

    @Test
    void accountRebindPausesOldEpochDeliveries() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        IngressResolution resolution = resolve(test, "alice");
        ChannelResponse.Accepted accepted = assertInstanceOf(ChannelResponse.Accepted.class,
            submit(test, resolution, "req-1"));
        VerifiedBackendEvent event = new VerifiedBackendEvent("be-1", new EventSource("resource-1", "be-1"),
            accepted.operationRef(), "target-1", null, EventType.MESSAGE_READY,
            Instant.parse("2026-10-27T00:00:01Z"), null,
            new AgentEventPayload.MessageReady(List.of(new NormalizedContentPart.Text("p1", "完成",
                ContentPart.Text.Format.PLAIN)), "bm-1", null));
        assertTrue(test.module.intake().receiveEvent(event).toCompletableFuture().join().accepted());

        var entry = test.module.entries().find(TestModule.ENTRY);
        test.module.management().rebindAccount(TestModule.ENTRY, entry.revision());
        test.module.tick();

        assertEquals(DeliveryRepository.DeliveryStatus.PAUSED,
            test.module.deliveries().list(null, 10).get(0).status());
        assertTrue(test.adapter.deliveries.isEmpty(),
            "old-epoch destinations must not send through the new account binding");
    }

    @Test
    void replacementAttemptGenerationNeverCollides() {
        TestModule test = new TestModule();
        test.createEntry();
        var entries = test.module.entries();
        long firstAttempt = entries.bumpAttemptGeneration(TestModule.ENTRY);
        long reconnectAttempt = entries.bumpAttemptGeneration(TestModule.ENTRY);
        long generation = entries.bumpGeneration(TestModule.ENTRY);
        long replacementAttempt = entries.bumpAttemptGeneration(TestModule.ENTRY);

        // 替换实例后尝试代次继续持久递增，不会与旧实例重号（旧回调不能借同号混入）
        assertTrue(replacementAttempt > reconnectAttempt);
        assertEquals(generation, entries.runtime(TestModule.ENTRY).generation());
        assertEquals(replacementAttempt, entries.runtime(TestModule.ENTRY).attemptGeneration());
    }

    @Test
    void pendingReadQueriesUseTheQueryPort() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        bind(test, "x", "caller-a");
        queueRead(test, "op-get", CommandType.SESSION_GET, null, new CommandPayload.SessionGet(),
            null);
        queueRead(test, "op-task-get", CommandType.TASK_GET, null, new CommandPayload.TaskGet(),
            "task-1");

        test.module.tick();

        assertEquals(RequestRepository.OperationStatus.COMPLETED,
            test.module.requests().inboxByOperation("op-get").status());
        assertEquals(RequestRepository.OperationStatus.COMPLETED,
            test.module.requests().inboxByOperation("op-task-get").status());
        assertEquals(0, test.backend.submitCalls.get(), "read commands must not use the write port");
        assertEquals(2, test.backend.queryCalls.get(), "each pending read must use the query port once");
    }

    private void queueRead(TestModule test, String operationRef, CommandType type, String targetRef,
                           CommandPayload payload, String taskRef) {
        CommandPacket command = new CommandPacket("1", "cmd-" + operationRef, type,
            Instant.parse("2026-10-27T00:00:00Z"), Correlation.of("flow-" + operationRef),
            new PacketContext(TestModule.TENANT, TestModule.WORKSPACE, "target-1", operationRef, taskRef),
            new Producer(Layer.L2, "test"), Actor.of("caller-a"), payload);
        test.module.grants().capture(command);
        test.module.requests().saveInbox(new RequestRepository.InboxRow(
            "dedupe-" + operationRef, TestModule.TENANT, TestModule.WORKSPACE, TestModule.ENTRY, 1L, "caller-a",
            "req-" + operationRef, "digest-" + operationRef, "cmd-" + operationRef, operationRef,
            "flow-" + operationRef, type.name(), Json.write(PacketJson.enc(command)),
            RequestRepository.OperationStatus.PENDING_DISPATCH, null, 0, null, test.clock.instant(),
            "USER", "x", 1L));
    }

    @Test
    void interactionGuardsRejectStaleAndTerminalUpdates() {
        TestModule test = new TestModule();
        test.createEntry();
        var row = test.module.requests().ensureInteraction(TestModule.TENANT, TestModule.WORKSPACE,
            "target-1", null, "flow-1", "backend-itx-1", 1L, "prompt-1", null, null, null, null);

        // 同版本同事实幂等
        var same = test.module.requests().ensureInteraction(TestModule.TENANT, TestModule.WORKSPACE,
            "target-1", null, "flow-1", "backend-itx-1", 1L, "prompt-1", null, null, null, null);
        assertEquals(row.interactionRef(), same.interactionRef());
        // 同版本异事实冲突
        assertThrows(com.netlivecowork.channel.contract.ChannelContractException.class,
            () -> test.module.requests().ensureInteraction(TestModule.TENANT, TestModule.WORKSPACE,
                "target-1", null, "flow-1", "backend-itx-1", 1L, "prompt-x", null, null, null, null));
        // 终态不复活
        assertTrue(test.module.requests().finishInteraction(row.interactionRef(), "RESOLVED", 1L));
        assertThrows(com.netlivecowork.channel.contract.ChannelContractException.class,
            () -> test.module.requests().ensureInteraction(TestModule.TENANT, TestModule.WORKSPACE,
                "target-1", null, "flow-1", "backend-itx-1", 2L, "prompt-2", null, null, null, null));
        // 旧取消不能关闭新提示
        assertFalse(test.module.requests().finishInteraction(row.interactionRef(), "CANCELLED", 0L));
        assertEquals("RESOLVED", test.module.requests().interaction(row.interactionRef()).status());
    }

    @Test
    void replayedBackendEventProducesOnlyOneDelivery() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        IngressResolution resolution = resolve(test, "alice");
        ChannelResponse.Accepted accepted = assertInstanceOf(ChannelResponse.Accepted.class,
            submit(test, resolution, "req-1"));
        VerifiedBackendEvent event = new VerifiedBackendEvent("be-1", new EventSource("resource-1", "be-1"),
            accepted.operationRef(), "target-1", null, EventType.MESSAGE_READY,
            Instant.parse("2026-10-27T00:00:01Z"), null,
            new AgentEventPayload.MessageReady(List.of(new NormalizedContentPart.Text("p1", "完成",
                ContentPart.Text.Format.PLAIN)), "bm-1", null));

        assertTrue(test.module.intake().receiveEvent(event).toCompletableFuture().join().accepted());
        test.module.intake().receiveEvent(event).toCompletableFuture().join();
        test.module.tick();

        assertEquals(1, test.module.deliveries().list(null, 10).size(),
            "a replayed backend event must not create a second delivery");
        assertEquals(1, test.adapter.deliveries.size(), "the platform must receive it once");
    }

    @Test
    void unknownDeliveryCanBeReconciledAsAcceptedWithoutResending() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        IngressResolution resolution = resolve(test, "alice");
        ChannelResponse.Accepted accepted = assertInstanceOf(ChannelResponse.Accepted.class,
            submit(test, resolution, "req-1"));
        VerifiedBackendEvent event = new VerifiedBackendEvent("be-1", new EventSource("resource-1", "be-1"),
            accepted.operationRef(), "target-1", null, EventType.MESSAGE_READY,
            Instant.parse("2026-10-27T00:00:01Z"), null,
            new AgentEventPayload.MessageReady(List.of(new NormalizedContentPart.Text("p1", "完成",
                ContentPart.Text.Format.PLAIN)), "bm-1", null));
        assertTrue(test.module.intake().receiveEvent(event).toCompletableFuture().join().accepted());

        DeliveryRepository.DeliveryRow row = test.module.deliveries().claimable(10).get(0);
        assertTrue(test.module.deliveries().markSending(row, "crashed-worker",
            test.clock.instant().plusSeconds(60)));
        test.clock.advanceMillis(61_000);
        test.module.tick();
        assertEquals(DeliveryRepository.DeliveryStatus.UNKNOWN,
            test.module.deliveries().delivery(row.deliveryId()).status());

        // UNKNOWN 不是不可更新的终态：平台确认后可按依据核对为 accepted（S55～S57）
        DeliveryRepository.DeliveryRow reconciled = test.module.management().reconcileDelivery(
            row.deliveryId(), true, "platform-receipt-1", "operator confirmed on platform");
        assertEquals(DeliveryRepository.DeliveryStatus.ACCEPTED, reconciled.status());

        test.clock.advanceMillis(61_000);
        test.module.tick();
        assertTrue(test.adapter.deliveries.isEmpty(), "reconciliation must never trigger a new send");
    }
}
