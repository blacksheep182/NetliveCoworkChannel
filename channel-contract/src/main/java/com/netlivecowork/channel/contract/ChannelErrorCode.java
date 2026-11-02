package com.netlivecowork.channel.contract;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** 错误码表（l1-adapter.md §13）。retryable 只是继续尝试的条件之一，写入不确定优先 unknown（INV-23）。 */
public enum ChannelErrorCode {
    INVALID_ARGUMENT(false),
    UNSUPPORTED_VERSION(false),
    UNSUPPORTED_CAPABILITY(false),
    IDENTITY_UNBOUND(false),
    AUTH_REQUIRED(false),
    ACCOUNT_MISMATCH(false),
    PERMISSION_DENIED(false),
    BINDING_CONFLICT(false),
    INTERACTION_STALE(false),
    REQUEST_CONFLICT(false),
    REQUEST_NOT_FOUND(true),
    DESTINATION_STALE(false),
    UNSUPPORTED_CONTENT(false),
    RATE_LIMITED(true),
    UNAVAILABLE(true),
    OUTCOME_UNKNOWN(false),
    GRANT_REVISION_CONFLICT(false),
    GRANT_STATE_CONFLICT(false),
    INTERNAL_ERROR(false);

    private final boolean retryable;

    ChannelErrorCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }

    public ChannelError error(String message) {
        return new ChannelError(name(), message, retryable, null, null);
    }

    public ChannelError error(String message, String field) {
        return new ChannelError(name(), message, retryable, field, null);
    }

    public ChannelError retryLater(String message, long retryAfterMs) {
        return new ChannelError(name(), message, retryable, null, retryAfterMs);
    }

    public static ChannelErrorCode parse(String code) {
        try {
            return valueOf(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            return INTERNAL_ERROR;
        }
    }

    public static Set<String> names() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }
}
