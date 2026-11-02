package com.netlivecowork.channel.contract;

/** 事件来源：L3 签发或确认的实际来源关联；sourceEventId 只在完整命名空间内去重（INV-29）。 */
public record EventSource(String resourceRef, String sourceEventId) {
    public EventSource {
        Producer.requireText(resourceRef, "source.resourceRef");
        Producer.requireText(sourceEventId, "source.sourceEventId");
    }

    /** 完整去重键：来源命名空间 + 原始事件 ID。 */
    public String dedupeKey() {
        return resourceRef + "\u0000" + sourceEventId;
    }
}
