package com.netlivecowork.channel.l2.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.netlivecowork.channel.infra.Db;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 映射登记待办的持久化、领取与重试语义（不在等待中丢待办）。 */
class RegistrationRepositoryTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-27T00:00:00Z");

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

    @Test
    void intentIsIdempotentAndRetriesUntilFailed() throws Exception {
        Path dir = Files.createTempDirectory("l2-registration");
        dir.toFile().deleteOnExit();
        Db db = Db.file(dir.resolve("l2.db"));
        L2Schema.apply(db);
        MutableClock clock = new MutableClock();
        RegistrationRepository registrations = new RegistrationRepository(db, clock);

        registrations.enqueue(RegistrationRepository.KIND_INTERACTION, "target-1", "itx-1", "backend-itx-1",
            1L, null);
        registrations.enqueue(RegistrationRepository.KIND_INTERACTION, "target-1", "itx-1", "backend-itx-1",
            1L, null);
        assertEquals(1, registrations.pending(10).size(), "enqueue must be idempotent per local ref");

        RegistrationRepository.Registration claimed = registrations.claim(
            registrations.pending(10).get(0).registrationRef());
        assertNotNull(claimed);
        assertEquals(1, claimed.attempts());
        assertNull(registrations.claim(claimed.registrationRef()), "only one worker may claim a registration");

        registrations.retry(claimed.registrationRef(), clock.instant(), "temporary failure");
        assertEquals("PENDING", registrations.get(claimed.registrationRef()).state());
        assertEquals("temporary failure", registrations.get(claimed.registrationRef()).lastError());

        registrations.fail(claimed.registrationRef(), "permanent failure");
        assertEquals("FAILED", registrations.get(claimed.registrationRef()).state());
        assertEquals(0, registrations.pending(10).size());
    }
}
