package com.netlivecowork.channel.l2;
import static org.junit.jupiter.api.Assertions.*;
import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class SessionCreationTest {
    TestModule t;IngressResolution resolution;
    @BeforeEach void setup() {
        t=new TestModule(false);t.createEntry();
        t.module.directory().bindIdentity(TestModule.ENTRY,1,"USER","alice","owner");
        t.backend.creatable=List.of(new SessionWorkspace("sws-empty","Empty authorized workspace",true));
        t.backend.creator=lookup->CompletableFuture.completedFuture(new SessionCreation.Created(
            new SessionSummary("created-target","Created",null,true,null,t.backend.creatable.getFirst()),
            new TargetOwnership("created-target","owner","verified-creation",1,1,t.clock.instant().plusSeconds(3600))));
        resolution=t.module.ingress(TestModule.ENTRY).resolve(new VerifiedIngress("resolve-create","account",
            new VerifiedIngress.Subject(VerifiedIngress.SubjectKind.USER,"alice"),new VerifiedIngress.ExternalScope("web-view","create-tab"),
            new VerifiedIngress.ExternalReplyTarget(VerifiedIngress.Audience.PERSONAL,"alice",Json.obj().put("conversation","alice")),"event",null)).toCompletableFuture().join();
    }
    @AfterEach void close(){t.module.close();t.db.close();}
    ChannelResponse call(String id,ChannelOperation op) {
        return t.module.ingress(TestModule.ENTRY).submit(resolution.admissionRef(),new IngressRequest("1",id,resolution.identity(),resolution.routing(),op)).toCompletableFuture().join();
    }
    OperationResult result(String id,ChannelOperation op) {return assertInstanceOf(ChannelResponse.Completed.class,call(id,op)).result();}
    void workspace() {
        var list=(OperationResult.WorkspaceList)result("workspaces",new ChannelOperation.WorkspaceList());
        assertEquals(1,list.items().size());assertTrue(list.items().getFirst().canCreate());
        result("choose",new ChannelOperation.WorkspaceSelect("sws-empty",0));
    }
    ChannelOperation create(){return new ChannelOperation.SessionCreate("sws-empty","First message",1);}
    @Test void emptyWorkspaceCreationRegistersOwnerExposureSelectionAndOriginalReply() {
        workspace();var response=call("create",create());
        var result=(OperationResult.SessionCreate)assertInstanceOf(ChannelResponse.Completed.class,response,response.toString()).result();
        assertEquals("created-target",result.selection().targetRef());
        assertTrue(t.module.grants().canAct(TestModule.TENANT,TestModule.WORKSPACE,"owner","created-target","input.submit"));
        assertEquals(1,t.db.query("SELECT * FROM l2_reply_relations WHERE source='SESSION_CREATE'",r->r.getString(1)).size());
        assertEquals(result,PacketJson.decOperationResult(PacketJson.enc(result)));
        assertEquals(response,call("create",create()));assertEquals(List.of(false),t.backend.creationLookups);
    }
    @Test void unknownCreationOnlyLooksUpOriginalAndCannotOverwriteConcurrentSelection() {
        workspace();t.backend.creator=lookup->lookup?CompletableFuture.completedFuture(new SessionCreation.Created(
            new SessionSummary("created-target","Created",null,true,null,t.backend.creatable.getFirst()),
            new TargetOwnership("created-target","owner","verified",1,1,t.clock.instant().plusSeconds(3600)))):
            CompletableFuture.failedFuture(new ChannelContractException(ChannelErrorCode.OUTCOME_UNKNOWN,"lost"));
        assertInstanceOf(ChannelResponse.Unknown.class,call("lost",create()));
        t.backend.creatable=List.of(t.backend.creatable.getFirst(),new SessionWorkspace("sws-other","Another authorized workspace",true));
        result("reload-workspaces",new ChannelOperation.WorkspaceList());
        result("choose-other",new ChannelOperation.WorkspaceSelect("sws-other",1));
        var recovered=t.module.ingress(TestModule.ENTRY).lookup(resolution.admissionRef(),"lost").toCompletableFuture().join();
        var restored=(OperationResult.SessionCreate)assertInstanceOf(ChannelResponse.Completed.class,recovered).result();
        assertEquals("sws-other",restored.selection().sessionWorkspaceRef());assertNull(restored.selection().targetRef());
        assertEquals(List.of(false,true),t.backend.creationLookups);
    }
    @Test void consoleCommandUsesSelectedAuthorizedWorkspaceAndRejectsReusedRequestContent() {
        workspace();var op=new ChannelOperation.InputSubmit(null,ChannelOperation.Interpretation.COMMAND_AWARE,List.of(new ContentPart.Text("p","/new First message")),null);
        assertInstanceOf(OperationResult.SessionCreate.class,result("console-create",op));
        var rejected=assertInstanceOf(ChannelResponse.Rejected.class,call("console-create",new ChannelOperation.SessionCreate("sws-empty","different",1)));
        assertEquals("REQUEST_CONFLICT",rejected.error().code());assertEquals(List.of(false),t.backend.creationLookups);
    }
    @Test void staleSelectionAndHostDenialNeverGrantSessionAccess() {
        workspace();assertInstanceOf(ChannelResponse.Rejected.class,call("stale",new ChannelOperation.SessionCreate("sws-empty","hello",0)));
        assertTrue(t.backend.creationLookups.isEmpty());
        t.backend.creator=lookup->CompletableFuture.failedFuture(new SessionCreation.NotStarted(ChannelErrorCode.PERMISSION_DENIED,"Workspace revoked"));
        assertInstanceOf(ChannelResponse.Rejected.class,call("denied",create()));
        assertNull(t.module.directory().target("created-target"));
    }
    @Test void authorizationFailureDuringReconciliationDoesNotEraseUnknownCreation() {
        workspace();var attempts=new java.util.concurrent.atomic.AtomicInteger();
        t.backend.creator=lookup->{
            int attempt=attempts.incrementAndGet();
            if(attempt==1)return CompletableFuture.failedFuture(new ChannelContractException(ChannelErrorCode.OUTCOME_UNKNOWN,"Acknowledgement was lost"));
            if(attempt==2)return CompletableFuture.failedFuture(new ChannelContractException(ChannelErrorCode.AUTH_REQUIRED,"Login expired during lookup"));
            return CompletableFuture.completedFuture(new SessionCreation.Created(
                new SessionSummary("created-target","Created",null,true,null,t.backend.creatable.getFirst()),
                new TargetOwnership("created-target","owner","verified",1,1,t.clock.instant().plusSeconds(3600))));
        };
        assertInstanceOf(ChannelResponse.Unknown.class,call("auth-recovery",create()));
        assertInstanceOf(ChannelResponse.Unknown.class,t.module.ingress(TestModule.ENTRY).lookup(resolution.admissionRef(),"auth-recovery").toCompletableFuture().join());
        assertInstanceOf(ChannelResponse.Completed.class,t.module.ingress(TestModule.ENTRY).lookup(resolution.admissionRef(),"auth-recovery").toCompletableFuture().join());
        assertEquals(List.of(false,true,true),t.backend.creationLookups);
    }

}
