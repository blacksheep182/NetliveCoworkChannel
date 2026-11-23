package com.netlivecowork.channel.contract;

import java.util.List;

/**
 * AgentEvent 的类型化载荷（L3 解析验证产物）。保留后端对象 ID，不含平台目的地或内部引用。
 * 未定义类型与不符合 schema 的内容一律拒绝（data-contracts.md §6）。
 */
public sealed interface AgentEventPayload permits AgentEventPayload.MessageReady, AgentEventPayload.TaskStarted,
    AgentEventPayload.TaskProgress, AgentEventPayload.TaskWaiting, AgentEventPayload.TaskCompleted,
    AgentEventPayload.TaskFailed, AgentEventPayload.TaskCancelled, AgentEventPayload.TaskArtifact,
    AgentEventPayload.InteractionRequired, AgentEventPayload.InteractionResolved,
    AgentEventPayload.InteractionExpired, AgentEventPayload.InteractionCancelled {

    /** 后端组装的完整可展示消息；backendMessageId 是后端消息身份。 */
    record MessageReady(List<NormalizedContentPart> content, String backendMessageId,
                        String replyToBackendMessageId) implements AgentEventPayload {
        public MessageReady {
            if (content == null || content.isEmpty()) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "message.ready requires content");
            }
            content = List.copyOf(content);
            Producer.requireText(backendMessageId, "message.ready.backendMessageId");
            replyToBackendMessageId = replyToBackendMessageId == null || replyToBackendMessageId.isBlank()
                ? null : replyToBackendMessageId;
        }
    }

    record TaskStarted(String backendTaskId, long revision) implements AgentEventPayload {
        public TaskStarted {
            Producer.requireText(backendTaskId, "task.started.backendTaskId");
            if (revision < 1) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "task.started requires revision >= 1");
            }
        }
    }

    record TaskProgress(String backendTaskId, long revision, String summary, Double progress)
        implements AgentEventPayload {
        public TaskProgress {
            Producer.requireText(backendTaskId, "task.progress.backendTaskId");
            if (revision < 1) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "task.progress requires revision >= 1");
            }
            if (progress != null && (progress < 0d || progress > 1d)) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "task.progress.progress must be within 0..1");
            }
        }
    }

    record TaskWaiting(String backendTaskId, long revision, String reason) implements AgentEventPayload {
        public TaskWaiting {
            Producer.requireText(backendTaskId, "task.waiting.backendTaskId");
            if (revision < 1) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "task.waiting requires revision >= 1");
            }
        }
    }

    record TaskCompleted(String backendTaskId, long revision, List<String> backendResultMessageIds,
                         List<BackendFileGrant> files, EventPayload.TaskCompletion completion) implements AgentEventPayload {
        public TaskCompleted(String backendTaskId, long revision, List<String> backendResultMessageIds,
                             List<BackendFileGrant> files) {
            this(backendTaskId, revision, backendResultMessageIds, files, null);
        }
        public TaskCompleted {
            Producer.requireText(backendTaskId, "task.completed.backendTaskId");
            if (revision < 1) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "task.completed requires revision >= 1");
            }
            backendResultMessageIds = backendResultMessageIds == null
                ? List.of() : List.copyOf(backendResultMessageIds);
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    record TaskFailed(String backendTaskId, long revision, ChannelError error,
                      List<String> backendResultMessageIds, EventPayload.TaskCompletion completion) implements AgentEventPayload {
        public TaskFailed(String backendTaskId, long revision, ChannelError error, List<String> backendResultMessageIds) {
            this(backendTaskId, revision, error, backendResultMessageIds, null);
        }
        public TaskFailed {
            Producer.requireText(backendTaskId, "task.failed.backendTaskId");
            if (revision < 1) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "task.failed requires revision >= 1");
            }
            if (error == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "task.failed requires error");
            }
            backendResultMessageIds = backendResultMessageIds == null
                ? List.of() : List.copyOf(backendResultMessageIds);
        }
    }

    record TaskCancelled(String backendTaskId, long revision, String reason,
                         EventPayload.TaskCompletion completion) implements AgentEventPayload {
        public TaskCancelled(String backendTaskId, long revision, String reason) {
            this(backendTaskId, revision, reason, null);
        }
        public TaskCancelled {
            Producer.requireText(backendTaskId, "task.cancelled.backendTaskId");
            if (revision < 1) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "task.cancelled requires revision >= 1");
            }
        }
    }

    record TaskArtifact(String backendTaskId, List<BackendFileGrant> files) implements AgentEventPayload {
        public TaskArtifact {
            Producer.requireText(backendTaskId, "task.artifact.backendTaskId");
            if (files == null || files.isEmpty()) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "task.artifact requires files");
            }
            files = List.copyOf(files);
        }
    }

    record InteractionRequired(BackendInteraction interaction) implements AgentEventPayload {
        public InteractionRequired {
            if (interaction == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "interaction.required requires interaction");
            }
        }
    }

    record InteractionResolved(BackendInteractionRef ref, EventPayload.Outcome outcome) implements AgentEventPayload {
        public InteractionResolved {
            if (ref == null || outcome == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "interaction.resolved requires ref and outcome");
            }
        }
    }

    record InteractionExpired(BackendInteractionRef ref, String reason) implements AgentEventPayload {
        public InteractionExpired {
            if (ref == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "interaction.expired requires ref");
            }
        }
    }

    record InteractionCancelled(BackendInteractionRef ref, String reason) implements AgentEventPayload {
        public InteractionCancelled {
            if (ref == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "interaction.cancelled requires ref");
            }
        }
    }
}
