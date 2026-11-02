package com.netlivecowork.channel.contract;

import java.time.Instant;

/**
 * L3 持久固定的后端人工事项：实际后端事项、版本与题目映射。L3 不虚构 revision（INV-27、59）。
 */
public record BackendInteraction(String backendInteractionId, long revision, Instant expiresAt,
                                 InteractionPrompt prompt, String backendTaskId) {
    public BackendInteraction(String id,long revision,Instant expiresAt,InteractionPrompt prompt) { this(id,revision,expiresAt,prompt,null); }
    public BackendInteraction {
        Producer.requireText(backendInteractionId, "backendInteractionId");
        if (revision < 1) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "revision must be >= 1");
        }
        if (prompt == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "prompt is required");
        }
    }
}
