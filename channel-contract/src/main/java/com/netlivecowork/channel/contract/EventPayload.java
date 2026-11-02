package com.netlivecowork.channel.contract;

/**
 * 规范事件载荷（L2 创建，只含内部引用）。source、occurredAt、业务版本与顺序来自 L3 已验证事实。
 */
public sealed interface EventPayload permits EventPayload.MessageReady, EventPayload.TaskStarted,
    EventPayload.TaskProgress, EventPayload.TaskWaiting, EventPayload.TaskCompleted, EventPayload.TaskFailed,
    EventPayload.TaskCancelled, EventPayload.TaskArtifact, EventPayload.InteractionRequired,
    EventPayload.InteractionResolved, EventPayload.InteractionExpired, EventPayload.InteractionCancelled {

    /** 一条完整可展示消息。不等于任务终态，也不等于平台已受理。 */
    record MessageReady(ChannelMessage message) implements EventPayload {
        public MessageReady {
            if (message == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "message.ready requires message");
            }
        }
    }

    record TaskStarted(long revision) implements EventPayload {
    }

    record TaskProgress(long revision, String summary, Double progress) implements EventPayload {
        public TaskProgress {
            summary = summary == null || summary.isBlank() ? null : summary;
            if (progress != null && (progress < 0d || progress > 1d)) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "progress must be within 0..1");
            }
        }
    }

    record TaskWaiting(long revision, String reason) implements EventPayload {
    }

    /** 终态。resultMessageRefs 引用结果消息，不重复展示相同内容（INV-26）。 */
    record TaskCompleted(long revision, java.util.List<String> resultMessageRefs, java.util.List<String> fileRefs)
        implements EventPayload {
        public TaskCompleted {
            resultMessageRefs = resultMessageRefs == null ? java.util.List.of() : java.util.List.copyOf(resultMessageRefs);
            fileRefs = fileRefs == null ? java.util.List.of() : java.util.List.copyOf(fileRefs);
        }
    }

    record TaskFailed(long revision, ChannelError error, java.util.List<String> resultMessageRefs)
        implements EventPayload {
        public TaskFailed {
            if (error == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "task.failed requires error");
            }
            resultMessageRefs = resultMessageRefs == null ? java.util.List.of() : java.util.List.copyOf(resultMessageRefs);
        }
    }

    record TaskCancelled(long revision, String reason) implements EventPayload {
    }

    /** 不可变产物事实：按源事件身份及 taskRef+fileRef 幂等登记，无任务 revision。 */
    record TaskArtifact(java.util.List<String> fileRefs) implements EventPayload {
        public TaskArtifact {
            if (fileRefs == null || fileRefs.isEmpty()) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "task.artifact requires fileRefs");
            }
            fileRefs = java.util.List.copyOf(fileRefs);
        }
    }

    record InteractionRequired(InteractionRequiredPayload payload) implements EventPayload {
        public InteractionRequired {
            if (payload == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "interaction.required requires payload");
            }
        }
    }

    record InteractionResolved(String interactionRef, long revision, Outcome outcome) implements EventPayload {
        public InteractionResolved {
            Producer.requireText(interactionRef, "interactionRef");
            if (outcome == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "outcome is required");
            }
        }
    }

    record InteractionExpired(String interactionRef, long revision, String reason) implements EventPayload {
        public InteractionExpired {
            Producer.requireText(interactionRef, "interactionRef");
        }
    }

    record InteractionCancelled(String interactionRef, long revision, String reason) implements EventPayload {
        public InteractionCancelled {
            Producer.requireText(interactionRef, "interactionRef");
        }
    }

    enum Outcome {
        APPROVED("approved"), REJECTED("rejected"), ANSWERED("answered");

        private final String wire;

        Outcome(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static Outcome parse(String wire) {
            for (Outcome outcome : values()) {
                if (outcome.wire.equals(wire)) {
                    return outcome;
                }
            }
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "unknown outcome: " + wire);
        }
    }
}
