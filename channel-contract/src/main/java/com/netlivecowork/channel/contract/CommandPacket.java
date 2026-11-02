package com.netlivecowork.channel.contract;

import java.time.Instant;

/**
 * 完成业务校验并持久受理的规范指令。不可变：packetId 固定后不追加或改写任何字段（INV-17）。
 */
public record CommandPacket(String version, String packetId, CommandType type, Instant createdAt,
                            Correlation correlation, PacketContext context, Producer producer, Actor actor,
                            CommandPayload payload) implements Packet {

    public CommandPacket {
        Packet.requireVersionOne(version);
        Producer.requireText(packetId, "packetId");
        if (type == null || payload == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "command requires type and payload");
        }
        if (!type.payloadType().isInstance(payload)) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "payload " + payload.getClass().getSimpleName() + " does not match type " + type.wire());
        }
        if (createdAt == null || correlation == null || context == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "command requires createdAt, correlation and context");
        }
        if (producer == null || producer.layer() != Layer.L2) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "CommandPacket producer must be L2");
        }
        if (actor == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "command requires actor");
        }
        if (context.operationRef() == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "command requires context.operationRef");
        }
    }

    @Override
    public String kind() {
        return "command";
    }

    /** 需要 context.targetRef 的操作范围（data-contracts.md §4.2）。 */
    public void requireTarget(String hint) {
        context.requireTargetRef(hint);
    }
}
