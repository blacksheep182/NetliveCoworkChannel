package com.netlivecowork.channel.l2;

import static org.junit.jupiter.api.Assertions.*;
import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.adapter.ConnectionPolicy;
import com.netlivecowork.channel.contract.adapter.EntryConfig;
import com.netlivecowork.channel.contract.json.ContentJson;
import com.netlivecowork.channel.contract.json.Json;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class GroupSessionFlowTest {
    private static final String ENTRY="group-weixin", ACCOUNT="bot-a", OWNER="owner";
    @Test void groupSessionsStayWithinOriginAndOnlyBoundOwnerApproves() {
        TestModule t=new TestModule(false,true);
        try {
            EntryConfig entry=new EntryConfig(TestModule.TENANT,TestModule.WORKSPACE,ENTRY,"Weixin",
                "weixin",1,1,1,true,Json.obj(),Map.of(),ConnectionPolicy.defaults(),"default");
            t.module.entries().save(entry,ACCOUNT);t.module.entries().recordObservedAccount(ENTRY,ACCOUNT);
            t.backend.creatable=List.of(new SessionWorkspace("sws-group","Group project",true));
            t.backend.creator=lookup->CompletableFuture.completedFuture(new SessionCreation.Created(
                new SessionSummary("group-target","Group task",null,true,null,t.backend.creatable.getFirst()),
                new TargetOwnership("group-target",OWNER,"verified-creation",1,1,t.clock.instant().plusSeconds(3600))));
            t.backend.sessionProjection=target->new SessionSummary(target,"Group task",null,true,null,
                t.backend.creatable.getFirst());
            AdmissionService admission=service(t);
            var alice=admission.resolve(entry,inbound("alice-1","alice","room-1"));
            assertEquals(ChannelErrorCode.PERMISSION_DENIED,((ChannelResponse.Rejected)call(admission,entry,alice,"before",
                new ChannelOperation.WorkspaceList())).error().errorCode());
            var grant=t.module.openGroupWorkspace(manager(t),ENTRY,alice.routing().scopeRef(),"sws-group");
            assertTrue(grant.active());
            var workspaces=(OperationResult.WorkspaceList)completed(admission,entry,alice,"workspaces",new ChannelOperation.WorkspaceList());
            assertEquals(1,workspaces.items().size());
            completed(admission,entry,alice,"select-workspace",new ChannelOperation.WorkspaceSelect("sws-group",0));
            var created=(OperationResult.SessionCreate)completed(admission,entry,alice,"create",
                new ChannelOperation.SessionCreate("sws-group","Start group task",1));
            assertEquals("group-target",created.session().targetRef());
            var bob=admission.resolve(entry,inbound("bob-1","bob","room-1"));
            var listing=(OperationResult.SessionList)completed(admission,entry,bob,"list",new ChannelOperation.SessionList(null,null));
            assertEquals(List.of("group-target"),listing.items().stream().map(SessionSummary::targetRef).toList());
            var otherGroup=admission.resolve(entry,inbound("charlie-1","charlie","room-2"));
            assertEquals(ChannelErrorCode.PERMISSION_DENIED,((ChannelResponse.Rejected)call(admission,entry,otherGroup,
                "cross-group",new ChannelOperation.SessionGet("group-target"))).error().errorCode());
            assertEquals(ChannelErrorCode.PERMISSION_DENIED,((ChannelResponse.Rejected)call(admission,entry,bob,"private",
                new ChannelOperation.SessionGet("private-target"))).error().errorCode());
            var groupInput=assertInstanceOf(ChannelResponse.Accepted.class,call(admission,entry,bob,"input",
                new ChannelOperation.InputSubmit(null,ChannelOperation.Interpretation.LITERAL,
                    List.of(new ContentPart.Text("p1","Continue")),null)));
            var output=new VerifiedBackendEvent("be-group-1",new EventSource("resource-group","event-group-1"),
                groupInput.operationRef(),"group-target",null,EventType.MESSAGE_READY,t.clock.instant().plusSeconds(1),null,
                new AgentEventPayload.MessageReady(List.of(new NormalizedContentPart.Text("p1","Done",
                    ContentPart.Text.Format.PLAIN)),"backend-message-1",null));
            assertTrue(t.module.intake().receiveEvent(output).toCompletableFuture().join().accepted());
            var deliveries=t.module.deliveries().claimable(10);
            assertFalse(deliveries.isEmpty());
            assertTrue(t.module.groupGrants().currentDelivery(deliveries.getFirst().deliveryId(),t.module.grants()));

            var question=t.module.requests().ensureInteraction(TestModule.TENANT,TestModule.WORKSPACE,
                "group-target",null,"flow-question","backend-question",1,
                Json.write(ContentJson.encode(new InteractionPrompt.Questions(List.of(),
                    List.of(new QuestionItem("q",List.of(new ContentPart.Text("p1","Choose?")),true,
                        new QuestionItem.Input.TextInput())),null))),null,
                alice.routing().scopeRef(),alice.routing().replyTargetRef(),null);
            var answer=new ChannelOperation.InteractionRespond(new InteractionRespondPayload(
                question.interactionRef(),question.revision(),new InteractionResponse.Answer(List.of(
                    new QuestionAnswer.TextAnswer("q",List.of(new ContentPart.Text("a1","yes")))))));
            assertInstanceOf(ChannelResponse.Accepted.class,call(admission,entry,bob,"bob-answer",answer));

            var second=t.module.requests().ensureInteraction(TestModule.TENANT,TestModule.WORKSPACE,
                "group-target",null,"flow-second","backend-second",1,
                Json.write(ContentJson.encode(new InteractionPrompt.Questions(List.of(),
                    List.of(new QuestionItem("q",List.of(new ContentPart.Text("p1","Choose again?")),true,
                        new QuestionItem.Input.TextInput())),null))),null,
                alice.routing().scopeRef(),alice.routing().replyTargetRef(),null);
            var secondAnswer=new ChannelOperation.InteractionRespond(new InteractionRespondPayload(
                second.interactionRef(),second.revision(),new InteractionResponse.Answer(List.of(
                    new QuestionAnswer.TextAnswer("q",List.of(new ContentPart.Text("a1","yes")))))));
            var dave=admission.resolve(entry,inbound("dave-1","dave","room-1"));
            var raced=List.of(
                CompletableFuture.supplyAsync(()->call(admission,entry,bob,"bob-second",secondAnswer)),
                CompletableFuture.supplyAsync(()->call(admission,entry,dave,"dave-second",secondAnswer)));
            var results=raced.stream().map(CompletableFuture::join).toList();
            assertEquals(1,results.stream().filter(ChannelResponse.Accepted.class::isInstance).count());
            assertEquals(1,results.stream().filter(ChannelResponse.Rejected.class::isInstance).count());

            var approval=t.module.requests().ensureInteraction(TestModule.TENANT,TestModule.WORKSPACE,
                "group-target",null,"flow-approval","backend-approval",1,
                Json.write(ContentJson.encode(new InteractionPrompt.Approval(
                    List.of(new ContentPart.Text("p1","Approve?")),
                    new InteractionPrompt.Approval.Operation("deploy",Json.obj(),null),null))),null,
                alice.routing().scopeRef(),alice.routing().replyTargetRef(),null);
            var approve=new ChannelOperation.InteractionRespond(new InteractionRespondPayload(
                approval.interactionRef(),approval.revision(),new InteractionResponse.Approve(null)));
            assertEquals(ChannelErrorCode.PERMISSION_DENIED,((ChannelResponse.Rejected)call(admission,entry,bob,"bob-approve",approve)).error().errorCode());
            var bindings=new OwnerWeixinBindingService(t.db,t.clock,t.module.entries(),t.module.directory());
            var challenge=t.module.beginOwnerWeixinBinding(manager(t),ENTRY);
            bindings.complete(entry,inbound("bind-alice","alice",null),challenge.token());
            var boundAlice=admission.resolve(entry,inbound("alice-2","alice","room-1"));
            var approved=assertInstanceOf(ChannelResponse.Accepted.class,call(admission,entry,boundAlice,"alice-approve",approve));
            assertTrue(t.module.groupGrants().currentOperation(approved.operationRef()));
            t.module.revokeOwnerWeixinBinding(manager(t),ENTRY);
            assertFalse(t.module.groupGrants().currentOperation(approved.operationRef()));

            t.module.revokeGroupWorkspace(manager(t),grant.grantRef(),grant.revision());
            assertFalse(t.module.groupGrants().currentDelivery(deliveries.getFirst().deliveryId(),t.module.grants()));
            assertEquals(ChannelErrorCode.PERMISSION_DENIED,((ChannelResponse.Rejected)call(admission,entry,bob,"after",
                new ChannelOperation.SessionList(null,null))).error().errorCode());
            var unboundAlice=admission.resolve(entry,inbound("alice-3","alice","room-1"));
            assertEquals(ChannelErrorCode.PERMISSION_DENIED,((ChannelResponse.Rejected)call(admission,entry,unboundAlice,"old-target",
                new ChannelOperation.SessionGet("group-target"))).error().errorCode());
        } finally {t.module.close();t.db.close();}
    }

    private static AdmissionService service(TestModule t) {
        return new AdmissionService(t.clock,TestModule.settings(),t.module.entries(),t.module.directory(),
            t.module.requests(),t.module.submit());
    }
    private static SessionAccess.Manager manager(TestModule t) {
        return new SessionAccess.Manager(OWNER,"verified-w3",t.clock.instant().plusSeconds(30),"w3",OWNER);
    }
    private static VerifiedIngress inbound(String event,String sender,String room) {
        boolean group=room!=null;String conversation=group?room:sender;
        return new VerifiedIngress("weixin-"+event,ACCOUNT,
            new VerifiedIngress.Subject(VerifiedIngress.SubjectKind.USER,ACCOUNT+":user:"+sender),
            new VerifiedIngress.ExternalScope(group?"weixin-group":"weixin",conversation),
            new VerifiedIngress.ExternalReplyTarget(group?VerifiedIngress.Audience.SHARED:
                VerifiedIngress.Audience.PERSONAL,conversation,Json.obj().put("conversation",conversation)),event,null);
    }
    private static ChannelResponse call(AdmissionService service,EntryConfig entry,IngressResolution resolved,
                                        String id,ChannelOperation operation) {
        return service.submit(entry,resolved.admissionRef(),new IngressRequest("1",id,resolved.identity(),
            resolved.routing(),operation));
    }
    private static OperationResult completed(AdmissionService service,EntryConfig entry,IngressResolution resolved,
                                             String id,ChannelOperation operation) {
        var response=call(service,entry,resolved,id,operation);
        return assertInstanceOf(ChannelResponse.Completed.class,response,response.toString()).result();
    }
}
