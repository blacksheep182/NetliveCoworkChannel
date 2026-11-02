package com.netlivecowork.channel.contract;

/** 本次投递尝试的超时。平台网络操作在数据库事务外执行（INV-20）。 */
public record DeliveryAttempt(long timeoutMs) {
    public DeliveryAttempt {
        if (timeoutMs <= 0) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "timeoutMs must be > 0");
        }
    }

    public static DeliveryAttempt withTimeout(long timeoutMs) {
        return new DeliveryAttempt(timeoutMs);
    }
}
