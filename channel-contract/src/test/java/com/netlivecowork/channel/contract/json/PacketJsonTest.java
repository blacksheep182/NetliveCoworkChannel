package com.netlivecowork.channel.contract.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netlivecowork.channel.contract.Actor;
import com.netlivecowork.channel.contract.AgentEventPayload;
import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelDelivery;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.ChannelMessage;
import com.netlivecowork.channel.contract.CommandPacket;
import com.netlivecowork.channel.contract.CommandPayload;
import com.netlivecowork.channel.contract.CommandType;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.Correlation;
import com.netlivecowork.channel.contract.EventPacket;
import com.netlivecowork.channel.contract.EventPayload;
import com.netlivecowork.channel.contract.EventSource;
import com.netlivecowork.channel.contract.EventType;
import com.netlivecowork.channel.contract.InteractionPrompt;
import com.netlivecowork.channel.contract.InteractionRequiredPayload;
import com.netlivecowork.channel.contract.Layer;
import com.netlivecowork.channel.contract.NormalizedContentPart;
import com.netlivecowork.channel.contract.PacketContext;
import com.netlivecowork.channel.contract.Producer;
import com.netlivecowork.channel.contract.QuestionItem;
import com.netlivecowork.channel.contract.VerifiedBackendEvent;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 契约 JSON 的编解码对称性与严格拒绝（data-contracts.md 约定）。 */
class PacketJsonTest {

    private static final Instant NOW = Instant.parse("2026-10-27T08:00:00Z");
    private static final Producer L2 = new Producer(Layer.L2, "test-l2");

    private CommandPacket command(CommandPayload payload, CommandType type) {
        return new CommandPacket("1", "cmd-1", type, NOW, Correlation.of("flow-1"),
            new PacketContext("tenant-1", "workspace-1", "target-1", "op-1", null), L2,
            Actor.of("caller-1"), payload);
    }

    @Test
    void acceptanceAuthorityIsIndependentOfAnOptionalTaskId() {
        var confirmed = new com.netlivecowork.channel.contract.ChannelResponse.Accepted("r", "op", null,
            com.netlivecowork.channel.contract.ReceiptPayload.AcceptedBy.AGENT);
        assertEquals(confirmed, PacketJson.decChannelResponse(Json.write(PacketJson.enc(confirmed))));
        var legacy = PacketJson.enc(confirmed);legacy.remove("acceptedBy");
        var decoded = (com.netlivecowork.channel.contract.ChannelResponse.Accepted)
            PacketJson.decChannelResponse(Json.write(legacy));
        assertEquals(com.netlivecowork.channel.contract.ReceiptPayload.AcceptedBy.CHANNEL, decoded.acceptedBy());
        legacy.put("acceptedBy", "invented");
        assertThrows(ChannelContractException.class, () -> PacketJson.decChannelResponse(Json.write(legacy)));
    }

    @Test
    void sessionExecutionSnapshotSurvivesResultRoundTrip() {
        var execution = new com.netlivecowork.channel.contract.SessionExecutionState("waiting", 17, NOW);
        var session = new com.netlivecowork.channel.contract.SessionSummary("target-1", "会话", null, true, execution);
        var packet = new com.netlivecowork.channel.contract.OperationResult.SessionGet(session);
        assertEquals(packet, PacketJson.decOperationResult(Json.read(Json.write(PacketJson.enc(packet)))));
        var legacy = Json.write(PacketJson.enc(packet)).replace(
            ",\"execution\":{\"status\":\"waiting\",\"revision\":17,\"observedAt\":\"" + NOW + "\"}", "");
        var result = (com.netlivecowork.channel.contract.OperationResult.SessionGet)
            PacketJson.decOperationResult(Json.read(legacy));
        assertEquals(null, result.session().execution());
        assertThrows(ChannelContractException.class,
            () -> PacketJson.decOperationResult(Json.read(Json.write(PacketJson.enc(packet)).replace("\"revision\":17", "\"revision\":0"))));
    }

    @Test
    void commandPacketRoundTrips() {
        CommandPacket packet = command(new CommandPayload.InputSubmit(new ChannelMessage("msg-1",
            List.of(new ContentPart.Text("p1", "你好", ContentPart.Text.Format.PLAIN)), null),
            com.netlivecowork.channel.contract.ChannelOperation.Interpretation.COMMAND_AWARE),
            CommandType.INPUT_SUBMIT);
        CommandPacket decoded = PacketJson.decCommand(Json.write(PacketJson.enc(packet)));
        assertEquals(packet, decoded);
    }

    @Test
    void eventPacketWithInteractionPromptRoundTrips() {
        InteractionPrompt prompt = new InteractionPrompt.Questions(
            List.of(new ContentPart.Text("p1", "请选择范围")),
            List.of(new QuestionItem("q1", List.of(new ContentPart.Text("p2", "范围?")), true,
                new QuestionItem.Input.ChoiceInput(false, true,
                    List.of(QuestionItem.ChoiceOption.of("o1", "本月"))))),
            Set.of(InteractionPrompt.AllowedResponse.ANSWER));
        EventPacket packet = new EventPacket("1", "event-1", EventType.INTERACTION_REQUIRED, NOW,
            Correlation.of("flow-1"), new PacketContext("tenant-1", "workspace-1", "target-1", "op-1", "task-1"),
            L2, new EventSource("resource-1", "be-1"), NOW, null,
            new EventPayload.InteractionRequired(new InteractionRequiredPayload("itx-1", 2, null, prompt)));
        EventPacket decoded = PacketJson.decEvent(Json.write(PacketJson.enc(packet)));
        assertEquals(packet, decoded);
    }

    @Test
    void deliveryRoundTripsWithActions() {
        EventPacket packet = new EventPacket("1", "event-2", EventType.MESSAGE_READY, NOW,
            Correlation.of("flow-1"), new PacketContext("tenant-1", "workspace-1", "target-1", null, null), L2,
            new EventSource("resource-1", "be-2"), NOW, null,
            new EventPayload.MessageReady(new ChannelMessage("msg-2",
                List.of(new ContentPart.Text("p1", "完成")), null)));
        ChannelDelivery delivery = new ChannelDelivery("1", "delivery-1", packet,
            new ChannelDelivery.Routing("entry-1", "dest-1", 3L),
            new ChannelDelivery.Presentation("报表助手"), List.of(new ChannelDelivery.Action("act-1", "批准")));
        ChannelDelivery decoded = PacketJson.decDelivery(Json.write(PacketJson.enc(delivery)));
        assertEquals(delivery, decoded);
    }

    @Test
    void agentEventIsParsedStrictly() {
        String event = """
            {"version":"1","eventId":"evt-1","type":"message.ready","occurredAt":"2026-10-27T00:00:00Z",
             "resource":{"sessionId":"s-1"},
             "correlation":{"requestKey":"rk-1"},
             "payload":{"content":[{"partId":"p1","type":"text","text":"hi","format":"plain"}],
                        "backendMessageId":"bm-1"}}
            """;
        var parsed = PacketJson.decAgentEvent(event);
        assertEquals(EventType.MESSAGE_READY, parsed.eventType());
        assertEquals("bm-1", ((AgentEventPayload.MessageReady) parsed.payload()).backendMessageId());

        String unknownField = event.replace("\"eventId\":\"evt-1\"", "\"eventId\":\"evt-1\",\"extra\":1");
        ChannelContractException failure = assertThrows(ChannelContractException.class,
            () -> PacketJson.decAgentEvent(unknownField));
        assertEquals(ChannelErrorCode.INVALID_ARGUMENT, failure.code());

        String unknownType = event.replace("\"type\":\"message.ready\"", "\"type\":\"text.done\"");
        assertThrows(ChannelContractException.class, () -> PacketJson.decAgentEvent(unknownType));
    }

    @Test
    void normalizedContentRoundTrips() {
        VerifiedBackendEvent verified = new VerifiedBackendEvent("be-1",
            new EventSource("svc/session/1", "evt-1"), "op-1", "target-1", null,
            EventType.MESSAGE_READY, NOW, null,
            new AgentEventPayload.MessageReady(List.of(
                new NormalizedContentPart.Text("p1", "结果", ContentPart.Text.Format.MARKDOWN),
                new NormalizedContentPart.Image("p2", new com.netlivecowork.channel.contract.BackendFileGrant(
                    "bf-1", new com.netlivecowork.channel.contract.FileMetadata("chart.png", "image/png", 128L)),
                    "趋势图")), "bm-1", null));
        VerifiedBackendEvent decoded = PacketJson.decVerifiedEvent(Json.write(PacketJson.enc(verified)));
        assertEquals(verified, decoded);
    }

    @Test
    void duplicatePartIdIsRejected() {
        ChannelContractException failure = assertThrows(ChannelContractException.class,
            () -> new ChannelMessage("msg-1", List.of(new ContentPart.Text("p1", "a"),
                new ContentPart.Text("p1", "b")), null));
        assertEquals(ChannelErrorCode.INVALID_ARGUMENT, failure.code());
    }

    @Test
    void receiptRequiresCausation() {
        ChannelContractException failure = assertThrows(ChannelContractException.class,
            () -> new com.netlivecowork.channel.contract.ReceiptPacket("1", "receipt-1", NOW,
                Correlation.of("flow-1"), new PacketContext("tenant-1", "workspace-1", null, "op-1", null), L2,
                new com.netlivecowork.channel.contract.ReceiptPayload.Accepted(
                    com.netlivecowork.channel.contract.ReceiptPayload.AcceptedBy.AGENT)));
        assertTrue(failure.getMessage().contains("causationId"));
    }
}
