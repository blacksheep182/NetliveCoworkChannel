package com.netlivecowork.channel.contract;

import java.time.Instant;
import java.util.Set;

/** 后端执行状态的只读快照；不代表访问权限，也不是下一次写入必定受理的承诺。 */
public record SessionExecutionState(String status, long revision, Instant observedAt,
                                    String rawStatus, InputAvailability input) {
    public SessionExecutionState(String status, long revision, Instant observedAt) {
        this(status, revision, observedAt, null, null);
    }
    public SessionExecutionState {
        if ((rawStatus == null) != (input == null) || (rawStatus != null && !Set.of(
                "IDLE", "RUNNING", "PAUSED", "PAUSED_HITL", "SUCCEEDED", "COMPLETED", "FINISHED",
                "FAILED", "CANCELED", "CANCELLED", "INTERRUPTED").contains(rawStatus))) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "invalid backend input facts");
        }
        if (!Set.of("idle", "running", "waiting", "completed", "failed", "cancelled", "interrupted", "unknown")
            .contains(status == null ? "" : status) || revision < 1 || observedAt == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "invalid session execution snapshot");
        }
    }
}
