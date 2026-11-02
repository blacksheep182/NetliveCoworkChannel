package com.netlivecowork.channel.contract;

/**
 * submit/lookup 的入口响应投影，按 requestId 关联；不是第四类公共包。受理前拒绝或查询不到记录时
 * 不得伪造 operationRef、flowId 或 ReceiptPacket（data-contracts.md §5）。
 */
public sealed interface ChannelResponse permits ChannelResponse.Completed, ChannelResponse.Accepted,
    ChannelResponse.Rejected, ChannelResponse.Unknown {

    String requestId();

    record Completed(String requestId, OperationResult result) implements ChannelResponse {
        public Completed {
            Producer.requireText(requestId, "requestId");
            if (result == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "completed requires result");
            }
        }
    }

    record Accepted(String requestId, String operationRef, String taskRef,
                    ReceiptPayload.AcceptedBy acceptedBy) implements ChannelResponse {
        public Accepted(String requestId, String operationRef, String taskRef) {
            this(requestId, operationRef, taskRef, ReceiptPayload.AcceptedBy.CHANNEL);
        }
        public Accepted {
            Producer.requireText(requestId, "requestId");
            Producer.requireText(operationRef, "operationRef");
            taskRef = taskRef == null || taskRef.isBlank() ? null : taskRef;
            acceptedBy = acceptedBy == null ? ReceiptPayload.AcceptedBy.CHANNEL : acceptedBy;
        }
    }

    record Rejected(String requestId, ChannelError error) implements ChannelResponse {
        public Rejected {
            Producer.requireText(requestId, "requestId");
            if (error == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "rejected requires error");
            }
        }
    }

    record Unknown(String requestId, String operationRef, ChannelError error) implements ChannelResponse {
        public Unknown {
            Producer.requireText(requestId, "requestId");
            operationRef = operationRef == null || operationRef.isBlank() ? null : operationRef;
            if (error == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "unknown requires error");
            }
        }
    }

    static ChannelResponse rejected(String requestId, ChannelErrorCode code, String message) {
        return new Rejected(requestId, ChannelError.of(code, message));
    }
}
