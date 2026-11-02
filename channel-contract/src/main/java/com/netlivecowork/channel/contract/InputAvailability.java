package com.netlivecowork.channel.contract;

import java.util.Set;

/** Backend-derived ordinary input action; never an authorization grant. */
public record InputAvailability(boolean allowed, String mode, String reason) {
    public InputAvailability {
        if (!Set.of("new_task", "continue", "deferred", "blocked").contains(mode == null ? "" : mode)
            || !Set.of("ready", "user_turn", "children_pending", "running", "interaction_required",
                       "resume_required", "terminated", "unknown").contains(reason == null ? "" : reason)
            || allowed == "blocked".equals(mode)
            || (allowed && !Set.of("ready", "user_turn", "children_pending").contains(reason))
            || (!allowed && Set.of("ready", "user_turn", "children_pending").contains(reason))
            || ("new_task".equals(mode) && !"ready".equals(reason))
            || ("deferred".equals(mode) && !"children_pending".equals(reason))) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "invalid input availability");
        }
    }
}
