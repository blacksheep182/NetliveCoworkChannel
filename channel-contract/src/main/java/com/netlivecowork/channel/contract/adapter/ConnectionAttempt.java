package com.netlivecowork.channel.contract.adapter;

/** 一次连接尝试：代次由 L2 分配，单调递增并持久化（INV-49）。 */
public record ConnectionAttempt(long generation, long timeoutMs) {
    public ConnectionAttempt {
        if (generation < 1) {
            throw new IllegalArgumentException("generation must be >= 1");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be > 0");
        }
    }
}
