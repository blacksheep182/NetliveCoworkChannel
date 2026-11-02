package com.netlivecowork.channel.contract;

import java.time.Instant;

/**
 * 资源归属事实（v2.11 §2.4、§2.6）：由 L3 验证登记后交给 L2 作授权判断；
 * 不进入公开 TargetSummary，也不凭客户端字符串授予权限。
 * revision 是后端持久归属事实的版本，owner A→B→A 也前进，与资源代次分开；
 * validUntil 是证据有效期上界，由后端提供，L3/L2 不自行延长。legacy 记录
 * 缺少有效期时不产生授权依据（S119 主体映射缺失保持门控）。
 */
public record TargetOwnership(String targetRef, String ownerPrincipalRef, String evidenceRef,
                              long resourceEpoch, long revision, Instant validUntil) {
    public TargetOwnership {
        Producer.requireText(targetRef, "ownership.targetRef");
        if (validUntil != null && revision < 1) {
            throw new IllegalArgumentException("ownership.revision must start at 1 when validity is present");
        }
    }
}
