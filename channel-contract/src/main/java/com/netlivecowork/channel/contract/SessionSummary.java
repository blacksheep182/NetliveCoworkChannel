package com.netlivecowork.channel.contract;

import java.util.List;

/** 会话列表项：只含获准展示的逻辑引用与名称，不含后端地址（INV-11）。 */
public record SessionSummary(String targetRef, String label, String agentRef, boolean available,
                             SessionExecutionState execution, SessionWorkspace workspace) {
    public SessionSummary(String targetRef, String label, String agentRef, boolean available, SessionExecutionState execution) {
        this(targetRef, label, agentRef, available, execution, null);
    }
    public SessionSummary(String targetRef, String label, String agentRef, boolean available) {
        this(targetRef, label, agentRef, available, null);
    }
    public SessionSummary {
        Producer.requireText(targetRef, "session.targetRef");
        Producer.requireText(label, "session.label");
        agentRef = agentRef == null || agentRef.isBlank() ? null : agentRef;
    }

    public static SessionSummary of(String targetRef, String label) {
        return new SessionSummary(targetRef, label, null, true);
    }

    /** 分页结果。cursor 由 L2 签发，绑定完整资源与权限范围。 */
    public record Page(List<SessionSummary> items, String nextCursor) {
        public Page {
            items = items == null ? List.of() : List.copyOf(items);
            nextCursor = nextCursor == null || nextCursor.isBlank() ? null : nextCursor;
        }

        public static Page empty() {
            return new Page(List.of(), null);
        }
    }
}
