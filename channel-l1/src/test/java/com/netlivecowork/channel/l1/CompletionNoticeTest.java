package com.netlivecowork.channel.l1;

import static org.junit.jupiter.api.Assertions.*;
import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.*;
import com.netlivecowork.channel.l1.render.PlainTextRenderer;
import com.netlivecowork.channel.l1.support.Packets;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompletionNoticeTest {
    private ChannelDelivery.Presentation presentation() {
        return new ChannelDelivery.Presentation("获准会话",new ChannelDelivery.Source("logical-workspace","项目 A","分析会话","生成报表"),
            new ChannelDelivery.Notification("task_completed","task_subscription",new ChannelDelivery.Navigation("open_source_session")));
    }
    @Test void textPlatformRendersLegacyCompletionAndKeepsInternalReferencesHidden() {
        var delivery=new ChannelDelivery("1","stable-delivery",Packets.taskCompleted(),new ChannelDelivery.Routing("entry","destination",1),presentation(),List.of());
        String text=PlainTextRenderer.deliveryText(delivery);
        assertEquals("来自任务订阅\n工作空间：项目 A\n会话：分析会话\n用户提问：生成报表\n任务状态：已完成\n可在原会话查看执行详情。",text);
        for(String hidden:List.of("stable-delivery","task-1","message-1","logical-workspace","destination"))assertFalse(text.contains(hidden));
        assertEquals(delivery,PacketJson.decDelivery(Json.write(PacketJson.enc(delivery))));
    }
    @Test void structuredAndTextAdaptersUseTheSamePersistedExecutionResult() {
        var packet = Packets.taskCompleted();
        var delivery = new ChannelDelivery("1", "delivery", new EventPacket(packet.version(), packet.packetId(),
            packet.type(), packet.createdAt(), packet.correlation(), packet.context(), packet.producer(),
            packet.source(), packet.occurredAt(), packet.order(), new EventPayload.TaskCompleted(1, List.of(), List.of(),
                new EventPayload.TaskCompletion("核对本周报表", "完成 3 项检查"))),
            new ChannelDelivery.Routing("entry", "destination", 1), presentation(), List.of());
        var restored = PacketJson.decDelivery(Json.write(PacketJson.enc(delivery)));
        assertEquals(delivery.completionReceipt(), restored.completionReceipt());
        assertEquals("completed", restored.completionReceipt().status());
        assertEquals("项目 A", restored.completionReceipt().workspaceLabel());
        assertTrue(PlainTextRenderer.deliveryText(restored).endsWith("用户提问：核对本周报表\n任务状态：已完成\n执行结果：\n完成 3 项检查"));
    }
    @Test void messageReadyIsNotACompletionAndPublicCodecRejectsUnknownPresentationFields() {
        assertThrows(ChannelContractException.class,()->new ChannelDelivery("1","wrong-event",Packets.messageReady("完整答案"),new ChannelDelivery.Routing("entry","destination",1),presentation(),List.of()));
        var old=Packets.delivery(Packets.messageReady("完整答案"),"old","entry","destination",1,"来源");
        assertNull(PacketJson.decDelivery(Json.write(PacketJson.enc(old))).presentation().notification());
        var json=PacketJson.enc(old);((com.fasterxml.jackson.databind.node.ObjectNode)json.path("presentation")).put("routeTo","another-user");
        assertThrows(ChannelContractException.class,()->PacketJson.decDelivery(json.toString()));
    }
}
