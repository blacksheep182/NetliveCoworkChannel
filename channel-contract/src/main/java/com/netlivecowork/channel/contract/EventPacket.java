package com.netlivecowork.channel.contract;

import java.time.Instant;

/**
 * 规范事件（L2 创建）。producer 记录真实创建层；source/occurredAt/order 来自 L3 验证后的事实。
 * 内部引用映射完成并持久化后整包不变（INV-17）。
 */
public record EventPacket(String version, String packetId, EventType type, Instant createdAt,
                          Correlation correlation, PacketContext context, Producer producer,
                          EventSource source, Instant occurredAt, EventOrder order, EventPayload payload)
    implements Packet {

    public EventPacket {
        Packet.requireVersionOne(version);
        Producer.requireText(packetId, "packetId");
        if (type == null || payload == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "event requires type and payload");
        }
        if (!type.payloadType().isInstance(payload)) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "payload " + payload.getClass().getSimpleName() + " does not match type " + type.wire());
        }
        if (createdAt == null || occurredAt == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "event requires createdAt and occurredAt");
        }
        if (correlation == null || context == null || producer == null || source == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "event requires correlation, context, producer and source");
        }
        if (producer.layer() != Layer.L2) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "EventPacket producer must be L2");
        }
        if (context.targetRef() == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "EventPacket requires context.targetRef");
        }
        if (type.isTaskEvent() && context.taskRef() == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "task event " + type.wire() + " requires context.taskRef");
        }
    }

    @Override
    public String kind() {
        return "event";
    }
}
