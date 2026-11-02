package com.netlivecowork.channel.contract;

/**
 * 分页请求。游标归属由签发方校验，不接受别的订阅或租户的游标（l2-application.md §3.1）。
 */
public record PageRequest(String cursor, Integer limit) {
    public static final int MAX_LIMIT = 200;

    public PageRequest {
        cursor = cursor == null || cursor.isBlank() ? null : cursor;
        if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "page.limit must be within 1.." + MAX_LIMIT);
        }
    }

    public static PageRequest first() {
        return new PageRequest(null, null);
    }

    public static PageRequest of(String cursor, Integer limit) {
        return new PageRequest(cursor, limit);
    }

    public int effectiveLimit() {
        return limit == null ? 20 : limit;
    }
}
