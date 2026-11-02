package com.netlivecowork.channel.l3;

import com.netlivecowork.channel.contract.CommandType;

/**
 * 一次派发的持久关联（INV-19、67）。{@code requestKey} 只在首次登记时由 operationRef 派生并固定，
 * 重试沿用原值；{@code resourceEpoch} 固定原资源身份，不随配置变化。
 */
record Dispatch(String operationRef, String packetId, CommandType commandType, String targetRef,
                String serviceRef, String sessionId, long resourceEpoch, String requestKey,
                String payloadDigest, Phase phase, int attempts, String receiptJson, String lastError) {

    /** 派发阶段：确定未发送 / 已登记未尝试 / 结果未知 / 后端已受理。 */
    enum Phase {
        REGISTERED, SENT_UNKNOWN, AGENT_ACCEPTED, NOT_SENT
    }

    boolean mayAttempt() {
        return phase == Phase.REGISTERED || phase == Phase.NOT_SENT;
    }

    /** 已固定的实际资源与服务是否仍等于当前登记的可信解析结果。 */
    boolean matches(TargetResolver.Resolved resolved) {
        return targetRef.equals(resolved.target().targetRef())
            && serviceRef.equals(resolved.service().serviceRef())
            && sessionId.equals(resolved.target().sessionId())
            && resourceEpoch == resolved.target().resourceEpoch();
    }
}
