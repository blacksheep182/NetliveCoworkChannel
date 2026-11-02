package com.netlivecowork.channel.contract;

import java.time.Instant;

/**
 * 对规范指令的受理、完成、拒绝或未知回执。causationId 必须指被回应的 CommandPacket.packetId。
 */
public record ReceiptPacket(String version, String packetId, Instant createdAt, Correlation correlation,
                            PacketContext context, Producer producer, ReceiptPayload payload) implements Packet {

    public ReceiptPacket {
        Packet.requireVersionOne(version);
        Producer.requireText(packetId, "packetId");
        if (createdAt == null || correlation == null || context == null || producer == null || payload == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "receipt requires createdAt, correlation, context, producer and payload");
        }
        if (!correlation.hasCausation()) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "receipt.correlation.causationId must point at the command packetId");
        }
    }

    @Override
    public String kind() {
        return "receipt";
    }

    public static ReceiptPacket agentAccepted(Producer producer, PacketContext context, Correlation correlation,
                                              Instant createdAt, String packetId) {
        return new ReceiptPacket("1", packetId, createdAt, correlation, context, producer,
            new ReceiptPayload.Accepted(ReceiptPayload.AcceptedBy.AGENT));
    }
}
