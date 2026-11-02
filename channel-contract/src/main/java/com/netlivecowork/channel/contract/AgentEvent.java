package com.netlivecowork.channel.contract;

/**
 * 后端原始业务事实。eventId 由后端登记并在重发时保持；correlation.requestKey 是 L3 派发时建立的
 * 关联，不由后端自报其它身份（INV-29、32）。
 */
public record AgentEvent(String version, String eventId, String type, java.time.Instant occurredAt,
                         Resource resource, AgentCorrelation correlation, EventOrder order,
                         AgentEventPayload payload) {

    public AgentEvent {
        Packet.requireVersionOne(version);
        Producer.requireText(eventId, "event.eventId");
        Producer.requireText(type, "event.type");
        if (occurredAt == null || resource == null || payload == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "agent event requires occurredAt, resource and payload");
        }
    }

    public EventType eventType() {
        return EventType.parse(type);
    }

    /** 实际工作对象：会话、可选的执行流与后端任务。 */
    public record Resource(String sessionId, String runId, String taskId) {
        public Resource {
            Producer.requireText(sessionId, "resource.sessionId");
            runId = runId == null || runId.isBlank() ? null : runId;
            taskId = taskId == null || taskId.isBlank() ? null : taskId;
        }
    }

    /** 仅回传 L3 派发时建立的关联；causedByEventId 只在后端有明确因果时填写。 */
    public record AgentCorrelation(String requestKey, String causedByEventId) {
        public AgentCorrelation {
            requestKey = requestKey == null || requestKey.isBlank() ? null : requestKey;
            causedByEventId = causedByEventId == null || causedByEventId.isBlank() ? null : causedByEventId;
        }
    }
}
