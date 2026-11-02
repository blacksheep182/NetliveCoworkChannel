package com.netlivecowork.channel.l3;

import static org.junit.jupiter.api.Assertions.*;
import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.Json;
import org.junit.jupiter.api.Test;

class BackendEventCodecTest {
    private String event(String type,String payload) {
        return "{\"version\":\"1\",\"eventId\":\"event-1\",\"type\":\""+type+"\","
            +"\"occurredAt\":\"2026-10-30T00:00:00Z\",\"resource\":{\"sessionId\":\"session-1\",\"taskId\":\"task-1\"},"
            +"\"correlation\":{\"requestKey\":\"request-1\"},\"payload\":"+payload+"}";
    }
    @Test void allSixHttpPayloadsNormalizeWithoutChangingTheResourceIdentity() {
        var message=BackendEventCodec.decode(event("message.ready","{\"backendMessageId\":\"message-1\",\"revision\":1,"
            +"\"content\":[{\"partId\":\"text\",\"type\":\"text\",\"text\":\"完整回复\",\"format\":\"plain\"}]}"));
        assertEquals("message-1",assertInstanceOf(AgentEventPayload.MessageReady.class,message.payload()).backendMessageId());
        assertEquals("task-1",assertInstanceOf(AgentEventPayload.TaskStarted.class,
            BackendEventCodec.decode(event("task.started","{\"revision\":1}")).payload()).backendTaskId());
        assertEquals("interaction_required",assertInstanceOf(AgentEventPayload.TaskWaiting.class,
            BackendEventCodec.decode(event("task.waiting","{\"revision\":2,\"reason\":\"interaction_required\"}")).payload()).reason());
        var done=assertInstanceOf(AgentEventPayload.TaskCompleted.class,
            BackendEventCodec.decode(event("task.completed","{\"revision\":3,\"resultMessageIds\":[\"message-1\"]}")).payload());
        assertEquals(java.util.List.of("message-1"),done.backendResultMessageIds());
        assertEquals("task-1",done.backendTaskId());
        assertInstanceOf(AgentEventPayload.TaskFailed.class,BackendEventCodec.decode(event("task.failed",
            "{\"revision\":3,\"error\":{\"code\":\"EXECUTION_FAILED\",\"message\":\"Task failed\"}}")).payload());
        assertInstanceOf(AgentEventPayload.TaskCancelled.class,
            BackendEventCodec.decode(event("task.cancelled","{\"revision\":3}")).payload());
    }
    @Test void wireStreamIdIsNormalizedToInternalOrderScope() {
        var source=Json.read(event("task.started","{\"revision\":1}"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)source).set("order",Json.obj().put("streamId","stream-1").put("sequence",1));
        assertEquals("stream-1",BackendEventCodec.decode(Json.write(source)).order().scopeRef());
    }
    @Test void legacyFieldsUnknownVersionsAndMissingCorrelationCannotEnterHttpProfile() {
        assertThrows(ChannelContractException.class,()->BackendEventCodec.decode(event("task.started","{\"revision\":1,\"backendTaskId\":\"other\"}")));
        assertThrows(ChannelContractException.class,()->BackendEventCodec.decode(event("task.waiting","{\"revision\":1,\"reason\":\"unknown\"}")));
        assertThrows(ChannelContractException.class,()->BackendEventCodec.decode(event("task.started","{\"revision\":0}")));
        assertThrows(ChannelContractException.class,()->BackendEventCodec.decode(event("task.progress","{\"revision\":1}")));
        assertThrows(ChannelContractException.class,()->BackendEventCodec.decode("[]"));
        assertThrows(ChannelContractException.class,()->BackendEventCodec.decode(event("task.started","{\"revision\":1}").replace("\"version\":\"1\"","\"version\":\"2\"")));
        var source=(com.fasterxml.jackson.databind.node.ObjectNode)Json.read(event("task.started","{\"revision\":1}"));
        source.remove("correlation");
        assertThrows(ChannelContractException.class,()->BackendEventCodec.decode(Json.write(source)));
    }

    @Test void completeInteractionKeepsActualTaskAndSnapshotAllowsOnlyMissingCorrelation() {
        String payload="{\"interaction\":{\"backendInteractionId\":\"hit-1\",\"revision\":10,\"prompt\":{\"kind\":\"approval\",\"content\":[{\"partId\":\"p\",\"type\":\"text\",\"text\":\"确认\",\"format\":\"plain\"}],\"operation\":{\"name\":\"fs:shell\",\"arguments\":{\"command\":\"echo safe\"}},\"allowedResponses\":[\"approve\",\"reject\"]}}}";
        var decoded=BackendEventCodec.decode(event("interaction.required",payload));
        var required=assertInstanceOf(AgentEventPayload.InteractionRequired.class,decoded.payload());
        assertEquals("task-1",required.interaction().backendTaskId());
        var nativeEvent=(com.fasterxml.jackson.databind.node.ObjectNode)Json.read(event("interaction.required",payload));nativeEvent.remove("correlation");
        assertNotNull(BackendEventCodec.decodeSnapshot(Json.write(nativeEvent)));
        assertThrows(ChannelContractException.class,()->BackendEventCodec.decode(Json.write(nativeEvent)));
        var raw=(com.fasterxml.jackson.databind.node.ObjectNode)nativeEvent.path("payload").path("interaction");raw.put("backendTaskId","spoofed");
        assertThrows(ChannelContractException.class,()->BackendEventCodec.decodeSnapshot(Json.write(nativeEvent)));
    }
}
