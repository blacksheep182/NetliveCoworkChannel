package com.netlivecowork.channel.l3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.json.PacketJson;
import com.netlivecowork.channel.infra.Db;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 事件接入：来源验证、去重、冲突隔离、关联解析与提交后才确认（INV-29～32、66）。 */
class EventIngestTest {

    @TempDir
    Path tempDir;

    @Test
    void validEventIsAcceptedAndQueuedPending() throws Exception {
        Db db = TestFixtures.db(tempDir);
        TestFixtures.RecordingIntake intake = new TestFixtures.RecordingIntake();
        try (TestBackend backend = new TestBackend(
                call -> TestBackend.Response.json(202, "{\"status\":\"accepted\"}"));
             L3Module module = new L3Module(db, Clock.systemUTC(), TestFixtures.settings(), intake)) {
            TestFixtures.seed(module, backend.baseUrl());
            module.events().registerSink(TestFixtures.SINK, TestFixtures.SERVICE, "sink-token");
            String requestKey = submitAndReadRequestKey(module, db, "op-e1");

            IngestResult result = module.events().ingest(TestFixtures.SINK, "sink-token",
                TestFixtures.agentEvent("evt-1", requestKey, "answer"));

            assertTrue(result.accepted());
            assertEquals("evt-1", result.eventId());
            assertEquals("pending", column(db, "forward_status"));
            assertEquals(TestFixtures.SERVICE + "/" + TestFixtures.SESSION + "/1",
                column(db, "resource_ref"));
            assertEquals("op-e1", column(db, "operation_ref"));
            assertEquals("evt-1", column(db, "source_event_id"));
            // 已验证事件可解码，且 taskRef 不由 L3 虚构。
            var verified = PacketJson.decVerifiedEvent(
                TestFixtures.scalar(db, "SELECT verified_json FROM backend_events"));
            assertNull(verified.taskRef());
            assertEquals(TestFixtures.TARGET, verified.targetRef());
            assertEquals("op-e1", verified.operationRef());
            assertEquals("evt-1", verified.source().sourceEventId());
        }
    }

    @Test
    void duplicateIdenticalEventIsIdempotent() throws Exception {
        Db db = TestFixtures.db(tempDir);
        try (TestBackend backend = new TestBackend(
                call -> TestBackend.Response.json(202, "{\"status\":\"accepted\"}"));
             L3Module module = new L3Module(db, Clock.systemUTC(), TestFixtures.settings(),
                 new TestFixtures.RecordingIntake())) {
            TestFixtures.seed(module, backend.baseUrl());
            module.events().registerSink(TestFixtures.SINK, TestFixtures.SERVICE, "sink-token");
            String body = TestFixtures.agentEvent("evt-2", null, "answer");

            assertTrue(module.events().ingest(TestFixtures.SINK, "sink-token", body).accepted());
            assertTrue(module.events().ingest(TestFixtures.SINK, "sink-token", body).accepted());

            assertEquals(1, countEvents(db));
            assertEquals("pending", column(db, "forward_status"));
        }
    }

    @Test
    void duplicateWithDifferentContentIsConflictAndQuarantined() throws Exception {
        Db db = TestFixtures.db(tempDir);
        try (TestBackend backend = new TestBackend(
                call -> TestBackend.Response.json(202, "{\"status\":\"accepted\"}"));
             L3Module module = new L3Module(db, Clock.systemUTC(), TestFixtures.settings(),
                 new TestFixtures.RecordingIntake())) {
            TestFixtures.seed(module, backend.baseUrl());
            module.events().registerSink(TestFixtures.SINK, TestFixtures.SERVICE, "sink-token");

            assertTrue(module.events().ingest(TestFixtures.SINK, "sink-token",
                TestFixtures.agentEvent("evt-3", null, "first")).accepted());
            IngestResult conflict = module.events().ingest(TestFixtures.SINK, "sink-token",
                TestFixtures.agentEvent("evt-3", null, "changed"));

            assertFalse(conflict.accepted());
            assertEquals(ChannelErrorCode.REQUEST_CONFLICT.name(), conflict.error().code());
            assertEquals(1, countEvents(db));
            // 冲突变体被隔离；原事件内容与转交状态不被覆盖或阻断（INV-32、64）。
            assertEquals(1, count(db, "backend_event_conflicts"));
            assertEquals("pending", column(db, "forward_status"));
            assertTrue(TestFixtures.scalar(db, "SELECT verified_json FROM backend_events").contains("first"));
        }
    }

    @Test
    void unknownSinkAndWrongTokenAreRejected() throws Exception {
        Db db = TestFixtures.db(tempDir);
        try (TestBackend backend = new TestBackend(call -> TestBackend.Response.status(202));
             L3Module module = new L3Module(db, Clock.systemUTC(), TestFixtures.settings(),
                 new TestFixtures.RecordingIntake())) {
            TestFixtures.seed(module, backend.baseUrl());
            module.events().registerSink(TestFixtures.SINK, TestFixtures.SERVICE, "sink-token");
            String body = TestFixtures.agentEvent("evt-4", null, "answer");

            IngestResult unknownSink = module.events().ingest("sink-other", "sink-token", body);
            assertFalse(unknownSink.accepted());
            assertEquals(ChannelErrorCode.AUTH_REQUIRED.name(), unknownSink.error().code());

            IngestResult badToken = module.events().ingest(TestFixtures.SINK, "wrong-token", body);
            assertFalse(badToken.accepted());
            assertEquals(ChannelErrorCode.AUTH_REQUIRED.name(), badToken.error().code());
            assertEquals(0, countEvents(db));
        }
    }

    @Test
    void unregisteredSessionIsDenied() throws Exception {
        Db db = TestFixtures.db(tempDir);
        try (TestBackend backend = new TestBackend(call -> TestBackend.Response.status(202));
             L3Module module = new L3Module(db, Clock.systemUTC(), TestFixtures.settings(),
                 new TestFixtures.RecordingIntake())) {
            TestFixtures.seed(module, backend.baseUrl());
            module.events().registerSink(TestFixtures.SINK, "service-other", "sink-token");

            IngestResult result = module.events().ingest(TestFixtures.SINK, "sink-token",
                TestFixtures.agentEvent("evt-5", null, "answer"));

            assertFalse(result.accepted());
            assertEquals(ChannelErrorCode.PERMISSION_DENIED.name(), result.error().code());
            assertEquals(0, countEvents(db));
        }
    }

    @Test
    void malformedEventIsRejectedAsInvalidArgument() throws Exception {
        Db db = TestFixtures.db(tempDir);
        try (TestBackend backend = new TestBackend(call -> TestBackend.Response.status(202));
             L3Module module = new L3Module(db, Clock.systemUTC(), TestFixtures.settings(),
                 new TestFixtures.RecordingIntake())) {
            TestFixtures.seed(module, backend.baseUrl());
            module.events().registerSink(TestFixtures.SINK, TestFixtures.SERVICE, "sink-token");

            IngestResult unknownField = module.events().ingest(TestFixtures.SINK, "sink-token",
                "{\"version\":\"1\",\"eventId\":\"evt-6\",\"type\":\"message.ready\","
                    + "\"occurredAt\":\"2026-10-27T10:00:00Z\",\"resource\":{\"sessionId\":\"session-1\"},"
                    + "\"payload\":{\"content\":[{\"partId\":\"p1\",\"type\":\"text\",\"text\":\"x\","
                    + "\"format\":\"plain\"}],\"backendMessageId\":\"m-1\"},\"extra\":true}");

            assertFalse(unknownField.accepted());
            assertEquals(ChannelErrorCode.INVALID_ARGUMENT.name(), unknownField.error().code());
            assertEquals(0, countEvents(db));
        }
    }

    private static String submitAndReadRequestKey(L3Module module, Db db, String operationRef) throws Exception {
        module.access().submitCommand(TestFixtures.submit(operationRef, "hello"), TestFixtures.call())
            .toCompletableFuture().get();
        String requestKey = TestFixtures.scalar(db, "SELECT request_key FROM dispatches WHERE operation_ref=?",
            operationRef);
        assertNotNull(requestKey);
        return requestKey;
    }

    private static String column(Db db, String column) {
        return TestFixtures.scalar(db, "SELECT " + column + " FROM backend_events");
    }

    private static int countEvents(Db db) {
        return count(db, "backend_events");
    }

    private static int count(Db db, String table) {
        Long count = db.queryOne("SELECT COUNT(*) FROM " + table, rs -> rs.getLong(1));
        return count == null ? 0 : count.intValue();
    }
}
