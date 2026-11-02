package com.netlivecowork.channel.contract;

/** 会话观察登记结果。supported=false 时后端不具备该能力，不得宣称订阅生效（INV-65）。 */
public record SessionWatch(String watchRef, String startBoundary, boolean supported, ChannelError error) {
    public SessionWatch {
        watchRef = watchRef == null || watchRef.isBlank() ? null : watchRef;
        startBoundary = startBoundary == null || startBoundary.isBlank() ? null : startBoundary;
        if (supported && watchRef == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "supported watch requires watchRef");
        }
    }

    public static SessionWatch unsupported(ChannelError error) {
        return new SessionWatch(null, null, false, error);
    }

    public static SessionWatch active(String watchRef, String startBoundary) {
        return new SessionWatch(watchRef, startBoundary, true, null);
    }
}
