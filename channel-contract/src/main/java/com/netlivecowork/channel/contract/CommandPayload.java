package com.netlivecowork.channel.contract;

import java.util.Set;

/**
 * 规范指令载荷（L2 创建）。入口 operation 与规范 command 是两个对象：targetRef/taskRef 从入口参数
 * 移入规范 context，群当前默认目标只在首次受理时解析（data-contracts.md §4.2）。
 */
public sealed interface CommandPayload permits CommandPayload.SessionCreate, CommandPayload.InputSubmit, CommandPayload.SessionList,
    CommandPayload.SessionSelect, CommandPayload.SessionGet, CommandPayload.SessionResume, CommandPayload.TaskGet, CommandPayload.TaskCancel,
    CommandPayload.InteractionRespond, CommandPayload.SubscriptionCreate, CommandPayload.SubscriptionRemove, CommandPayload.SelectionGet, CommandPayload.WorkspaceList, CommandPayload.WorkspaceSelect {

    /** messageRef 由 L2 受理时签发；已解析引用与 replyToMessageRef 复验后保留。 */
    record InputSubmit(ChannelMessage message, ChannelOperation.Interpretation interpretation)
        implements CommandPayload {
        public InputSubmit {
            if (message == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "input.submit requires message");
            }
            interpretation = interpretation == null ? ChannelOperation.Interpretation.LITERAL : interpretation;
        }
    }

    record SessionList(String agentRef, PageRequest page, String sessionWorkspaceRef) implements CommandPayload {
        public SessionList(String agentRef, PageRequest page) { this(agentRef,page,null); }
        public SessionList(PageRequest page) {
            this(null, page);
        }

        public SessionList {
            page = page == null ? PageRequest.first() : page;
        }
    }

    /** 由 L2 执行：只改范围绑定，不改目标身份。 */
    record SessionSelect(String scopeRef, long expectedBindingRevision) implements CommandPayload {
        public SessionSelect {
            Producer.requireText(scopeRef, "session.select.scopeRef");
        }
    }

    record SessionCreate(String sessionWorkspaceRef,String userPrompt,long expectedBindingRevision) implements CommandPayload {}

    record SelectionGet() implements CommandPayload {}
    record WorkspaceList() implements CommandPayload {}
    record WorkspaceSelect(String scopeRef, String sessionWorkspaceRef, long expectedBindingRevision) implements CommandPayload {}

    record SessionGet() implements CommandPayload {
    }

    record SessionResume() implements CommandPayload {
    }

    record TaskGet() implements CommandPayload {
    }

    /** 仅在后端支持真实取消时开放；受理不等于已取消（INV-24）。 */
    record TaskCancel(String reason) implements CommandPayload {
        public TaskCancel {
            reason = reason == null || reason.isBlank() ? null : reason;
        }
    }

    record InteractionRespond(InteractionRespondPayload payload) implements CommandPayload {
        public InteractionRespond {
            if (payload == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "interaction.respond requires payload");
            }
        }
    }

    /** 由 L2 执行：建立有起点和版本的持久订阅，不把平台目的地发给 Agent。 */
    record SubscriptionCreate(SubscriptionResource resource, Set<EventType> eventTypes, String destinationRef,
                              long destinationRevision, String resumeCursor) implements CommandPayload {
        public SubscriptionCreate {
            if (resource == null || eventTypes == null || eventTypes.isEmpty()) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "subscription.create requires resource and eventTypes");
            }
            eventTypes = Set.copyOf(eventTypes);
            Producer.requireText(destinationRef, "subscription.create.destinationRef");
            resumeCursor = resumeCursor == null || resumeCursor.isBlank() ? null : resumeCursor;
        }
    }

    record SubscriptionRemove(String subscriptionRef) implements CommandPayload {
        public SubscriptionRemove {
            Producer.requireText(subscriptionRef, "subscription.remove.subscriptionRef");
        }
    }
}
