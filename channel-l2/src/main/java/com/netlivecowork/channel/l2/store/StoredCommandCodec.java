package com.netlivecowork.channel.l2.store;

import com.netlivecowork.channel.contract.CommandPacket;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.json.PacketJson;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Reads historical L2 records without rewriting immutable packets or accepting a
 * second public wire format. All new packets use PacketJson's strict flat payload.
 */
public final class StoredCommandCodec {
    private StoredCommandCodec() {}

    public static CommandPacket decode(String storedJson) {
        var parsed=Json.read(storedJson);
        if("interaction.respond".equals(parsed.path("type").asText())
                && parsed.path("payload").isObject() && parsed.path("payload").has("payload")) {
            var legacy=Json.obj(parsed,"payload");
            Json.onlyFields(legacy,"payload");
            var response=Json.obj(legacy,"payload");
            PacketJson.decRespondPayload(response); // Reject mixed, recursive or malformed legacy data.
            ((ObjectNode)parsed).set("payload",response);
        }
        return PacketJson.decCommand(Json.write(parsed));
    }
}
