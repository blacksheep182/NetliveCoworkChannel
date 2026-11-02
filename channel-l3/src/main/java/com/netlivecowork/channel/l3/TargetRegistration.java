package com.netlivecowork.channel.l3;

import com.netlivecowork.channel.contract.TargetSummary;
import java.util.List;

/**
 * 逻辑目标登记：固定代表确定的服务与会话资源身份（l3-routing-and-recovery.md §4）。
 * {@code resourceEpoch} 是后端资源重建代次，属于原资源，不是进程启动次数；重连不改它。
 * {@code ownerPrincipalRef} 只用于登记归属，可用性/可见性由 L2 的共享披露规则决定（INV-02、21）。
 */
public record TargetRegistration(String targetRef, String tenantRef, String workspaceRef,
                                 String ownerPrincipalRef, String serviceRef, String sessionId,
                                 long resourceEpoch, String label, String agentRef,
                                 List<String> capabilities, TargetSummary.Availability availability,
                                 long bindingRevision, boolean enabled) {

    public TargetRegistration {
        targetRef = requireText(targetRef, "targetRef");
        tenantRef = requireText(tenantRef, "tenantRef");
        workspaceRef = requireText(workspaceRef, "workspaceRef");
        serviceRef = requireText(serviceRef, "serviceRef");
        sessionId = requireText(sessionId, "sessionId");
        label = requireText(label, "label");
        if (resourceEpoch < 1) {
            throw new IllegalArgumentException("resourceEpoch must be >= 1");
        }
        if (bindingRevision < 0) {
            throw new IllegalArgumentException("bindingRevision must be >= 0");
        }
        ownerPrincipalRef = blankToNull(ownerPrincipalRef);
        agentRef = blankToNull(agentRef);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        availability = availability == null ? TargetSummary.Availability.UNKNOWN : availability;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public boolean supports(String capability) {
        return capabilities.contains(capability);
    }

    /** resourceRef 覆盖事件 ID 的完整命名空间：服务 + 会话 + 资源代次（INV-29）。 */
    public String resourceRef() {
        return serviceRef + "/" + sessionId + "/" + resourceEpoch;
    }
}
