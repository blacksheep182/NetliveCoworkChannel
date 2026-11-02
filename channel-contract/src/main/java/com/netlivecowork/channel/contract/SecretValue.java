package com.netlivecowork.channel.contract;

import java.time.Instant;

/** 受限凭据值。值不进入业务包、日志或错误响应（INV-52）。 */
public record SecretValue(String value, long revision, Instant expiresAt) {
    public SecretValue {
        Producer.requireText(value, "secret.value");
    }

    @Override
    public String toString() {
        return "SecretValue[revision=" + revision + ", expiresAt=" + expiresAt + "]";
    }
}
