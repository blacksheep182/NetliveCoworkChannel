package com.netlivecowork.channel.contract;

import java.util.List;

/**
 * 用户可选的逻辑目标。实际服务地址归 L3，L2 只保存逻辑引用、名称、能力与可用性观测（INV-11）。
 */
public record TargetSummary(String targetRef, String agentRef, String label, List<String> capabilities,
                            Availability availability) {
    public TargetSummary {
        Producer.requireText(targetRef, "targetRef");
        Producer.requireText(label, "label");
        agentRef = agentRef == null || agentRef.isBlank() ? null : agentRef;
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        availability = availability == null ? Availability.UNKNOWN : availability;
    }

    public enum Availability {
        ONLINE, OFFLINE, UNKNOWN
    }

    public static TargetSummary of(String targetRef, String label) {
        return new TargetSummary(targetRef, null, label, List.of(), Availability.UNKNOWN);
    }
}
