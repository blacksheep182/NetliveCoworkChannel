package com.netlivecowork.channel.l2;

import com.fasterxml.jackson.databind.JsonNode;
import com.netlivecowork.channel.contract.FileMetadata;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.port.BackendAccessPort;
import com.netlivecowork.channel.l2.store.RegistrationRepository;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 重试 L2 → L3 的交互/文件映射登记；失败退避，超限进入 FAILED 等待人工重试。 */
public final class BackendRegistrationPump {

    private static final Logger log = LoggerFactory.getLogger(BackendRegistrationPump.class);
    private static final int MAX_ATTEMPTS = 6;
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

    private final RegistrationRepository registrations;
    private final BackendAccessPort backend;
    private final Clock clock;

    public BackendRegistrationPump(RegistrationRepository registrations, BackendAccessPort backend, Clock clock) {
        this.registrations = registrations;
        this.backend = backend;
        this.clock = clock;
    }

    public int pump(int limit) {
        int done = 0;
        for (RegistrationRepository.Registration candidate : registrations.pending(limit)) {
            RegistrationRepository.Registration claimed = registrations.claim(candidate.registrationRef());
            if (claimed == null) {
                continue;
            }
            try {
                switch (claimed.kind()) {
                    case RegistrationRepository.KIND_INTERACTION -> backend.registerInteraction(claimed.localRef(),
                        claimed.targetRef(), claimed.backendRef(), claimed.revision())
                        .toCompletableFuture().join();
                    case RegistrationRepository.KIND_FILE -> {
                        JsonNode metadata = Json.read(claimed.payloadJson());
                        backend.registerFile(claimed.localRef(), claimed.targetRef(), claimed.backendRef(),
                            new FileMetadata(metadata.path("name").asText(),
                                metadata.path("mediaType").asText(), metadata.path("sizeBytes").asLong()))
                            .toCompletableFuture().join();
                    }
                    default -> throw new IllegalStateException("unknown registration kind: " + claimed.kind());
                }
                registrations.done(claimed.registrationRef());
                done++;
            } catch (RuntimeException e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                if (claimed.attempts() >= MAX_ATTEMPTS) {
                    registrations.fail(claimed.registrationRef(), message);
                    log.warn("backend registration {} failed permanently: {}",
                        claimed.registrationRef(), message);
                } else {
                    registrations.retry(claimed.registrationRef(),
                        clock.instant().plus(backoff(claimed.attempts())), message);
                }
            }
        }
        return done;
    }

    private Duration backoff(int attempts) {
        Duration delay = Duration.ofSeconds(5).multipliedBy(1L << Math.min(attempts - 1, 6));
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }
}
