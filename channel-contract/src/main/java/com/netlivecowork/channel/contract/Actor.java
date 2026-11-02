package com.netlivecowork.channel.contract;

/**
 * 实际操作者与委托依据。操作人与资源所有者分离（INV-02）；共享会话不按当前发言者重解析。
 */
public record Actor(String principalRef, String delegationRef) {
    public Actor {
        Producer.requireText(principalRef, "actor.principalRef");
        delegationRef = delegationRef == null || delegationRef.isBlank() ? null : delegationRef;
    }

    public static Actor of(String principalRef) {
        return new Actor(principalRef, null);
    }
}
