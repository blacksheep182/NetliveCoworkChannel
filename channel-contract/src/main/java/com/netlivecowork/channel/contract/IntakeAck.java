package com.netlivecowork.channel.contract;

/** L3 → L2 转交的持久接管确认。accepted=true 表示已持久保存（或已保存待关联记录）。 */
public record IntakeAck(boolean accepted, ChannelError error) {
    public IntakeAck {
        if (!accepted && error == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "rejected ack requires error");
        }
    }

    public static IntakeAck ok() {
        return new IntakeAck(true, null);
    }

    public static IntakeAck rejected(ChannelError error) {
        return new IntakeAck(false, error);
    }
}
