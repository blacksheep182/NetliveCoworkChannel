package com.netlivecowork.channel.contract.adapter;

import com.netlivecowork.channel.contract.ChannelError;
import java.time.Instant;

/**
 * 平台账号授权会话。令牌写入凭据设施，不经 authStatus 返回（l1-adapter.md §10）。
 */
public record AuthSession(String authRef, Status status, Instant expiresAt, String externalAccountKey,
                          Presentation presentation, ChannelError error) {

    public AuthSession {
        if (authRef == null || authRef.isBlank()) {
            throw new IllegalArgumentException("authRef is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        externalAccountKey = externalAccountKey == null || externalAccountKey.isBlank()
            ? null : externalAccountKey;
    }

    public enum Status {
        PENDING, COMPLETED, FAILED, CANCELLED, EXPIRED
    }

    /** 展示方式：二维码内容或跳转地址。 */
    public sealed interface Presentation permits AuthSession.Presentation.Qr, AuthSession.Presentation.Redirect {
        record Qr(String value) implements Presentation {
        }

        record Redirect(String url) implements Presentation {
        }
    }
}
