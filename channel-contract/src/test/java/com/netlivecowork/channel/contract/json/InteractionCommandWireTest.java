package com.netlivecowork.channel.contract.json;

import com.netlivecowork.channel.contract.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Golden JSON comes from data-contracts.md §4.2, independently of the encoder. */
class InteractionCommandWireTest {
    private static final String ENVELOPE="""
        {"version":"1","packetId":"cmd-1","kind":"command","type":"interaction.respond",
         "createdAt":"2026-11-01T00:00:00Z","correlation":{"flowId":"flow-1"},
         "context":{"tenantRef":"tenant-1","workspaceRef":"workspace-1","targetRef":"target-1","operationRef":"op-1","taskRef":"task-1"},
         "producer":{"layer":"L2","componentRef":"channel-l2"},"actor":{"principalRef":"user-1"},"payload":%s}
        """;
    private static final String APPROVE="""
        {"interactionRef":"interaction-1","expectedRevision":7,"response":{"kind":"approve"}}
        """;
    private static final String REJECT="""
        {"interactionRef":"interaction-1","expectedRevision":7,"response":{"kind":"reject","reason":"不执行"}}
        """;
    private static final String ANSWER="""
        {"interactionRef":"interaction-1","expectedRevision":7,"response":{"kind":"answer","answers":[
          {"questionId":"q-1","kind":"choice","optionIds":["o-1","o-2"]},
          {"questionId":"q-2","kind":"text","content":[{"partId":"answer","type":"text","text":"补充说明","format":"plain"}]}]}}
        """;

    private CommandPacket command(InteractionResponse response) {
        return new CommandPacket("1","cmd-1",CommandType.INTERACTION_RESPOND,Instant.parse("2026-11-01T00:00:00Z"),
            Correlation.of("flow-1"),new PacketContext("tenant-1","workspace-1","target-1","op-1","task-1"),
            new Producer(Layer.L2,"channel-l2"),Actor.of("user-1"),
            new CommandPayload.InteractionRespond(new InteractionRespondPayload("interaction-1",7,response)));
    }
    private void verify(InteractionResponse response,String literalPayload) {
        var expected=Json.read(ENVELOPE.formatted(literalPayload));
        assertEquals(expected,Json.read(Json.write(PacketJson.enc(command(response)))),"The whole wire packet must match the original design JSON");
        assertEquals(command(response),PacketJson.decCommand(Json.write(expected)),"An independently authored flat packet must decode");
        assertFalse(PacketJson.enc(command(response)).path("payload").has("payload"));
        var operation=new ChannelOperation.InteractionRespond(new InteractionRespondPayload("interaction-1",7,response));
        assertEquals(Json.read(literalPayload),Json.read(Json.write(PacketJson.enc(operation))).get("payload"),
            "L1's operation and L2's command must use the same original payload shape");
        assertEquals(operation,PacketJson.decOperation("interaction.respond",Json.read(literalPayload)));
    }
    @Test void approveMatchesOriginalFlatPayload() {verify(InteractionResponse.Approve.plain(),APPROVE);}
    @Test void rejectMatchesOriginalFlatPayload() {verify(new InteractionResponse.Reject("不执行"),REJECT);}
    @Test void completeGroupMatchesOriginalFlatPayload() {
        verify(new InteractionResponse.Answer(List.of(new QuestionAnswer.ChoiceAnswer("q-1",List.of("o-1","o-2"),null),
            new QuestionAnswer.TextAnswer("q-2",List.of(new ContentPart.Text("answer","补充说明"))))),ANSWER);
    }
    @Test void publicDecoderRejectsExtraWrapperForEveryResponseKind() {
        for(String payload:List.of(APPROVE,REJECT,ANSWER))
            assertThrows(ChannelContractException.class,()->PacketJson.decCommand(ENVELOPE.formatted("{\"payload\":"+payload+"}")));
    }
    @Test void publicDecoderRejectsMixedAndRecursiveWrappers() {
        var mixed=(com.fasterxml.jackson.databind.node.ObjectNode)Json.read(APPROVE);
        mixed.set("payload",Json.read(APPROVE));
        assertThrows(ChannelContractException.class,()->PacketJson.decCommand(ENVELOPE.formatted(Json.write(mixed))));
        assertThrows(ChannelContractException.class,()->PacketJson.decCommand(ENVELOPE.formatted("{\"payload\":{\"payload\":"+APPROVE+"}}")));
    }
}
