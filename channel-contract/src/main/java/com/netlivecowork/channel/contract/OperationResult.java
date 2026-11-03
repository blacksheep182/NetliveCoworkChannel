package com.netlivecowork.channel.contract;

import java.util.List;

/**
 * 操作结果。普通提交的同步结果不携带最终答案（INV-25）；interaction.respond 只在后端确认本次
 * 响应生效后表达 applied（INV-61）。
 */
public sealed interface OperationResult permits OperationResult.SessionCreate, OperationResult.InputSubmit, OperationResult.SessionList,
    OperationResult.SessionGet, OperationResult.SessionResume, OperationResult.SessionSelect, OperationResult.TaskGet, OperationResult.TaskCancel,
    OperationResult.SelectionGet, OperationResult.WorkspaceList, OperationResult.WorkspaceSelect, OperationResult.InteractionRespond, OperationResult.SubscriptionCreate, OperationResult.SubscriptionRemove {

    CommandType type();

    record SessionCreate(SessionSummary session, NavigationSelection selection) implements OperationResult {
        public CommandType type() { return CommandType.SESSION_CREATE; }
    }

    record InputSubmit(String taskRef) implements OperationResult {
        public InputSubmit {
            taskRef = taskRef == null || taskRef.isBlank() ? null : taskRef;
        }

        @Override
        public CommandType type() {
            return CommandType.INPUT_SUBMIT;
        }
    }

    record SessionList(List<SessionSummary> items, String nextCursor) implements OperationResult {
        public SessionList {
            items = items == null ? List.of() : List.copyOf(items);
            nextCursor = nextCursor == null || nextCursor.isBlank() ? null : nextCursor;
        }

        @Override
        public CommandType type() {
            return CommandType.SESSION_LIST;
        }
    }

    record SelectionGet(NavigationSelection selection) implements OperationResult {
        public CommandType type() { return CommandType.SELECTION_GET; }
    }

    record WorkspaceList(List<SessionWorkspace> items, NavigationSelection selection) implements OperationResult {
        public WorkspaceList { items = List.copyOf(items); }
        public CommandType type() { return CommandType.WORKSPACE_LIST; }
    }
    record WorkspaceSelect(NavigationSelection selection) implements OperationResult {
        public CommandType type() { return CommandType.WORKSPACE_SELECT; }
    }

    record SessionGet(SessionSummary session, List<EventPacket> interactions) implements OperationResult {
        public SessionGet(SessionSummary session) { this(session,null); }
        public SessionGet {
            if (session == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "session.get requires session");
            }
        }

        @Override
        public CommandType type() {
            return CommandType.SESSION_GET;
        }
    }

    record SessionResume(String targetRef) implements OperationResult {
        public SessionResume { Producer.requireText(targetRef, "session.resume.targetRef"); }
        public CommandType type() { return CommandType.SESSION_RESUME; }
    }

    record SessionSelect(String scopeRef, String targetRef, long revision, String sessionWorkspaceRef) implements OperationResult {
        public SessionSelect(String scopeRef, String targetRef, long revision) { this(scopeRef,targetRef,revision,null); }
        @Override
        public CommandType type() {
            return CommandType.SESSION_SELECT;
        }
    }

    record TaskGet(TaskSnapshot task) implements OperationResult {
        public TaskGet {
            if (task == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "task.get requires task");
            }
        }

        @Override
        public CommandType type() {
            return CommandType.TASK_GET;
        }
    }

    record TaskCancel(String taskRef, TaskStatus status) implements OperationResult {
        public TaskCancel {
            Producer.requireText(taskRef, "taskRef");
            status = status == null ? TaskStatus.UNKNOWN : status;
        }

        @Override
        public CommandType type() {
            return CommandType.TASK_CANCEL;
        }
    }

    record InteractionRespond(String interactionRef) implements OperationResult {
        public InteractionRespond {
            Producer.requireText(interactionRef, "interactionRef");
        }

        @Override
        public CommandType type() {
            return CommandType.INTERACTION_RESPOND;
        }
    }

    record SubscriptionCreate(String subscriptionRef) implements OperationResult {
        public SubscriptionCreate {
            Producer.requireText(subscriptionRef, "subscriptionRef");
        }

        @Override
        public CommandType type() {
            return CommandType.SUBSCRIPTION_CREATE;
        }
    }

    record SubscriptionRemove(String subscriptionRef) implements OperationResult {
        public SubscriptionRemove {
            Producer.requireText(subscriptionRef, "subscriptionRef");
        }

        @Override
        public CommandType type() {
            return CommandType.SUBSCRIPTION_REMOVE;
        }
    }
}
