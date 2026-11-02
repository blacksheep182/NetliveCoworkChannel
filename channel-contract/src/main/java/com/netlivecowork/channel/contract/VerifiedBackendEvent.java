package com.netlivecowork.channel.contract;

import java.time.Instant;

/**
 * L3 已验证来源与资源关联的后端事实。L3 不选平台受众，也不改写事件正文（INV-28、29）。
 */
public record VerifiedBackendEvent(String backendEventRef, EventSource source, String operationRef,
                                   String targetRef, String taskRef, EventType type, Instant occurredAt,
                                   EventOrder order, AgentEventPayload payload) {

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
