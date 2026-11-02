package com.netlivecowork.channel.l2;

import static org.junit.jupiter.api.Assertions.*;
import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.*;
import com.netlivecowork.channel.l2.store.DirectoryRepository;
import java.util.*;
import org.junit.jupiter.api.*;

class NavigationTest {
    TestModule t;
    @BeforeEach void setup() {
        t=new TestModule(false);t.createEntry();t.module.management().syncTargets();t.verifiedOwner("owner");
        t.module.directory().upsertTarget(new DirectoryRepository.TargetRow("target-2",TestModule.TENANT,TestModule.WORKSPACE,null,"Second",List.of(),TargetSummary.Availability.ONLINE,null,1));
        t.db.update("UPDATE l2_targets SET resource_epoch=1,owner_principal_ref='owner',evidence_valid_until=? WHERE target_ref='target-2'",t.clock.instant().plusSeconds(604800));
        t.module.directory().bindIdentity(TestModule.ENTRY,1,"USER","alice","owner");
        t.backend.sessionProjection=target->new SessionSummary(target,target,null,true,null,new SessionWorkspace(target.equals("target-1")?"sws-a":"sws-b","same-name"));
    }
    @AfterEach void close(){t.module.close();t.db.close();}
    IngressResolution resolve(String view) {
        return t.module.ingress(TestModule.ENTRY).resolve(new VerifiedIngress("resolve-"+view,"acct-1",
            new VerifiedIngress.Subject(VerifiedIngress.SubjectKind.USER,"alice"),new VerifiedIngress.ExternalScope("web-view",view),
            new VerifiedIngress.ExternalReplyTarget(VerifiedIngress.Audience.PERSONAL,"alice",Json.obj().put("conversation","alice")),"event",null)).toCompletableFuture().join();
    }
    ChannelResponse call(IngressResolution scope,String id,ChannelOperation op) {
        return t.module.ingress(TestModule.ENTRY).submit(scope.admissionRef(),new IngressRequest("1",id,scope.identity(),scope.routing(),op)).toCompletableFuture().join();
    }
    OperationResult done(IngressResolution scope,ChannelOperation op) {
        var r=call(scope,UUID.randomUUID().toString(),op);return assertInstanceOf(ChannelResponse.Completed.class,r,r.toString()).result();
    }
    NavigationSelection selection(IngressResolution s) {return ((OperationResult.WorkspaceList)done(s,new ChannelOperation.WorkspaceList())).selection();}
    void selectA(IngressResolution s) {done(s,new ChannelOperation.WorkspaceSelect("sws-a",0));done(s,new ChannelOperation.SessionSelect("target-1",1));}
    ChannelOperation text(String text) {return new ChannelOperation.InputSubmit(null,ChannelOperation.Interpretation.COMMAND_AWARE,List.of(new ContentPart.Text("p",text)),null);}

    @Test void typedClicksAndCommandsUseTheSameOperationsAndPersistedSelection() {
        var web=resolve("web");var chat=resolve("chat");
        var list=(OperationResult.WorkspaceList)done(web,new ChannelOperation.WorkspaceList());
        assertEquals(2,list.items().size());assertEquals(list.items().get(0).label(),list.items().get(1).label());assertNull(list.selection().targetRef());
        done(chat,text("/workspaces"));done(chat,text("/workspace 1"));done(web,new ChannelOperation.WorkspaceSelect("sws-a",0));
        done(chat,text("/list"));done(chat,text("/use 1"));done(web,new ChannelOperation.SessionSelect("target-1",1));
        assertEquals(selection(web).targetRef(),selection(chat).targetRef());assertEquals(2,selection(chat).revision());
    }
    @Test void workspaceSwitchClearsOnlyItsScopeAndSameWorkspaceIsANoop() {
        var a=resolve("a");var b=resolve("b");selectA(a);selectA(b);
        done(a,new ChannelOperation.WorkspaceSelect("sws-a",2));assertEquals(2,selection(a).revision());assertEquals("target-1",selection(a).targetRef());
        done(a,new ChannelOperation.WorkspaceSelect("sws-b",2));assertNull(selection(a).targetRef());assertEquals("target-1",selection(b).targetRef());
        assertEquals(3,selection(a).revision());
    }
    @Test void crossWorkspaceAndStaleSelectionCannotChangeStateOrSend() {
        var s=resolve("one");selectA(s);
        assertEquals("BINDING_CONFLICT",assertInstanceOf(ChannelResponse.Rejected.class,call(s,"cross",new ChannelOperation.SessionSelect("target-2",2))).error().code());
        done(s,new ChannelOperation.WorkspaceSelect("sws-b",2));done(s,new ChannelOperation.SessionSelect("target-2",3));
        var input=new ChannelOperation.InputSubmit("target-1",ChannelOperation.Interpretation.LITERAL,List.of(new ContentPart.Text("p","hello")),null,2L);
        assertEquals("BINDING_CONFLICT",assertInstanceOf(ChannelResponse.Rejected.class,call(s,"old-send",input)).error().code());
        assertEquals(0,t.backend.submitCalls.get());assertEquals("target-2",selection(s).targetRef());
    }
    @Test void lookupAndReplayNeverReapplyOldSelection() {
        var s=resolve("one");done(s,new ChannelOperation.WorkspaceSelect("sws-a",0));
        var op=new ChannelOperation.SessionSelect("target-1",1);var original=call(s,"switch-original",op);
        done(s,new ChannelOperation.WorkspaceSelect("sws-b",2));
        assertEquals(original,call(s,"switch-original",op));
        assertEquals(original,t.module.ingress(TestModule.ENTRY).lookup(s.admissionRef(),"switch-original").toCompletableFuture().join());
        assertEquals("sws-b",selection(s).sessionWorkspaceRef());assertNull(selection(s).targetRef());
    }
    @Test void currentSelectionIsScopeBoundAndDoesNotQueryBackendOrReplayOldChoice() {
        var s=resolve("lightweight");selectA(s);int reads=t.backend.queryCalls.get();
        var original=assertInstanceOf(ChannelResponse.Completed.class,call(s,"current-old",new ChannelOperation.SelectionGet()));
        assertEquals("target-1",((OperationResult.SelectionGet)original.result()).selection().targetRef());
        assertEquals(reads,t.backend.queryCalls.get());
        done(s,new ChannelOperation.WorkspaceSelect("sws-b",2));reads=t.backend.queryCalls.get();
        assertEquals(original,t.module.ingress(TestModule.ENTRY).lookup(s.admissionRef(),"current-old").toCompletableFuture().join());
        var current=(OperationResult.SelectionGet)done(s,new ChannelOperation.SelectionGet());
        assertEquals("sws-b",current.selection().sessionWorkspaceRef());assertNull(current.selection().targetRef());
        var other=(OperationResult.SelectionGet)done(resolve("another-tab"),new ChannelOperation.SelectionGet());
        assertNull(other.selection().targetRef());assertNull(other.selection().sessionWorkspaceRef());
        assertEquals(reads,t.backend.queryCalls.get());
    }
    @Test void lightweightSelectionReadClearsRevokedChoiceWithoutBackendEnumeration() {
        var s=resolve("revoked-lightweight");selectA(s);int reads=t.backend.queryCalls.get();
        call(s,"before-revoke",new ChannelOperation.SelectionGet());
        t.module.directory().setTargetOwner("target-1","someone-else");
        t.backend.sessionProjection=target->{throw new AssertionError("Selection query must not enumerate backend sessions");};
        var current=((OperationResult.SelectionGet)done(s,new ChannelOperation.SelectionGet())).selection();
        assertNull(current.targetRef());assertNull(current.sessionWorkspaceRef());assertEquals(3,current.revision());
        assertEquals(reads,t.backend.queryCalls.get());
        assertInstanceOf(ChannelResponse.Rejected.class,t.module.ingress(TestModule.ENTRY).lookup(s.admissionRef(),"before-revoke").toCompletableFuture().join());
    }
    @Test void acceptedInputKeepsOriginalTargetAfterSwitchAndOnReplay() {
        var s=resolve("one");selectA(s);
        var input=new ChannelOperation.InputSubmit("target-1",ChannelOperation.Interpretation.LITERAL,List.of(new ContentPart.Text("p","hello")),null,2L);
        var accepted=assertInstanceOf(ChannelResponse.Accepted.class,call(s,"input-original",input));
        done(s,new ChannelOperation.WorkspaceSelect("sws-b",2));
        assertTrue(t.tickUntil(()->t.backend.submitCalls.get()==1));
        assertEquals("target-1",t.backend.commands.stream().filter(c->c.type()==CommandType.INPUT_SUBMIT).findFirst().orElseThrow().context().targetRef());
        assertInstanceOf(ChannelResponse.Accepted.class,call(s,"input-original",input));assertEquals(1,t.backend.submitCalls.get());
        assertEquals("target-1",PacketJson.decCommand(t.module.requests().inboxByOperation(accepted.operationRef()).commandJson()).context().targetRef());
    }
    @Test void revokedWorkspaceDisappearsAndSelectionRevisionAdvancesWithoutFallback() {
        var s=resolve("one");selectA(s);t.module.directory().setTargetOwner("target-1","someone-else");
        var list=(OperationResult.WorkspaceList)done(s,new ChannelOperation.WorkspaceList());
        assertEquals(List.of("sws-b"),list.items().stream().map(SessionWorkspace::sessionWorkspaceRef).toList());
        assertNull(list.selection().targetRef());assertNull(list.selection().sessionWorkspaceRef());assertEquals(3,list.selection().revision());
    }
    @Test void unavailableMetadataDoesNotClearExistingSelectionOrPretendEmpty() {
        var s=resolve("one");selectA(s);t.backend.sessionProjection=target->{throw new IllegalStateException("backend offline");};
        assertInstanceOf(ChannelResponse.Rejected.class,call(s,"offline",new ChannelOperation.WorkspaceList()));
        assertEquals("target-1",t.module.directory().binding(TestModule.TENANT,TestModule.WORKSPACE,s.routing().scopeRef()).targetRef());
    }
    @Test void pageCursorCannotCrossWorkspaceAndUnlistedNumberCannotSelect() {
        var s=resolve("one");assertInstanceOf(ChannelResponse.Rejected.class,call(s,"unlisted",text("/use 1")));
        var first=(OperationResult.SessionList)done(s,new ChannelOperation.SessionList(null,new PageRequest(null,1)));
        assertNotNull(first.nextCursor());done(s,new ChannelOperation.WorkspaceSelect("sws-b",0));
        assertInstanceOf(ChannelResponse.Rejected.class,call(s,"cursor",new ChannelOperation.SessionList(null,new PageRequest(first.nextCursor(),1),"sws-b")));
    }
}
