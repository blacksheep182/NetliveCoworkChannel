package com.netlivecowork.channel.contract;

import java.util.Objects;

/**
 * 规范包上下文。租户、空间与各类内部引用均来自可信登记，不接受客户端自报（INV-03）。
 * 未使用的字段为 null；省略即 null，不写空串。
 */
public record PacketContext(String tenantRef, String workspaceRef, String targetRef,
                            String operationRef, String taskRef) {
    public PacketContext {
        Producer.requireText(tenantRef, "tenantRef");
        Producer.requireText(workspaceRef, "workspaceRef");
        targetRef = blankToNull(targetRef);
        operationRef = blankToNull(operationRef);
        taskRef = blankToNull(taskRef);
    }

    public static PacketContext scope(String tenantRef, String workspaceRef) {
        return new PacketContext(tenantRef, workspaceRef, null, null, null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public PacketContext withTargetRef(String targetRef) {
        return new PacketContext(tenantRef, workspaceRef, targetRef, operationRef, taskRef);
    }

    public PacketContext withOperationRef(String operationRef) {
        return new PacketContext(tenantRef, workspaceRef, targetRef, operationRef, taskRef);
    }

    public PacketContext withTaskRef(String taskRef) {
        return new PacketContext(tenantRef, workspaceRef, targetRef, operationRef, taskRef);
    }

    public String requireTargetRef(String hint) {
        if (targetRef == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, hint + ": targetRef is required");
        }
        return targetRef;
    }

    public String requireTaskRef(String hint) {
        if (taskRef == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, hint + ": taskRef is required");
        }
        return taskRef;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PacketContext c
            && Objects.equals(tenantRef, c.tenantRef)
            && Objects.equals(workspaceRef, c.workspaceRef)
            && Objects.equals(targetRef, c.targetRef)
            && Objects.equals(operationRef, c.operationRef)
            && Objects.equals(taskRef, c.taskRef);
    }
}
