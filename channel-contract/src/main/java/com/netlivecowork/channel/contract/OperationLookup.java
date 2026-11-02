package com.netlivecowork.channel.contract;

/**
 * 原操作核对结果：查询“本次提交是否生效”，不重新寻址或执行（l2-application.md §2.3）。
 * 查询不到不能自动证明未执行（INV-23）。
 */
public record OperationLookup(String operationRef, DispatchStatus status, ReceiptPacket receipt,
                              ChannelError error) {
    public OperationLookup {
        Producer.requireText(operationRef, "operationRef");
        if (status == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "status is required");
        }
    }
}
