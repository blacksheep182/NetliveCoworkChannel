package com.netlivecowork.channel.l3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netlivecowork.channel.infra.Db;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 回传泵：确认、退避重试、达到上限暂停且不丢事件（INV-31、64）。 */
class ForwardPumpTest {

    @TempDir
    Path tempDir;

    @Test
    void ackingIntakeMarksEventAcked() throws Exception {
        Db db = TestFixtures.db(tempDir);
        TestFixtures.RecordingIntake intake = new TestFixtures.RecordingIntake();
        try (TestBackend backend = new TestBackend(call -> TestBackend.Response.status(202));
             L3Module module = new L3Module(db, Clock.systemUTC(), TestFixtures.settings(), intake)) {
            TestFixtures.seed(module, backend.baseUrl());
            module.events().registerSink(TestFixtures.SINK, TestFixtures.SERVICE, "sink-token");
            assertTrue(module.events().ingest(TestFixtures.SINK, "sink-token",
                TestFixtures.agentEvent("evt-f1", null, "answer")).accepted());

            assertEquals(1, module.forwardOnce(10));

            assertEquals(1, intake.received().size());
            assertEquals("evt-f1", intake.received().get(0).source().sourceEventId());
            assertEquals("acked", status(db));
            assertNotNull(TestFixtures.scalar(db, "SELECT acked_at FROM backend_events"));
            // 已确认事件不会被再次领取。
            assertEquals(0, module.forwardOnce(10));
        }
    }

    @Test
    void failingIntakeRetriesThenPausesWithoutLosingTheEvent() throws Exception {
        Db db = TestFixtures.db(tempDir);
        TestFixtures.RecordingIntake intake = new TestFixtures.RecordingIntake();
        intake.accepting = false;
        // forwardMaxAttempts=2，退避为 0 以便直接重试。
        try (TestBackend backend = new TestBackend(call -> TestBackend.Response.status(202));
             L3Module module = new L3Module(db, Clock.systemUTC(),
                 TestFixtures.settings(2_000L, 2, false, false), intake)) {
            TestFixtures.seed(module, backend.baseUrl());
            module.events().registerSink(TestFixtures.SINK, TestFixtures.SERVICE, "sink-token");
            assertTrue(module.events().ingest(TestFixtures.SINK, "sink-token",
                TestFixtures.agentEvent("evt-f2", null, "answer")).accepted());

            assertEquals(0, module.forwardOnce(10));
            assertEquals("pending", status(db));
            assertEquals(1, attempts(db));
            assertNotNull(TestFixtures.scalar(db, "SELECT next_attempt_at FROM backend_events"));

            assertEquals(0, module.forwardOnce(10));
            assertEquals("paused", status(db));
            assertEquals(2, attempts(db));
            // 事件与内容仍然完整保留（未确认事件不得静默删除）。
            assertTrue(TestFixtures.scalar(db, "SELECT verified_json FROM backend_events").contains("evt-f2"));
            assertEquals(0, module.forwardOnce(10));
        }
    }

    @Test
    void resumedIntakeDeliversTheRetainedEvent() throws Exception {
        Db db = TestFixtures.db(tempDir);
        TestFixtures.RecordingIntake intake = new TestFixtures.RecordingIntake();
        intake.accepting = false;
        try (TestBackend backend = new TestBackend(call -> TestBackend.Response.status(202));
             L3Module module = new L3Module(db, Clock.systemUTC(),
                 TestFixtures.settings(2_000L, 3, false, false), intake)) {
            TestFixtures.seed(module, backend.baseUrl());
            module.events().registerSink(TestFixtures.SINK, TestFixtures.SERVICE, "sink-token");
            module.events().ingest(TestFixtures.SINK, "sink-token", TestFixtures.agentEvent("evt-f3", null, "a"));

            module.forwardOnce(10);
            assertEquals("pending", status(db));
            intake.accepting = true;
            assertEquals(1, module.forwardOnce(10));
            assertEquals("acked", status(db));
        }
    }

    private static String status(Db db) {
        return TestFixtures.scalar(db, "SELECT forward_status FROM backend_events");
    }

    private static int attempts(Db db) {
        Long value = db.queryOne("SELECT attempts FROM backend_events", rs -> rs.getLong(1));
        return value == null ? -1 : value.intValue();
    }
}
