package com.netlivecowork.channel.l3;

import com.netlivecowork.channel.contract.ChannelError;

/** 事件接入结果。accepted 表示 L3 已持久接管，不代表任务完成或平台投递（INV-22、31）。 */
public record IngestResult(boolean accepted, String eventId, ChannelError error) {

    public IngestResult {
        eventId = eventId == null || eventId.isBlank() ? null : eventId;
        if (accepted) {
            if (eventId == null) {
                throw new IllegalArgumentException("accepted ingest result requires eventId");
            }
            if (error != null) {
                throw new IllegalArgumentException("accepted ingest result cannot carry an error");
            }
        } else if (error == null) {
            throw new IllegalArgumentException("rejected ingest result requires an error");
        }
    }

    public static IngestResult accepted(String eventId) {
        return new IngestResult(true, eventId, null);
    }

    public static IngestResult rejected(ChannelError error) {
        return new IngestResult(false, null, error);
    }
}
