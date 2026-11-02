package com.netlivecowork.channel.l2;

import com.netlivecowork.channel.l2.store.DirectoryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 归属事实刷新泵（v2.11 §2.6、backend-http-v1 §4）：后端证据有明确有效期，到期前重取后端
 * 事实续期，禁止缓存无限续权；事实不可用时权威自然失效，L2 按无依据拒绝。最小重试间隔限制
 * 对后端 facts 的访问频率，后端不可达时不形成热循环。
 */
public final class OwnershipRefreshPump {

    /** 到期前多久开始刷新；低于该窗口的事实视为需要续期。 */
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(10);
    /** 两次刷新尝试的最小间隔，也覆盖后端不可达时的退避。 */
    private static final Duration MIN_INTERVAL = Duration.ofSeconds(5);

    private final DirectoryRepository directory;
    private final ManagementService management;
    private final L2Settings settings;
    private final Clock clock;
    private volatile Instant nextAttempt = Instant.EPOCH;

    public OwnershipRefreshPump(DirectoryRepository directory, ManagementService management,
                                L2Settings settings, Clock clock) {
        this.directory = directory;
        this.management = management;
        this.settings = settings;
        this.clock = clock;
    }

    /** @return 本轮实际写入的归属事实数 */
    public int pump() {
        Instant now = clock.instant();
        if (now.isBefore(nextAttempt)) {
            return 0;
        }
        nextAttempt = now.plus(MIN_INTERVAL);
        if (!directory.ownershipRefreshDue(settings.tenantRef(), settings.workspaceRef(),
            now.plus(REFRESH_MARGIN))) {
            return 0;
        }
        return management.applyOwnershipFacts();
    }
}
