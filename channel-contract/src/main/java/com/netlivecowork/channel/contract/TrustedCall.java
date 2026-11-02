package com.netlivecowork.channel.contract;

import java.time.Instant;

/**
 * L2 → L3 的可信调用上下文：只补可信身份/委托与授权复验依据，覆盖不反写不可变 CommandPacket。
 */
public record TrustedCall(String tenantRef, String workspaceRef, String entryRef, String principalRef,
                          String delegationRef, long configRevision, Instant deadline) {
    public TrustedCall {
        Producer.requireText(tenantRef, "tenantRef");
        Producer.requireText(workspaceRef, "workspaceRef");
        Producer.requireText(entryRef, "entryRef");
        Producer.requireText(principalRef, "principalRef");
        delegationRef = delegationRef == null || delegationRef.isBlank() ? null : delegationRef;
        if (configRevision < 0) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "configRevision must be >= 0");
        }
    }
}
