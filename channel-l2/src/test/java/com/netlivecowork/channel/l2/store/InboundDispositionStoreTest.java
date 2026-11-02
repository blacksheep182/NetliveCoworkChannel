package com.netlivecowork.channel.l2.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netlivecowork.channel.contract.DispositionResult;
import com.netlivecowork.channel.contract.InboundDisposition;
import com.netlivecowork.channel.infra.Db;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 入站处置表的权威性、去重、冲突隔离与保留边界（S69～S72）。 */
class InboundDispositionStoreTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-27T00:00:00Z");

        void advanceDays(long days) {
            now = now.plusSeconds(days * 86_400);
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

    private InboundDispositionStore store(MutableClock clock) throws Exception {
        Path dir = Files.createTempDirectory("l2-disposition");
        dir.toFile().deleteOnExit();
        Db db = Db.file(dir.resolve("l2.db"));
        L2Schema.apply(db);
        return new InboundDispositionStore(db, clock, "entry-1", 1L);
    }

    @Test
    void recordsAreDeduplicatedQuarantinedAndExpire() throws Exception {
        MutableClock clock = new MutableClock();
        InboundDispositionStore store = store(clock);

        assertNull(store.find("m1").toCompletableFuture().join());
        assertEquals(DispositionResult.RECORDED,
            store.recordRejected("m1", "digest-a", "IDENTITY_UNBOUND").toCompletableFuture().join());

        InboundDisposition recorded = store.find("m1").toCompletableFuture().join();
        assertNotNull(recorded);
        assertEquals("IDENTITY_UNBOUND", recorded.code());
        assertTrue(recorded.sameContent("digest-a"));
        assertTrue(!recorded.expired());

        // 同键同内容重投：幂等复用，不覆盖
        assertEquals(DispositionResult.DUPLICATE,
            store.recordRejected("m1", "digest-a", "IDENTITY_UNBOUND").toCompletableFuture().join());
        // 同键异内容：隔离，原处置保留（S71）
        assertEquals(DispositionResult.CONFLICT,
            store.recordRejected("m1", "digest-b", "OTHER").toCompletableFuture().join());
        assertTrue(store.find("m1").toCompletableFuture().join().sameContent("digest-a"));

        // 超出保留边界：明确为过期而不是“从未处理”（S72）
        clock.advanceDays(31);
        assertTrue(store.find("m1").toCompletableFuture().join().expired());

        // 缺口标记可查
        store.markGap("m2", "retention expired").toCompletableFuture().join();
        assertTrue(store.find("m2").toCompletableFuture().join().gap());
    }
}
