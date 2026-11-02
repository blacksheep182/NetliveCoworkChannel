package com.netlivecowork.channel.contract;

import java.util.List;
import java.util.Set;

/**
 * 入口操作（L1 → L2）。入口 operation 与规范 command 是两个对象：targetRef/taskRef
 * 在首次受理时进入规范 context，不由调用者追加字段（data-contracts.md §4.1）。
 */
public sealed interface ChannelOperation permits ChannelOperation.InputSubmit, ChannelOperation.SessionList,
    ChannelOperation.SessionSelect, ChannelOperation.SessionGet, ChannelOperation.TaskGet,
    ChannelOperation.TaskCancel, ChannelOperation.InteractionRespond, ChannelOperation.SubscriptionCreate,
    ChannelOperation.SubscriptionRemove, ChannelOperation.SelectionGet, ChannelOperation.WorkspaceList, ChannelOperation.WorkspaceSelect {

    String type();

    /** 普通输入。interpretation 只说明输入语义，不授权后端切换目标。 */
    record InputSubmit(String targetRef, Interpretation interpretation, List<ContentPart> content,
                       String replyToMessageRef, Long expectedBindingRevision) implements ChannelOperation {
        public InputSubmit(String targetRef, Interpretation interpretation, List<ContentPart> content, String replyToMessageRef) {
            this(targetRef, interpretation, content, replyToMessageRef, null);
        }
        public InputSubmit {
            if (expectedBindingRevision != null && expectedBindingRevision < 0) throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "invalid selection revision");
            targetRef = targetRef == null || targetRef.isBlank() ? null : targetRef;
            interpretation = interpretation == null ? Interpretation.LITERAL : interpretation;
            if (content == null || content.isEmpty()) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "input.submit requires content");
            }
            content = List.copyOf(content);
            replyToMessageRef = replyToMessageRef == null || replyToMessageRef.isBlank()
                ? null : replyToMessageRef;
        }

        @Override
        public String type() {
            return "input.submit";
        }
    }

    record SessionList(String agentRef, PageRequest page, String sessionWorkspaceRef) implements ChannelOperation {
        public SessionList(String agentRef, PageRequest page) { this(agentRef, page, null); }
        public SessionList {
            agentRef = agentRef == null || agentRef.isBlank() ? null : agentRef;
            page = page == null ? PageRequest.first() : page;
        }

        @Override
        public String type() {
            return "session.list";
        }
    }

    record SelectionGet() implements ChannelOperation {
        public String type() { return "selection.get"; }
    }

    record WorkspaceList() implements ChannelOperation {
        public String type() { return "workspace.list"; }
    }
    record WorkspaceSelect(String sessionWorkspaceRef, long expectedBindingRevision) implements ChannelOperation {
        public WorkspaceSelect {
            Producer.requireText(sessionWorkspaceRef, "sessionWorkspaceRef");
            if (expectedBindingRevision < 0) throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "invalid selection revision");
        }
        public String type() { return "workspace.select"; }
    }

    record SessionSelect(String targetRef, long expectedBindingRevision) implements ChannelOperation {
        public SessionSelect {
            Producer.requireText(targetRef, "session.select.targetRef");
            if (expectedBindingRevision < 0) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "expectedBindingRevision must be >= 0");
            }
        }

        @Override
        public String type() {
            return "session.select";
        }
    }

    record SessionGet(String targetRef) implements ChannelOperation {
        public SessionGet {
            Producer.requireText(targetRef, "session.get.targetRef");
        }

        @Override
        public String type() {
            return "session.get";
        }
    }

    record TaskGet(String taskRef) implements ChannelOperation {
        public TaskGet {
            Producer.requireText(taskRef, "task.get.taskRef");
        }

        @Override
        public String type() {
            return "task.get";
        }
    }

    record TaskCancel(String taskRef, String reason) implements ChannelOperation {
        public TaskCancel {
            Producer.requireText(taskRef, "task.cancel.taskRef");
            reason = reason == null || reason.isBlank() ? null : reason;
        }

        @Override
        public String type() {
            return "task.cancel";
        }
    }

    record InteractionRespond(InteractionRespondPayload payload) implements ChannelOperation {
        public InteractionRespond {
            if (payload == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "interaction.respond requires payload");
            }
        }

        @Override
        public String type() {
            return "interaction.respond";
        }
    }

    record SubscriptionCreate(SubscriptionResource resource, Set<EventType> eventTypes, String destinationRef,
                              String resumeCursor) implements ChannelOperation {
        public SubscriptionCreate {
            if (resource == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "subscription.create requires resource");
            }
            if (eventTypes == null || eventTypes.isEmpty()) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "subscription.create requires eventTypes");
            }
            eventTypes = Set.copyOf(eventTypes);
            Producer.requireText(destinationRef, "subscription.create.destinationRef");
            resumeCursor = resumeCursor == null || resumeCursor.isBlank() ? null : resumeCursor;
        }

        @Override
        public String type() {
            return "subscription.create";
        }
    }

    record SubscriptionRemove(String subscriptionRef) implements ChannelOperation {
        public SubscriptionRemove {
            Producer.requireText(subscriptionRef, "subscription.remove.subscriptionRef");
        }

        @Override
        public String type() {
            return "subscription.remove";
        }
    }

    enum Interpretation {
        LITERAL("literal"), COMMAND_AWARE("commandAware");

        private final String wire;

        Interpretation(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }
}
