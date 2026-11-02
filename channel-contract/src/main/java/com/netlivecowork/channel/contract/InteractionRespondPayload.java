package com.netlivecowork.channel.contract;

/** 人工交互响应载荷：原事项引用、预期事项版本与响应内容。 */
public record InteractionRespondPayload(String interactionRef, long expectedRevision,
                                        InteractionResponse response) {
    public InteractionRespondPayload {
        Producer.requireText(interactionRef, "interactionRef");
        if (expectedRevision < 1) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "expectedRevision must be >= 1");
        }
        if (response == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "response is required");
        }
    }
}
