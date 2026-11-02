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
        if (presentation.notification() != null && (packet.type() != EventType.TASK_COMPLETED
            || packet.context().taskRef() == null))
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "Completion notice requires task.completed");
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
            if (!"task_completed".equals(kind) || !("request_reply".equals(origin) || "task_subscription".equals(origin)) || navigation == null)
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
