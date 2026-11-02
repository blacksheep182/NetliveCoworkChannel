package com.netlivecowork.channel.contract;

/** 交互事项载荷：L2 签发 interactionRef，沿用可信 revision 与后端明确期限。 */
public record InteractionRequiredPayload(String interactionRef, long revision, java.time.Instant expiresAt,
                                         InteractionPrompt prompt) {
    public InteractionRequiredPayload {
        Producer.requireText(interactionRef, "interactionRef");
        if (revision < 1) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "revision must be >= 1");
        }
        if (prompt == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "prompt is required");
        }
    }
}
