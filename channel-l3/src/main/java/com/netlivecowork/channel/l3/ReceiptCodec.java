package com.netlivecowork.channel.l3;

import com.netlivecowork.channel.contract.ReceiptPacket;
import com.netlivecowork.channel.contract.ReceiptPayload;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.json.PacketJson;

/**
 * 回执 JSON 编解码。契约的 PacketJson 提供 enc(ReceiptPacket) 但没有对应 dec，L3 需要从
 * 派发记录读回最近回执，因此在此补一个只读解码器（不修改契约，见报告里的契约缺口）。
 */
final class ReceiptCodec {

    private ReceiptCodec() {
    }

    static String encode(ReceiptPacket receipt) {
        return Json.write(PacketJson.enc(receipt));
    }

    static ReceiptPacket decode(String json) {
        var node = Json.read(json);
        Json.onlyFields(node, "version", "packetId", "kind", "createdAt", "correlation", "context", "producer",
            "payload");
        var payloadNode = Json.obj(node, "payload");
        Json.onlyFields(payloadNode, "status", "acceptedBy", "result", "error");
        ReceiptPayload payload = switch (Json.text(payloadNode, "status")) {
            case "accepted" -> new ReceiptPayload.Accepted(
                "channel".equalsIgnoreCase(Json.text(payloadNode, "acceptedBy"))
                    ? ReceiptPayload.AcceptedBy.CHANNEL : ReceiptPayload.AcceptedBy.AGENT);
            case "completed" -> new ReceiptPayload.Completed(
                PacketJson.decOperationResult(Json.obj(payloadNode, "result")));
            case "rejected" -> new ReceiptPayload.Rejected(PacketJson.decError(Json.obj(payloadNode, "error")));
            case "unknown" -> new ReceiptPayload.Unknown(PacketJson.decError(Json.obj(payloadNode, "error")));
            default -> throw Json.invalid("unknown receipt status");
        };
        return new ReceiptPacket(Json.text(node, "version"), Json.text(node, "packetId"),
            Json.instant(node, "createdAt"), PacketJson.decCorrelation(Json.obj(node, "correlation")),
            PacketJson.decContext(Json.obj(node, "context")), PacketJson.decProducer(Json.obj(node, "producer")),
            payload);
    }
}
