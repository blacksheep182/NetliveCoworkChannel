package com.netlivecowork.channel.contract;

/**
 * 面向入口的结构化错误。消息必须脱敏，不带堆栈、凭证或内部地址（INV-52）。
 */
public record ChannelError(String code, String message, boolean retryable, String field, Long retryAfterMs) {
    private static final int MAX_MESSAGE = 512;

    public ChannelError {
        Producer.requireText(code, "code");
        message = sanitize(message);
        field = field == null || field.isBlank() ? null : field;
    }

    public static ChannelError of(ChannelErrorCode code, String message) {
        return new ChannelError(code.name(), message, code.retryable(), null, null);
    }

    public static ChannelError of(ChannelErrorCode code, String message, String field) {
        return new ChannelError(code.name(), message, code.retryable(), field, null);
    }

    public ChannelErrorCode errorCode() {
        return ChannelErrorCode.parse(code);
    }

    public ChannelError withField(String field) {
        return new ChannelError(code, message, retryable, field, retryAfterMs);
    }

    public ChannelError withRetryAfterMs(long retryAfterMs) {
        return new ChannelError(code, message, retryable, field, retryAfterMs);
    }

    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unspecified error";
        }
        String flat = raw.replace('\r', ' ').replace('\n', ' ').trim();
        return flat.length() <= MAX_MESSAGE ? flat : flat.substring(0, MAX_MESSAGE);
    }
}
