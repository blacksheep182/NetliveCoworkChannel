package com.netlivecowork.channel.contract;

/** 规范事件类型（data-contracts.md §7）。message.ready 与 task.completed 表达不同事实（INV-26）。 */
public enum EventType {
    MESSAGE_READY("message.ready", EventPayload.MessageReady.class),
    TASK_STARTED("task.started", EventPayload.TaskStarted.class),
    TASK_PROGRESS("task.progress", EventPayload.TaskProgress.class),
    TASK_WAITING("task.waiting", EventPayload.TaskWaiting.class),
    TASK_COMPLETED("task.completed", EventPayload.TaskCompleted.class),
    TASK_FAILED("task.failed", EventPayload.TaskFailed.class),
    TASK_CANCELLED("task.cancelled", EventPayload.TaskCancelled.class),
    TASK_ARTIFACT("task.artifact", EventPayload.TaskArtifact.class),
    INTERACTION_REQUIRED("interaction.required", EventPayload.InteractionRequired.class),
    INTERACTION_RESOLVED("interaction.resolved", EventPayload.InteractionResolved.class),
    INTERACTION_EXPIRED("interaction.expired", EventPayload.InteractionExpired.class),
    INTERACTION_CANCELLED("interaction.cancelled", EventPayload.InteractionCancelled.class);

    private final String wire;
    private final Class<? extends EventPayload> payloadType;

    EventType(String wire, Class<? extends EventPayload> payloadType) {
        this.wire = wire;
        this.payloadType = payloadType;
    }

    public String wire() {
        return wire;
    }

    public Class<? extends EventPayload> payloadType() {
        return payloadType;
    }

    public boolean isTaskEvent() {
        return this != MESSAGE_READY && this != INTERACTION_REQUIRED && this != INTERACTION_RESOLVED
            && this != INTERACTION_EXPIRED && this != INTERACTION_CANCELLED;
    }

    public TaskStatus terminalTaskStatus() {
        return switch (this) {
            case TASK_COMPLETED -> TaskStatus.COMPLETED;
            case TASK_FAILED -> TaskStatus.FAILED;
            case TASK_CANCELLED -> TaskStatus.CANCELLED;
            default -> null;
        };
    }

    public static EventType parse(String wire) {
        if (wire == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "event.type is required");
        }
        for (EventType type : values()) {
            if (type.wire.equals(wire)) {
                return type;
            }
        }
        throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "unknown event type: " + wire);
    }
}
