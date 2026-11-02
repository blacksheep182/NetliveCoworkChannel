package com.netlivecowork.channel.contract.adapter;

/** 连接策略。连接意图、重连退避与健康监督归 L2，协议动作由 L1 执行（INV-48）。 */
public record ConnectionPolicy(long connectTimeoutMs, long probeIntervalMs, long probeTimeoutMs,
                               Reconnect reconnect) {

    public ConnectionPolicy {
        if (connectTimeoutMs <= 0 || probeIntervalMs <= 0 || probeTimeoutMs <= 0) {
            throw new IllegalArgumentException("connection timeouts must be positive");
        }
        reconnect = reconnect == null ? Reconnect.defaults() : reconnect;
    }

    public static ConnectionPolicy defaults() {
        return new ConnectionPolicy(10_000, 30_000, 5_000, Reconnect.defaults());
    }

    /** enabled=false 表示不自动重连；maxAttempts=null 表示无次数上限，仍受停用与认证失效约束。 */
    public record Reconnect(boolean enabled, long initialDelayMs, long maxDelayMs, Integer maxAttempts) {
        public Reconnect {
            if (initialDelayMs <= 0 || maxDelayMs < initialDelayMs) {
                throw new IllegalArgumentException("invalid reconnect backoff bounds");
            }
            if (maxAttempts != null && maxAttempts < 0) {
                throw new IllegalArgumentException("maxAttempts must be >= 0");
            }
        }

        public static Reconnect defaults() {
            return new Reconnect(true, 1_000, 60_000, null);
        }

        /** 指数退避并封顶，attempt 从 1 开始。 */
        public long delayFor(int attempt) {
            long delay = initialDelayMs;
            for (int i = 1; i < attempt && delay < maxDelayMs; i++) {
                delay = Math.min(maxDelayMs, delay * 2);
            }
            return Math.min(maxDelayMs, delay);
        }
    }
}
