package com.netlivecowork.channel.l1.support;

import com.netlivecowork.channel.contract.ChannelDelivery;
import com.netlivecowork.channel.contract.ChannelMessage;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.Correlation;
import com.netlivecowork.channel.contract.EventOrder;
import com.netlivecowork.channel.contract.EventPacket;
import com.netlivecowork.channel.contract.EventPayload;
import com.netlivecowork.channel.contract.EventSource;
import com.netlivecowork.channel.contract.EventType;
import com.netlivecowork.channel.contract.PacketContext;
import com.netlivecowork.channel.contract.Producer;
import java.time.Instant;
import java.util.List;

/** 测试用规范包构造：EventPacket 由 L2 创建，投递固定目的地与来源标注。 */
public final class Packets {

    public static final Instant AT = Instant.parse("2026-10-27T08:00:00Z");

    private Packets() {
    }

    public static EventPacket messageReady(String text) {
        return messageReady(List.of(new ContentPart.Text("p1", text)));
    }

    public static EventPacket messageReady(List<ContentPart> content) {
        return packet(EventType.MESSAGE_READY,
            new EventPayload.MessageReady(new ChannelMessage("message-1", content, null)), null);
    }

    public static EventPacket taskCompleted() {
        return packet(EventType.TASK_COMPLETED,
            new EventPayload.TaskCompleted(2, List.of("message-1"), List.of()), "task-1");
    }

    public static EventPacket packet(EventType type, EventPayload payload, String taskRef) {
        return new EventPacket("1", "packet-1", type, AT, Correlation.of("flow-1"),
            new PacketContext("tenant-1", "workspace-1", "target-1", null, taskRef),
            new Producer(com.netlivecowork.channel.contract.Layer.L2, "application-1"),
            new EventSource("resource-1", "source-event-1"), AT, new EventOrder("order-1", 1), payload);
    }

    public static ChannelDelivery delivery(EventPacket packet, String deliveryId, String entryRef,
                                           String destinationRef, long revision, String sourceLabel) {
        return delivery(packet, deliveryId, entryRef, destinationRef, revision, sourceLabel, List.of());
    }

    public static ChannelDelivery delivery(EventPacket packet, String deliveryId, String entryRef,
                                           String destinationRef, long revision, String sourceLabel,
                                           List<ChannelDelivery.Action> actions) {
        return new ChannelDelivery("1", deliveryId, packet,
            new ChannelDelivery.Routing(entryRef, destinationRef, revision),
            new ChannelDelivery.Presentation(sourceLabel), actions);
    }
}
