package com.netlivecowork.channel.l1;

import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.*;
import com.netlivecowork.channel.l1.render.*;
import com.netlivecowork.channel.l1.support.*;
import com.netlivecowork.channel.l1.web.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InteractionReplyTest {
    InteractionRequiredPayload approval(){return new InteractionRequiredPayload("interaction-1",10,null,
        new InteractionPrompt.Approval(List.of(new ContentPart.Text("p","确认安全操作")),
            new InteractionPrompt.Approval.Operation("fs:shell",Json.obj().put("command","echo safe"),null),null));}
    InteractionRequiredPayload questions(){return new InteractionRequiredPayload("interaction-2",11,null,
        new InteractionPrompt.Questions(List.of(),List.of(
            new QuestionItem("q-a",List.of(new ContentPart.Text("p","下一步")),true,
                new QuestionItem.Input.ChoiceInput(false,true,List.of(new QuestionItem.ChoiceOption("option-a","继续",null,true),QuestionItem.ChoiceOption.of("option-b","结束")))),
            new QuestionItem("q-b",List.of(new ContentPart.Text("p","说明")),true,new QuestionItem.Input.TextInput())),null));}

    @Test void actualWebIngressAndChatReplyProduceTheSameExistingOperation() {
        var host=new TestHost("web");
        var instance=new WebAdapterFactory().create(new com.netlivecowork.channel.contract.adapter.AdapterInstanceConfig(
            "tenant-1","workspace-1","web",1,1,1,"web:web",Json.obj().set("users",Json.obj().put("alice","secret")),List.of()),host.host());
        instance.activate().toCompletableFuture().join();
        instance.respond("secret","request-1","view_123456789012345","interaction-1",10,Json.obj().put("kind","approve"));
        assertEquals(InteractionReplyMapper.reply(approval(),"同意"),host.operation(0));
        assertEquals("interaction.respond",host.operation(0).type());
        assertEquals(1,host.resolved.size());assertEquals(1,host.submitted.size());
        assertThrows(SecurityException.class,()->instance.respond("bad","req","view_123456789012345","interaction-1",10,Json.obj().put("kind","approve")));
        instance.deactivate(com.netlivecowork.channel.contract.adapter.StopReason.SHUTDOWN).toCompletableFuture().join();
    }
    @Test void groupReplyMapsOnlyDeliveredOptionIdentitiesAndCoversEveryRequiredQuestion() {
        var operation=InteractionReplyMapper.reply(questions(),"1:2;2:说明内容");
        var response=(InteractionResponse.Answer)operation.payload().response();
        assertEquals(new QuestionAnswer.ChoiceAnswer("q-a",List.of("option-b"),null),response.answers().get(0));
        assertEquals("q-b",response.answers().get(1).questionId());
        assertEquals(InteractionReplyMapper.form("interaction-2",11,ContentJson.encode(response)),operation);
        for(String invalid:List.of("1:1","1:9;2:内容","1:1,2;2:内容","1:1;1:2;2:内容"))
            assertThrows(ChannelContractException.class,()->InteractionReplyMapper.reply(questions(),invalid));
    }
    @Test void durableReplyContextIsAccountAndConversationScopedAndRetainsOriginalUnknownOperation() {
        var host=new TestHost("weixin");var first=new InteractionReplyContext(host.host().protocolState());
        var packet=Packets.packet(EventType.INTERACTION_REQUIRED,new EventPayload.InteractionRequired(approval()),null);
        String hint=first.delivered("account-a","alice",packet);
        String code=hint.substring(hint.indexOf('#'),hint.indexOf('#')+13);
        var operation=first.reply("account-a","alice","msg-1",code+" 同意");
        assertThrows(ChannelContractException.class,()->first.reply("account-b","alice","msg-1",code+" 同意"));
        assertThrows(ChannelContractException.class,()->first.reply("account-a","bob","msg-1",code+" 同意"));
        first.delivered("account-a","alice",Packets.packet(EventType.INTERACTION_RESOLVED,new EventPayload.InteractionResolved("interaction-1",12,EventPayload.Outcome.APPROVED),null));
        var restarted=new InteractionReplyContext(host.host().protocolState());
        assertEquals(operation,restarted.reply("account-a","alice","msg-1",code+" 同意"));
        assertThrows(ChannelContractException.class,()->restarted.reply("account-a","alice","msg-2",code+" 同意"));
        assertNull(restarted.reply("account-a","alice","msg-3","普通消息"));
    }
}
