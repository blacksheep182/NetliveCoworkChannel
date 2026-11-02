package com.netlivecowork.channel.l3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netlivecowork.channel.infra.Db;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 回传队列的租约与围栏（INV-31、64）：领取后崩溃的事件必须在租约过期后被重新领取，
 * 且过期持有者不能覆盖新持有者的确认。
 */
class EventStoreLeaseTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-27T00:00:00Z");

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private EventStore store(MutableClock clock) throws Exception {
        Path dir = Files.createTempDirectory("l3-event-store");
        dir.toFile().deleteOnExit();
        Db db = Db.file(dir.resolve("l3.db"));
        L3Settings settings = new L3Settings(false, false, 1_000, 3, 0, 10, 5);
        return new EventStore(db, clock, settings);
    }

    private EventStore.EventDraft draft(String eventId) {
        return new EventStore.EventDraft("backend-event-" + eventId, "svc-1/session-1/1", eventId,
            "target-1", "op-1", "rk-1", "message.ready", Instant.parse("2026-10-27T00:00:00Z"),
            "digest-" + eventId, "{\"backendEventRef\":\"backend-event-" + eventId + "\"}");
    }

    @Test
    void expiredSendingLeaseIsReclaimedAndStaleOwnerCannotAcknowledge() throws Exception {
        MutableClock clock = new MutableClock();
        EventStore events = store(clock);
        assertEquals(EventStore.InsertResult.ACCEPTED, events.insert(draft("e1")));

        List<EventStore.Claimed> first = events.claim(10, "owner-A");
        assertEquals(1, first.size());
        // 租约未过期：另一个 worker 领不到同一条事件
        assertTrue(events.claim(10, "owner-B").isEmpty());

        // 模拟进程在领取后、确认前退出：租约过期后可被重新领取
        clock.advanceSeconds(6);
        List<EventStore.Claimed> second = events.claim(10, "owner-B");
        assertEquals(1, second.size());
        assertEquals(first.get(0).backendEventRef(), second.get(0).backendEventRef());

        // 过期持有者的确认不得覆盖新状态：owner-A 的 ack 被 owner 围栏拒绝，事件仍由 owner-B 持有
        events.ack(second.get(0).backendEventRef(), "owner-A");
        assertTrue(events.claim(10, "owner-C").isEmpty());

        // 新持有者确认后才算送达
        events.ack(second.get(0).backendEventRef(), "owner-B");
        clock.advanceSeconds(60);
        assertTrue(events.claim(10, "owner-D").isEmpty());
    }
}
