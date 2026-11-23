package com.netlivecowork.channel.contract;

import java.time.Instant;

/**
 * L3 已验证来源与资源关联的后端事实。L3 不选平台受众，也不改写事件正文（INV-28、29）。
 */
public record VerifiedBackendEvent(String backendEventRef, EventSource source, String operationRef,
                                   String targetRef, String taskRef, EventType type, Instant occurredAt,
                                   EventOrder order, AgentEventPayload payload, Observation observation) {

    public VerifiedBackendEvent(String backendEventRef, EventSource source, String operationRef,
                                String targetRef, String taskRef, EventType type, Instant occurredAt,
                                EventOrder order, AgentEventPayload payload) {
        this(backendEventRef, source, operationRef, targetRef, taskRef, type, occurredAt, order, payload, null);
    }

    /** A Cowork-owned delivery basis, separate from the source event identity. */
    public record Observation(String serviceRef, String sinkRef, String ref, long revision, String eventDigest) {
        public Observation {
            if (serviceRef == null || serviceRef.isBlank() || sinkRef == null || sinkRef.isBlank()
                    || ref == null || !ref.matches("sub-[a-f0-9]{32}") || revision < 1
                    || eventDigest == null || !eventDigest.matches("[a-f0-9]{64}")) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "Invalid observation reference");
            }
        }
    }

    public VerifiedBackendEvent {
        Producer.requireText(backendEventRef, "backendEventRef");
        if (source == null || type == null || payload == null || occurredAt == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "verified event requires source, type, occurredAt and payload");
        }
        operationRef = blankToNull(operationRef);
        targetRef = blankToNull(targetRef);
        taskRef = blankToNull(taskRef);
        // taskRef 由 L2 依后端任务身份建立；L3 不虚构内部任务引用，因此这里不强制。
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
