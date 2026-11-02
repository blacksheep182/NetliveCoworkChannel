package com.netlivecowork.channel.contract;

import java.time.Instant;
import java.util.List;

/** Read-only history views. Backend IDs/cursors are restricted to the L2–L3 port. */
public final class SessionHistory {
    private SessionHistory() {}
    public record Request(String targetRef, PageRequest page) {
        public Request {
            Producer.requireText(targetRef, "history.targetRef");
            page = page == null ? PageRequest.first() : page;
            if (page.effectiveLimit() > 100) throw new ChannelContractException(
                ChannelErrorCode.INVALID_ARGUMENT, "history page limit must be within 1..100");
        }
    }
    public record Message(String messageRef, String role, String text, boolean unsupportedContent,
                          Instant createdAt) {}
    public record Page(String targetRef, List<Message> items, String nextCursor) {
        public Page { items = List.copyOf(items); }
    }
    public record BackendMessage(String backendMessageId, String role, String text,
                                 boolean unsupportedContent, Instant createdAt) {
        public BackendMessage {
            Producer.requireText(backendMessageId, "history.backendMessageId");
            if (!("user".equals(role) || "assistant".equals(role)) || text == null || createdAt == null)
                throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE, "invalid backend history message");
        }
    }
    public record BackendPage(List<BackendMessage> items, String nextCursor) {
        public BackendPage { items = List.copyOf(items); }
    }
}
