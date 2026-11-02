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
    @Test void textPlatformKeepsSourceAndCompletionMeaningWithoutCopyingAnswerOrLeakingInternalRefs() {
        var delivery=new ChannelDelivery("1","stable-delivery",Packets.taskCompleted(),new ChannelDelivery.Routing("entry","destination",1),presentation(),List.of());
        String text=PlainTextRenderer.body(delivery);
        assertEquals("来自任务订阅：任务已完成｜项目 A｜分析会话｜生成报表。结果按原会话消息送达；可通过 /list 选择原会话查看。",text);
        for(String hidden:List.of("stable-delivery","task-1","message-1","logical-workspace","destination"))assertFalse(text.contains(hidden));
        assertEquals(delivery,PacketJson.decDelivery(Json.write(PacketJson.enc(delivery))));
    }
    @Test void messageReadyIsNotACompletionAndPublicCodecRejectsUnknownPresentationFields() {
        assertThrows(ChannelContractException.class,()->new ChannelDelivery("1","wrong-event",Packets.messageReady("完整答案"),new ChannelDelivery.Routing("entry","destination",1),presentation(),List.of()));
        var old=Packets.delivery(Packets.messageReady("完整答案"),"old","entry","destination",1,"来源");
        assertNull(PacketJson.decDelivery(Json.write(PacketJson.enc(old))).presentation().notification());
        var json=PacketJson.enc(old);((com.fasterxml.jackson.databind.node.ObjectNode)json.path("presentation")).put("routeTo","another-user");
        assertThrows(ChannelContractException.class,()->PacketJson.decDelivery(json.toString()));
    }
}
