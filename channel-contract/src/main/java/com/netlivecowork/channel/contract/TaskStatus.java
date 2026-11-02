package com.netlivecowork.channel.contract;

/** 任务状态。真实状态在后端；L2 只保存最后确认快照（l3-routing-and-recovery.md §3）。 */
public enum TaskStatus {
    QUEUED("queued"),
    RUNNING("running"),
    WAITING("waiting"),
    CANCELLING("cancelling"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    UNKNOWN("unknown");

    private final String wire;

    TaskStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static TaskStatus parse(String wire) {
        if (wire == null) {
            return UNKNOWN;
        }
        for (TaskStatus status : values()) {
            if (status.wire.equalsIgnoreCase(wire) || status.name().equalsIgnoreCase(wire)) {
                return status;
            }
        }
        return UNKNOWN;
    }

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
