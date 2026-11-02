package com.netlivecowork.channel.contract;

import java.time.Instant;

/**
 * 入站确定性拒绝的权威处置记录（S69～S72）。按完整来源键（entry/账号代次/源事件 ID）稳定可查，
 * 跨连接代次与 Adapter 版本保留；展示用的“最近 N 条”只是投影，不承担去重与保留语义。
 */
public record InboundDisposition(String entryRef, long accountBindingEpoch, String sourceEventId,
                                 String contentDigest, String code, Instant recordedAt, Instant retainUntil,
                                 boolean expired, boolean gap) {

    public InboundDisposition {
        Producer.requireText(entryRef, "disposition.entryRef");
        Producer.requireText(sourceEventId, "disposition.sourceEventId");
        Producer.requireText(code, "disposition.code");
        if (recordedAt == null || retainUntil == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "disposition requires recordedAt and retainUntil");
        }
    }

    /** 同一来源事件、同一内容才可复用原处置；异内容必须隔离而不是覆盖（S71）。 */
    public boolean sameContent(String digest) {
        return contentDigest != null && contentDigest.equals(digest);
    }
}
