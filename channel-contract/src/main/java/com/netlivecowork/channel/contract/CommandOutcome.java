package com.netlivecowork.channel.contract;

/** 一次指令派发的结果。receipt 只在取得确切回执时存在；error 不得暴露内部地址或凭证。 */
public record CommandOutcome(DispatchStatus status, String dispatchRef, ReceiptPacket receipt,
                             ChannelError error) {
    public CommandOutcome {
        if (status == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "status is required");
        }
        dispatchRef = dispatchRef == null || dispatchRef.isBlank() ? null : dispatchRef;
    }

    public static CommandOutcome notSent(String dispatchRef, ChannelError error) {
        return new CommandOutcome(DispatchStatus.NOT_SENT, dispatchRef, null, error);
    }

    public static CommandOutcome unknown(String dispatchRef, ChannelError error) {
        return new CommandOutcome(DispatchStatus.OUTCOME_UNKNOWN, dispatchRef, null, error);
    }

    public static CommandOutcome accepted(String dispatchRef, ReceiptPacket receipt) {
        return new CommandOutcome(DispatchStatus.AGENT_ACCEPTED, dispatchRef, receipt, null);
    }
}
