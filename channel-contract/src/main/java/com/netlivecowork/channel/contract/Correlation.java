package com.netlivecowork.channel.contract;

import java.util.Objects;

/**
 * 业务链与直接原因。flowId 覆盖输入、审批、回复与终态；causationId 只在有可信依据时填写。
 */
public record Correlation(String flowId, String causationId) {
    public Correlation {
        Producer.requireText(flowId, "flowId");
        if (causationId != null && causationId.isBlank()) {
            causationId = null;
        }
    }

    public static Correlation of(String flowId) {
        return new Correlation(flowId, null);
    }

    public Correlation causedBy(String causationId) {
        return new Correlation(flowId, causationId);
    }

    public Correlation copy() {
        return new Correlation(flowId, causationId);
    }

    public boolean hasCausation() {
        return causationId != null && !causationId.isBlank();
    }

    @Override
    public String toString() {
        return hasCausation() ? flowId + " <- " + causationId : flowId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Correlation c
            && Objects.equals(flowId, c.flowId)
            && Objects.equals(causationId, c.causationId);
    }
}
