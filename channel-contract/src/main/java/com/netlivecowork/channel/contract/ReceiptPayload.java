package com.netlivecowork.channel.contract;

/** 回执载荷。accepted 区分 channel/agent，producer 记录真实创建组件（INV-22）。 */
public sealed interface ReceiptPayload permits ReceiptPayload.Accepted, ReceiptPayload.Completed,
    ReceiptPayload.Rejected, ReceiptPayload.Unknown {

    record Accepted(AcceptedBy acceptedBy) implements ReceiptPayload {
        public Accepted {
            if (acceptedBy == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "acceptedBy is required");
            }
        }
    }

    /** 操作事实已完成。completed 表示本次操作完成，不表示任务完成（INV-25）。 */
    record Completed(OperationResult result) implements ReceiptPayload {
        public Completed {
            if (result == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "completed requires result");
            }
        }
    }

    record Rejected(ChannelError error) implements ReceiptPayload {
        public Rejected {
            if (error == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "rejected requires error");
            }
        }
    }

    /** 结果不确定：保留 unknown，不换新 ID 重发（INV-23）。 */
    record Unknown(ChannelError error) implements ReceiptPayload {
        public Unknown {
            if (error == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "unknown requires error");
            }
        }
    }

    enum AcceptedBy {
        CHANNEL, AGENT
    }
}
