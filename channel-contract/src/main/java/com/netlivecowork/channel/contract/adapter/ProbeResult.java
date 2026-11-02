package com.netlivecowork.channel.contract.adapter;

import com.netlivecowork.channel.contract.ChannelError;
import com.netlivecowork.channel.contract.ChannelErrorCode;

/** 探测结果。unhealthy 表示可确认不可用；unknown 不能当作健康。 */
public record ProbeResult(Status status, ChannelError error) {

    public ProbeResult {
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
    }

    public enum Status {
        HEALTHY, UNHEALTHY, UNKNOWN
    }

    public static ProbeResult healthy() {
        return new ProbeResult(Status.HEALTHY, null);
    }

    public static ProbeResult unhealthy(ChannelError error) {
        return new ProbeResult(Status.UNHEALTHY, error);
    }

    public static ProbeResult unknown(String message) {
        return new ProbeResult(Status.UNKNOWN, ChannelErrorCode.UNAVAILABLE.error(message));
    }
}
