package com.netlivecowork.channel.l3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netlivecowork.channel.contract.AgentEvent;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.json.PacketJson;

/** Strict personal-text-v1 wire DTOs normalized into the common internal event model. */
final class BackendEventCodec {
    static AgentEvent decode(String body) { return decode(body,false); }
    static AgentEvent decodeSnapshot(String body) { return decode(body,true); }
    private static AgentEvent decode(String body,boolean snapshot) {
        JsonNode parsed=Json.read(body);
        if(!parsed.isObject())throw Json.invalid("event object is required");
        ObjectNode node=(ObjectNode)parsed;
        Json.onlyFields(node,"version","eventId","type","occurredAt","resource","correlation","order","payload");
        id(Json.text(node,"eventId"));
        JsonNode resource=Json.obj(node,"resource");
        Json.onlyFields(resource,"sessionId","taskId","runId");
        id(Json.text(resource,"sessionId"));
        if(resource.has("runId"))id(Json.text(resource,"runId"));
        if(resource.has("taskId"))id(Json.text(resource,"taskId"));
        if(!snapshot || node.has("correlation")){JsonNode correlation=Json.obj(node,"correlation");id(Json.text(correlation,"requestKey"));}
        if(node.has("order")) {
            ObjectNode order=(ObjectNode)Json.obj(node,"order");
            Json.onlyFields(order,"streamId","sequence");
            id(Json.text(order,"streamId"));
            long sequence=Json.reqLong(order,"sequence");
            if(sequence<1 || sequence>9007199254740991L)throw Json.invalid("invalid event sequence");
            order.put("scopeRef",order.remove("streamId").textValue());
        }
        String type=Json.text(node,"type");
        ObjectNode payload=(ObjectNode)Json.obj(node,"payload");
        if(type.startsWith("interaction.")) {
            JsonNode ref=Json.obj(payload,type.equals("interaction.required")?"interaction":"ref");
            if(type.equals("interaction.required"))Json.onlyFields(ref,"backendInteractionId","revision","expiresAt","prompt");
            else Json.onlyFields(ref,"backendInteractionId","revision");
            id(Json.text(ref,"backendInteractionId"));long revision=Json.reqLong(ref,"revision");
            if(revision<1||revision>9007199254740991L)throw Json.invalid("Invalid interaction revision");
            var event=PacketJson.decAgentEvent(Json.write(node));
            if(!java.util.Set.of("interaction.required","interaction.resolved","interaction.expired","interaction.cancelled").contains(type))throw Json.invalid("Invalid interaction event");
            return event;
        }
        long revision=Json.reqLong(payload,"revision");
        if(revision<1 || revision>9007199254740991L)throw Json.invalid("invalid event revision");
        if(type.equals("message.ready")) {
            Json.onlyFields(payload,"backendMessageId","revision","content");
            if(revision!=1)throw Json.invalid("message revision must be 1");
            id(Json.text(payload,"backendMessageId"));
            for(JsonNode part:Json.array(payload,"content")) {
                Json.onlyFields(part,"partId","type","text","format");
                id(Json.text(part,"partId"));
                if(!"text".equals(Json.text(part,"type")) || !"plain".equals(Json.text(part,"format")))
                    throw Json.invalid("personal-text-v1 requires plain text parts");
                Json.text(part,"text");
            }
            payload.remove("revision");
        } else {
            String task=id(Json.text(resource,"taskId"));
            switch(type) {
                case "task.started", "task.cancelled" -> Json.onlyFields(payload,"revision");
                case "task.waiting" -> {
                    Json.onlyFields(payload,"revision","reason");
                    if(!java.util.Set.of("interaction_required","external_dependency").contains(Json.text(payload,"reason")))
                        throw Json.invalid("invalid waiting reason");
                }
                case "task.completed" -> {
                    Json.onlyFields(payload,"revision","resultMessageIds");
                    JsonNode ids=Json.array(payload,"resultMessageIds");
                    ids.forEach(value -> { if(!value.isTextual())throw Json.invalid("invalid result message ID");id(value.textValue()); });
                    payload.set("backendResultMessageIds",ids);
                    payload.remove("resultMessageIds");
                }
                case "task.failed" -> {
                    Json.onlyFields(payload,"revision","error");
                    ObjectNode error=(ObjectNode)Json.obj(payload,"error");
                    Json.onlyFields(error,"code","message");
                    Json.text(error,"code");Json.text(error,"message");
                    error.put("retryable",false);
                }
                default -> throw Json.invalid("unsupported personal-text-v1 event type");
            }
            payload.put("backendTaskId",task);
        }
        return PacketJson.decAgentEvent(Json.write(node));
    }
    private static String id(String value) {
        if(!value.matches("[A-Za-z0-9._~-]{1,128}"))throw Json.invalid("invalid protocol identifier");
        return value;
    }
}
