package com.netlivecowork.channel.contract;

import java.util.List;

/** Receiver custody and authenticated client progress are independent of backend execution. */
public final class DeliveryInbox {
    private DeliveryInbox() {}
    public record Receipt(String deliveryId, String receiptRef, String sequence) {}
    public record Item(String sequence, ChannelDelivery delivery, String readAt) {}
    public record Request(String consumerKey, String cursor, Integer limit) {
        public Request {
            Producer.requireText(consumerKey, "consumerKey");
            if (!consumerKey.matches("[A-Za-z0-9_-]{16,80}") || (limit != null && (limit < 1 || limit > 100)))
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "Invalid inbox page");
        }
        public int pageSize() { return limit == null ? 20 : limit; }
    }
    public record Page(String consumerRef, List<Item> items, String nextCursor, boolean hasMore) {}
    public record Result(String status, Page page, String reason, String resyncCursor) {
        public static Result page(Page page) { return new Result("page", page, null, null); }
        public static Result gap(String reason, String cursor) { return new Result("gap", null, reason, cursor); }
    }
    public record Acknowledgement(String consumerRef, String kind, String throughCursor, List<String> deliveryIds) {
        public Acknowledgement {
            Producer.requireText(consumerRef, "consumerRef");
            if (!("received".equals(kind) && throughCursor != null && deliveryIds == null)
                && !("read".equals(kind) && throughCursor == null && deliveryIds != null && deliveryIds.size() <= 100))
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "Invalid inbox acknowledgement");
        }
    }
}
