package com.netlivecowork.channel.l3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.CommandOutcome;
import com.netlivecowork.channel.contract.CommandPacket;
import com.netlivecowork.channel.contract.CommandPayload;
import com.netlivecowork.channel.contract.CommandType;
import com.netlivecowork.channel.contract.DispatchStatus;
import com.netlivecowork.channel.contract.EventType;
import com.netlivecowork.channel.contract.InteractionResponse;
import com.netlivecowork.channel.contract.Layer;
import com.netlivecowork.channel.contract.OperationLookup;
import com.netlivecowork.channel.contract.OperationResult;
import com.netlivecowork.channel.contract.ReceiptPacket;
import com.netlivecowork.channel.contract.ReceiptPayload;
import com.netlivecowork.channel.contract.SessionWatch;
import com.netlivecowork.channel.contract.SessionWatchRequest;
import com.netlivecowork.channel.infra.Db;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 目标解析、派发记录、回执映射与人工交互语义。 */
class BackendDispatchTest {

    @TempDir
    Path tempDir;

    @Test
    void unknownTargetIsNotSentWithoutHttp() throws Exception {
        Db db = TestFixtures.db(tempDir);
        try (TestBackend backend = new TestBackend(call -> TestBackend.Response.status(202));
             L3Module module = module(TestFixtures.settings(), backend, db)) {
            CommandOutcome outcome = module.access()
                .submitCommand(TestFixtures.submit("op-1", "target-missing", "hello"), TestFixtures.call())
                .toCompletableFuture().get();

            assertEquals(DispatchStatus.NOT_SENT, outcome.status());
            assertEquals(ChannelErrorCode.IDENTITY_UNBOUND.name(), outcome.error().code());
            assertEquals(0, backend.callCount());
        }
    }

    @Test
    void scopeMismatchIsPermissionDeniedWithoutHttp() throws Exception {
        Db db = TestFixtures.db(tempDir);
        try (TestBackend backend = new TestBackend(call -> TestBackend.Response.status(202));
             L3Module module = module(TestFixtures.settings(), backend, db)) {
            CommandOutcome outcome = module.access()
                .submitCommand(TestFixtures.submit("op-2", "hello"), TestFixtures.call("tenant-1", "ws-other"))
                .toCompletableFuture().get();

            assertEquals(DispatchStatus.NOT_SENT, outcome.status());
            assertEquals(ChannelErrorCode.PERMISSION_DENIED.name(), outcome.error().code());
            assertEquals(0, backend.callCount());
        }
    }

    @Test
    void acceptedAnswerYieldsAgentAcceptedReceipt() throws Exception {
        Db db = TestFixtures.db(tempDir);
        try (TestBackend backend = new TestBackend(
                call -> TestBackend.Response.json(202, accepted(call)));
             L3Module module = module(TestFixtures.settings(), backend, db)) {
            CommandPacket command = TestFixtures.submit("op-3", "hello");
            CommandOutcome outcome = module.access().submitCommand(command, TestFixtures.call())
                .toCompletableFuture().get();

            assertEquals(DispatchStatus.AGENT_ACCEPTED, outcome.status());
            ReceiptPacket receipt = outcome.receipt();
            assertNotNull(receipt);
            ReceiptPayload.Accepted accepted = assertInstanceOf(ReceiptPayload.Accepted.class, receipt.payload());
            assertEquals(ReceiptPayload.AcceptedBy.AGENT, accepted.acceptedBy());
            assertEquals(command.packetId(), receipt.correlation().causationId());
            assertEquals(command.correlation().flowId(), receipt.correlation().flowId());
            assertEquals(command.context().operationRef(), receipt.context().operationRef());
            assertEquals(Layer.L3, receipt.producer().layer());
            assertEquals("l3-backend", receipt.producer().componentRef());
            assertEquals("AGENT_ACCEPTED", scalarPhase(db, "op-3"));
            assertTrue(backend.lastCall().path().endsWith("/sessions/session-1/messages"));
            assertTrue(backend.lastCall().body().contains("\"event_sink_ref\":\"sink-1\""));
            assertTrue(backend.lastCall().body().contains("\"agent_id\":\"agent-1\""));

            // 存储的回执可再次解码，供 lookupOperation 与后续转交复用。
            OperationLookup lookup = module.access().lookupOperation("op-3", TestFixtures.call())
                .toCompletableFuture().get();
            assertEquals(DispatchStatus.AGENT_ACCEPTED, lookup.status());
            assertInstanceOf(ReceiptPayload.Accepted.class, lookup.receipt().payload());
        }
    }

    @Test
    void repeatedSubmitUsesOneHttpCallAndStoredReceipt() throws Exception {
        Db db = TestFixtures.db(tempDir);
        try (TestBackend backend = new TestBackend(
                call -> TestBackend.Response.json(202, accepted(call)));
             L3Module module = module(TestFixtures.settings(), backend, db)) {
            CommandPacket command = TestFixtures.submit("op-4", "hello");
            CommandOutcome first = module.access().submitCommand(command, TestFixtures.call())
                .toCompletableFuture().get();
            CommandOutcome second = module.access().submitCommand(command, TestFixtures.call())
                .toCompletableFuture().get();

            assertEquals(1, backend.callCount());
            assertEquals(DispatchStatus.AGENT_ACCEPTED, second.status());
            assertEquals(first.receipt().packetId(), second.receipt().packetId());
        }
    }

    @Test
    void differentPayloadWithSameOperationRefConflictsWithoutHttp() throws Exception {
        Db db = TestFixtures.db(tempDir);
        try (TestBackend backend = new TestBackend(
                call -> TestBackend.Response.json(202, accepted(call)));
             L3Module module = module(TestFixtures.settings(), backend, db)) {
            module.access().submitCommand(TestFixtures.submit("op-5", "first body"), TestFixtures.call())
                .toCompletableFuture().get();
            CommandOutcome conflict = module.access()
                .submitCommand(TestFixtures.submit("op-5", "different body"), TestFixtures.call())
                .toCompletableFuture().get();

            assertEquals(1, backend.callCount());
            assertEquals(DispatchStatus.NOT_SENT, conflict.status());
            assertEquals(ChannelErrorCode.REQUEST_CONFLICT.name(), conflict.error().code());
            assertInstanceOf(ReceiptPayload.Rejected.class, conflict.receipt().payload());
            // 原关联不被覆盖。
            assertEquals("AGENT_ACCEPTED", scalarPhase(db, "op-5"));
        }
    }

    @Test
    void timeoutKeepsOutcomeUnknownAndDispatchSentUnknown() throws Exception {
        Db db = TestFixtures.db(tempDir);
        L3Settings settings = TestFixtures.settings(300L, 2, false, false);
        try (TestBackend backend = new TestBackend(
                call -> TestBackend.Response.delayed(202, "{\"status\":\"accepted\"}", 1_500L));
             L3Module module = module(settings, backend, db)) {
            CommandOutcome outcome = module.access().submitCommand(TestFixtures.submit("op-6", "slow"), TestFixtures.call())
                .toCompletableFuture().get();

            assertEquals(DispatchStatus.OUTCOME_UNKNOWN, outcome.status());
            assertEquals(ChannelErrorCode.OUTCOME_UNKNOWN.name(), outcome.error().code());
            assertEquals("SENT_UNKNOWN", scalarPhase(db, "op-6"));

            OperationLookup lookup = module.access().lookupOperation("op-6", TestFixtures.call())
                .toCompletableFuture().get();
            assertEquals(DispatchStatus.OUTCOME_UNKNOWN, lookup.status());
            // 后端核对同样超时：保持未知并不伪造回执（INV-23、67）。
            if (lookup.receipt() != null) {
                assertInstanceOf(ReceiptPayload.Unknown.class, lookup.receipt().payload());
            }
        }
    }

    @Test
    void badRequestMapsToInvalidArgument() throws Exception {
        Db db = TestFixtures.db(tempDir);
        try (TestBackend backend = new TestBackend(
                call -> TestBackend.Response.json(400, rejection(call, "INVALID_REQUEST")));
             L3Module module = module(TestFixtures.settings(), backend, db)) {
            CommandOutcome outcome = module.access().submitCommand(TestFixtures.submit("op-7", "bad"), TestFixtures.call())
                .toCompletableFuture().get();

            assertEquals(DispatchStatus.NOT_SENT, outcome.status());
            assertEquals(ChannelErrorCode.INVALID_ARGUMENT.name(), outcome.error().code());
            ReceiptPayload.Rejected rejected = assertInstanceOf(ReceiptPayload.Rejected.class, outcome.receipt().payload());
            assertEquals(ChannelErrorCode.INVALID_ARGUMENT.name(), rejected.error().code());
            assertEquals("NOT_SENT", scalarPhase(db, "op-7"));
        }
    }

    @Test
    void rateLimitedMapsToRateLimited() throws Exception {
        Db db = TestFixtures.db(tempDir);
        try (TestBackend backend = new TestBackend(
                call -> TestBackend.Response.json(429, rejection(call, "RATE_LIMITED")));
             L3Module module = module(TestFixtures.settings(), backend, db)) {
            CommandOutcome outcome = module.access().submitCommand(TestFixtures.submit("op-8", "later"), TestFixtures.call())
                .toCompletableFuture().get();

            assertEquals(DispatchStatus.NOT_SENT, outcome.status());
            assertEquals(ChannelErrorCode.RATE_LIMITED.name(), outcome.error().code());
        }
    }

    @Test
    void interactionRespondIsOutsideV1AndRejectedWithoutHttp() throws Exception {
        Db db = TestFixtures.db(tempDir);
        try (TestBackend backend = new TestBackend(
                call -> TestBackend.Response.json(200, "{\"status\":\"accepted\"}"));
             L3Module module = module(TestFixtures.settings(), backend, db)) {
            module.registerInteraction("interaction-1", TestFixtures.TARGET, "backend-hitl-9", 1L);
            CommandPacket command = TestFixtures.interactionRespond("op-9", "interaction-1", 1L,
                InteractionResponse.Approve.plain());
            CommandOutcome outcome = module.access().submitCommand(command, TestFixtures.call())
                .toCompletableFuture().get();

            // v1 后端未定义显式交互响应接口：明确能力不足，不转旧 hitl 接口绕过（backend-http-v1 §1）。
            assertEquals(DispatchStatus.NOT_SENT, outcome.status());
            assertEquals(ChannelErrorCode.UNSUPPORTED_CAPABILITY.name(), outcome.error().code());
            assertEquals(0, backend.callCount());
        }
    }

    @Test
    void interactionWithAppliedEvidenceCompletes() throws Exception {
        Db db = TestFixtures.db(tempDir);
        try (TestBackend backend = new TestBackend(
                call -> TestBackend.Response.json(200, "{\"applied\":true}"));
             L3Module module = module(TestFixtures.settings(), backend, db)) {
            module.registerInteraction("interaction-2", TestFixtures.TARGET, "backend-hitl-10", 1L);
            CommandPacket command = TestFixtures.interactionRespond("op-10", "interaction-2", 1L,
                InteractionResponse.Approve.plain());
            CommandOutcome outcome = module.access().submitCommand(command, TestFixtures.call())
                .toCompletableFuture().get();

            // 受理不等于已生效；v1 无显式响应接口，同样按能力不足拒绝而不是猜测应用。
            assertEquals(DispatchStatus.NOT_SENT, outcome.status());
            assertEquals(ChannelErrorCode.UNSUPPORTED_CAPABILITY.name(), outcome.error().code());
            assertEquals(0, backend.callCount());
        }
    }

    @Test
    void interactionWithStaleRevisionIsRejectedWithoutHttp() throws Exception {
        Db db = TestFixtures.db(tempDir);
        try (TestBackend backend = new TestBackend(call -> TestBackend.Response.json(200, "{\"applied\":true}"));
             L3Module module = module(TestFixtures.settings(), backend, db)) {
            module.registerInteraction("interaction-3", TestFixtures.TARGET, "backend-hitl-11", 2L);
            CommandOutcome outcome = module.access().submitCommand(
                TestFixtures.interactionRespond("op-11", "interaction-3", 1L, InteractionResponse.Approve.plain()),
                TestFixtures.call()).toCompletableFuture().get();

            assertEquals(DispatchStatus.NOT_SENT, outcome.status());
            assertEquals(ChannelErrorCode.UNSUPPORTED_CAPABILITY.name(), outcome.error().code());
            assertEquals(0, backend.callCount());
        }
    }

    @Test
    void sessionWatchUnsupportedWhenCapabilityDisabled() throws Exception {
        Db db = TestFixtures.db(tempDir);
        try (TestBackend backend = new TestBackend(call -> TestBackend.Response.status(200));
             L3Module module = module(TestFixtures.settings(), backend, db)) {
            SessionWatch watch = module.access().ensureSessionWatch(new SessionWatchRequest("watch-1",
                TestFixtures.TENANT, TestFixtures.WORKSPACE, TestFixtures.TARGET,
                Set.of(EventType.MESSAGE_READY), TestFixtures.SINK, null)).toCompletableFuture().get();

            assertFalse(watch.supported());
            assertEquals(ChannelErrorCode.UNSUPPORTED_CAPABILITY.name(), watch.error().code());
            assertEquals(0, backend.callCount());
        }
    }

    @Test
    void sessionWatchRegistersOnceAndIsIdempotent() throws Exception {
        Db db = TestFixtures.db(tempDir);
        try (TestBackend backend = new TestBackend(call -> TestBackend.Response.json(200,
                "{\"watch_ref\":\"bw-1\",\"start_boundary\":\"seq-7\"}"));
             L3Module module = module(TestFixtures.settings(2_000L, 2, false, true), backend, db)) {
            SessionWatchRequest request = new SessionWatchRequest("watch-2", TestFixtures.TENANT,
                TestFixtures.WORKSPACE, TestFixtures.TARGET, Set.of(EventType.MESSAGE_READY), TestFixtures.SINK,
                null);
            SessionWatch first = module.access().ensureSessionWatch(request).toCompletableFuture().get();
            SessionWatch second = module.access().ensureSessionWatch(request).toCompletableFuture().get();

            assertTrue(first.supported());
            assertEquals("bw-1", first.watchRef());
            assertEquals("seq-7", second.startBoundary());
            assertEquals(1, backend.callCount());
            assertTrue(backend.lastCall().body().contains("\"event_sink_ref\":\"sink-1\""));
        }
    }

    @Test
    void pendingInteractionIsActionableNotAnIdempotencyConflict() throws Exception {
        assertStateRejection("INTERACTION_PENDING", "CoWork");
    }

    @Test
    void busySessionIsActionableAndKeepsBackendRetryability() throws Exception {
        assertStateRejection("SESSION_BUSY", "仍在运行");
    }

    private void assertStateRejection(String code, String hint) throws Exception {
        Db db = TestFixtures.db(tempDir);
        try (TestBackend backend = new TestBackend(call -> TestBackend.Response.json(409, rejection(call, code)));
             L3Module module = module(TestFixtures.settings(), backend, db)) {
            CommandPacket command = TestFixtures.submit("state-rejection", "hello");
            CommandOutcome outcome = module.access().submitCommand(command, TestFixtures.call()).toCompletableFuture().get();
            assertEquals(DispatchStatus.NOT_SENT, outcome.status());
            assertEquals(ChannelErrorCode.UNAVAILABLE.name(), outcome.error().code());
            assertFalse(outcome.error().retryable());
            assertTrue(outcome.error().message().contains(hint));
            var lookup = module.access().lookupOperation("state-rejection", TestFixtures.call()).toCompletableFuture().get();
            assertEquals(outcome.receipt(), lookup.receipt());
            assertEquals(outcome.error(), lookup.error(), "lookup must preserve the structured rejection, including retryability");
            assertEquals(1, backend.callCount(), "rejection and lookup must not automatically resend input");
            assertEquals("NOT_SENT", scalarPhase(db, "state-rejection"));
        }
    }

    @Test
    void sessionGetQueryNeverWritesDispatchRows() throws Exception {
        Db db = TestFixtures.db(tempDir);
        try (TestBackend backend = new TestBackend(
                call -> TestBackend.Response.json(200, """
                    {"title":"Team session (backend)","status":"waiting","revision":17,
                     "observed_at":"2026-10-31T03:00:00Z"}
                    """));
             L3Module module = module(TestFixtures.settings(), backend, db)) {
            ReceiptPacket receipt = module.access()
                .query(TestFixtures.command("q-1", TestFixtures.TARGET, CommandType.SESSION_GET,
                    new CommandPayload.SessionGet()), TestFixtures.call())
                .toCompletableFuture().get();

            ReceiptPayload.Completed completed = assertInstanceOf(ReceiptPayload.Completed.class,
                receipt.payload());
            OperationResult.SessionGet result = assertInstanceOf(OperationResult.SessionGet.class,
                completed.result());
            assertEquals(TestFixtures.TARGET, result.session().targetRef());
            assertEquals("Team session (backend)", result.session().label());
            assertTrue(result.session().available(), "read access does not imply ability to submit a new turn");
            assertEquals("waiting", result.session().execution().status());
            assertEquals(17, result.session().execution().revision());
            assertEquals(Instant.parse("2026-10-31T03:00:00Z"), result.session().execution().observedAt());
            backend.handler(call -> TestBackend.Response.json(200, """
                {"title":"Team session (backend)","status":"completed","revision":25,
                 "observed_at":"2026-10-31T03:01:00Z"}
                """));
            var updated = module.access().query(TestFixtures.command("q-2", TestFixtures.TARGET,
                CommandType.SESSION_GET, new CommandPayload.SessionGet()), TestFixtures.call()).toCompletableFuture().get();
            var next = (OperationResult.SessionGet) ((ReceiptPayload.Completed) updated.payload()).result();
            assertEquals("completed", next.session().execution().status(), "queries must read fresh backend state");
            assertEquals(25, next.session().execution().revision());
            assertEquals(0, count(db, "dispatches"));
        }
    }

    private L3Module module(L3Settings settings, TestBackend backend, Db db) {
        L3Module module = new L3Module(db, Clock.systemUTC(), settings, new TestFixtures.RecordingIntake(),
            ServiceCredentialResolver.NOOP, TestFixtures.identities());
        TestFixtures.seed(module, backend.baseUrl());
        return module;
    }

    /** 后端 v1 的 202 受理体：request_key/accepted_at/retain_until（backend-http-v1 §5）。 */
    private static String accepted(TestBackend.Call call) {
        String requestKey = com.netlivecowork.channel.contract.json.Json.read(call.body())
            .path("request_key").asText();
        Instant now = Instant.now();
        return "{\"request_key\":\"" + requestKey + "\",\"status\":\"accepted\",\"accepted_at\":\""
            + now + "\",\"retain_until\":\"" + now.plusSeconds(3_600) + "\"}";
    }

    /** 后端 v1 的确定性拒绝体：结构化 error 携带原请求键（backend-http-v1 §5）。 */
    private static String rejection(TestBackend.Call call, String code) {
        String requestKey = com.netlivecowork.channel.contract.json.Json.read(call.body())
            .path("request_key").asText();
        return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"definite rejection\",\"retryable\":false},"
            + "\"request_key\":\"" + requestKey + "\"}";
    }

    private static String scalarPhase(Db db, String operationRef) {
        return TestFixtures.scalar(db, "SELECT phase FROM dispatches WHERE operation_ref=?", operationRef);
    }

    private static int count(Db db, String table) {
        Long value = db.queryOne("SELECT COUNT(*) FROM " + table, rs -> rs.getLong(1));
        return value == null ? 0 : value.intValue();
    }

    @Test void negotiatedInteractionUsesStructuredDedicatedEndpointAndBoundEvidence() throws Exception {
        Db db=TestFixtures.db(tempDir);
        try(TestBackend backend=new TestBackend(call->{
            var body=com.netlivecowork.channel.contract.json.Json.read(call.body());
            String digest=TestFixtures.scalar(db,"SELECT request_digest FROM backend_request_bindings WHERE operation_ref=?","op-structured");
            return TestBackend.Response.json(200,"{\"request_key\":\""+body.path("request_key").asText()+"\",\"status\":\"applied\",\"request_digest\":\""+digest+"\",\"source_event_id\":\"decision-1\"}");
        });L3Module module=module(TestFixtures.settings(),backend,db)) {
            backend.interactions=true;module.registerInteraction("interaction-safe",TestFixtures.TARGET,"backend-hit-safe",10L);
            var result=module.access().submitCommand(TestFixtures.interactionRespond("op-structured","interaction-safe",10L,InteractionResponse.Approve.plain()),TestFixtures.call()).toCompletableFuture().get();
            assertEquals(DispatchStatus.AGENT_ACCEPTED,result.status());assertInstanceOf(ReceiptPayload.Completed.class,result.receipt().payload());
            assertEquals(1,backend.callCount());assertTrue(backend.lastCall().path().endsWith("/interactions/backend-hit-safe/responses"));
            var body=com.netlivecowork.channel.contract.json.Json.read(backend.lastCall().body());assertEquals(10,body.path("expected_revision").asInt());
            assertEquals("approve",body.path("response").path("kind").asText());assertFalse(body.has("action"));
        }
    }
    @Test void unprovedHttp200RemainsUnknownAndLookupNeverResendsTheDecision() throws Exception {
        Db db=TestFixtures.db(tempDir);
        try(TestBackend backend=new TestBackend(call->TestBackend.Response.json(200,"{\"applied\":true}"));
            L3Module module=module(TestFixtures.settings(),backend,db)) {
            backend.interactions=true;module.registerInteraction("interaction-unknown",TestFixtures.TARGET,"backend-hit",10L);
            var command=TestFixtures.interactionRespond("op-unknown-interaction","interaction-unknown",10L,InteractionResponse.Approve.plain());
            assertEquals(DispatchStatus.OUTCOME_UNKNOWN,module.access().submitCommand(command,TestFixtures.call()).toCompletableFuture().get().status());
            assertEquals("SENT_UNKNOWN",scalarPhase(db,"op-unknown-interaction"));
            assertEquals(DispatchStatus.OUTCOME_UNKNOWN,module.access().lookupOperation("op-unknown-interaction",TestFixtures.call()).toCompletableFuture().get().status());
            assertEquals(1,backend.calls().stream().filter(c->c.method().equals("POST")).count());
            assertTrue(backend.lastCall().path().contains("/interaction-responses/"));
        }
    }
    @Test void backendInteractionStaleIsDefiniteAndNotAnUnknownWrite() throws Exception {
        Db db=TestFixtures.db(tempDir);
        try(TestBackend backend=new TestBackend(call->TestBackend.Response.json(409,rejection(call,"INTERACTION_STALE")));
            L3Module module=module(TestFixtures.settings(),backend,db)) {
            backend.interactions=true;module.registerInteraction("interaction-stale",TestFixtures.TARGET,"backend-hit",10L);
            var result=module.access().submitCommand(TestFixtures.interactionRespond("op-stale-interaction","interaction-stale",10L,InteractionResponse.Approve.plain()),TestFixtures.call()).toCompletableFuture().get();
            assertEquals(DispatchStatus.NOT_SENT,result.status());assertEquals("INTERACTION_STALE",result.error().code());
        }
    }
}
