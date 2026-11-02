package com.netlivecowork.channel.infra;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * 稳定标识生成：前缀 + 时间可读段 + 随机段。标识只是引用，不授予访问权（INV-08）。
 * 按时间排序便于排查，但时间本身不代替可靠序号（INV-27）。
 */
public final class Ids {

    private static final String ALPHABET = "0123456789abcdefghjkmnpqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String next(String prefix) {
        return prefix + "-" + timestamp(Instant.now()) + "-" + random(10);
    }

    public static String next(String prefix, Instant at) {
        return prefix + "-" + timestamp(at) + "-" + random(10);
    }

    /** 时间段的 base32（毫秒），至少 9 字符。 */
    private static String timestamp(Instant instant) {
        long value = instant.toEpochMilli();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            out.append(ALPHABET.charAt((int) (value & 31)));
            value >>= 5;
        }
        return out.reverse().toString();
    }

    public static String random(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    /** 供不支持时间序的场合使用：只要求唯一。 */
    public static String opaque(String prefix) {
        return prefix + "-" + random(20);
    }
}
