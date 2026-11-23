package com.netlivecowork.channel.contract;

import java.util.List;

/**
 * 一个固定事件发往一个确定目的地的包装。destinationRevision 表达目的地身份版本（INV-13）；
 * 重试参数不变，多目的地各持 deliveryId（INV-37）。
 */
public record ChannelDelivery(String version, String deliveryId, EventPacket packet, Routing routing,
                              Presentation presentation, List<Action> actions) {

    public ChannelDelivery {
        Packet.requireVersionOne(version);
        Producer.requireText(deliveryId, "deliveryId");
        if (packet == null || routing == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "delivery requires packet and routing");
        }
        if (presentation == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "delivery requires presentation.sourceLabel");
        }
        actions = actions == null ? List.of() : List.copyOf(actions);
        var status = packet.type().terminalTaskStatus();
        if (presentation.notification() != null && (status == null || packet.context().taskRef() == null
            || !presentation.notification().kind().equals("task_" + status.wire())))
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "Completion notice requires a terminal task event");
    }

    /** The same task receipt is projected by every L1 adapter. */
    public record CompletionReceipt(String origin, String sessionWorkspaceRef, String workspaceLabel,
                                    String targetRef, String sessionLabel, String taskRef,
                                    String query, String status, String result) {}

    public CompletionReceipt completionReceipt() {
        if (presentation.notification() == null) return null;
        var details = switch (packet.payload()) {
            case EventPayload.TaskCompleted value -> value.completion();
            case EventPayload.TaskFailed value -> value.completion();
            case EventPayload.TaskCancelled value -> value.completion();
            default -> null;
        };
        var source = presentation.source();
        String query = details != null && details.query() != null ? details.query()
            : source == null ? null : source.taskSummary();
        String result = details == null ? null : details.result();
        if (result == null && packet.payload() instanceof EventPayload.TaskFailed failed) result = failed.error().message();
        if (result == null && packet.payload() instanceof EventPayload.TaskCancelled cancelled) result = cancelled.reason();
        return new CompletionReceipt(presentation.notification().origin(),
            source == null ? null : source.sessionWorkspaceRef(), source == null ? null : source.workspaceLabel(),
            packet.context().targetRef(), source == null ? presentation.sourceLabel() : source.sessionLabel(),
            packet.context().taskRef(), query, packet.type().terminalTaskStatus().wire(), result);
    }

    public record Routing(String entryRef, String destinationRef, long destinationRevision) {
        public Routing {
            Producer.requireText(entryRef, "delivery.entryRef");
            Producer.requireText(destinationRef, "delivery.destinationRef");
        }
    }

    /** sourceLabel 仅用于展示，不参与授权、不改变事件身份（INV-40）。 */
    public record Presentation(String sourceLabel, Source source, Notification notification) {
        public Presentation(String sourceLabel) { this(sourceLabel, null, null); }
        public Presentation {
            Producer.requireText(sourceLabel, "presentation.sourceLabel");
        }
    }

    public record Source(String sessionWorkspaceRef, String workspaceLabel, String sessionLabel, String taskSummary) {}
    public record Navigation(String kind) {
        public Navigation { if (!"open_source_session".equals(kind)) throw new IllegalArgumentException("Invalid navigation"); }
    }
    public record Notification(String kind, String origin, Navigation navigation) {
        public Notification {
            if (!("task_completed".equals(kind) || "task_failed".equals(kind) || "task_cancelled".equals(kind))
                || !("request_reply".equals(origin) || "task_subscription".equals(origin)
                    || "session_subscription".equals(origin)) || navigation == null)
                throw new IllegalArgumentException("Invalid completion notice");
        }
    }

    /** 动作绑定原事项、版本、允许操作与有效期；显示按钮不代表批准权限（INV-05）。 */
    public record Action(String actionRef, String label) {
        public Action {
            Producer.requireText(actionRef, "actionRef");
            Producer.requireText(label, "action.label");
        }
    }
}
