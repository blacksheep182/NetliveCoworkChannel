package com.netlivecowork.channel.l2;

import java.time.Duration;
import java.time.Instant;

/**
 * L2 运行参数。期限与退避是可配置建议值，不是协议常量（l3-backend.md §4.4）。
 * tenantRef/workspaceRef 是本部署的可信资源域，不接受客户端自报（INV-03）。
 */
public record L2Settings(String tenantRef, String workspaceRef, String componentRef,
                         long admissionTtlSeconds, int dispatchMaxAttempts,
                         long dispatchInitialBackoffMs, long dispatchMaxBackoffMs,
                         long pendingDispatchTtlSeconds, long deliveryTimeoutMs, int deliveryMaxAttempts,
                         long deliveryInitialBackoffMs, long deliveryMaxBackoffMs,
                         long deliveryLeaseSeconds, int outboxBatch, int dispatchBatch,
                         boolean autoProvisionIdentities) {

    public L2Settings {
        if (tenantRef == null || tenantRef.isBlank()) {
            throw new IllegalArgumentException("tenantRef is required");
        }
        if (workspaceRef == null || workspaceRef.isBlank()) {
            throw new IllegalArgumentException("workspaceRef is required");
        }
        componentRef = componentRef == null || componentRef.isBlank() ? "channel-l2" : componentRef;
    }

    public static L2Settings standalone(String tenantRef, String workspaceRef) {
        return new L2Settings(tenantRef, workspaceRef, "channel-l2", 300, 10, 2_000, 60_000, 900, 10_000,
            5, 2_000, 300_000, 60, 20, 20, false);
    }

    public Duration admissionTtl() {
        return Duration.ofSeconds(admissionTtlSeconds);
    }

    public Duration pendingDispatchTtl() {
        return Duration.ofSeconds(pendingDispatchTtlSeconds);
    }

    public Instant dispatchDeadline(Instant now) {
        return now.plus(Duration.ofSeconds(Math.max(30, deliveryTimeoutMs / 1000 + 30)));
    }

    public long dispatchBackoff(int attempt) {
        long delay = dispatchInitialBackoffMs;
        for (int i = 1; i < attempt; i++) {
            delay = Math.min(dispatchMaxBackoffMs, delay * 2);
        }
        return Math.min(dispatchMaxBackoffMs, delay);
    }

    public long deliveryBackoff(int attempt) {
        long delay = deliveryInitialBackoffMs;
        for (int i = 1; i < attempt; i++) {
            delay = Math.min(deliveryMaxBackoffMs, delay * 2);
        }
        return Math.min(deliveryMaxBackoffMs, delay);
    }
}
