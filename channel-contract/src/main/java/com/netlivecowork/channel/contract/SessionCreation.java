package com.netlivecowork.channel.contract;
/** Internal result: physical session identity stops in L3. */
public final class SessionCreation {
    private SessionCreation() {}
    public record Created(SessionSummary session, TargetOwnership ownership) {}
    /** Positive evidence that the original creation was not dispatched or was rejected before execution. */
    public static final class NotStarted extends ChannelContractException {
        public NotStarted(ChannelErrorCode code,String message) { super(code,message); }
    }
}
