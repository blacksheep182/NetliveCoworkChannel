package com.netlivecowork.channel.l3;

import static org.junit.jupiter.api.Assertions.*;
import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.infra.Db;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionCreationBackendTest {
    @TempDir Path temp;
    private static final String WORKSPACE="ws-0123456789abcdef0123456789abcdef";
    private final class Fixture implements AutoCloseable {
        final Db db=TestFixtures.db(temp);
        final TestBackend backend;
        final L3Module module;
        String key,mode="ok";int creates;
        Fixture(boolean sink) throws Exception {
            backend=new TestBackend(call->{
                if(call.path().endsWith("/creation-workspaces") && "catalog-down".equals(mode))return TestBackend.Response.json(503,"{}");
                if(call.path().endsWith("/creation-workspaces"))return TestBackend.Response.json(200,Json.write(Json.obj().put("backend_id","backend-1")
                    .set("items", "denied".equals(mode)?Json.arr():Json.arr().add(Json.obj().put("workspace_id",WORKSPACE).put("label","Project")))));
                if(call.path().endsWith("/session-catalog")) {
                    var catalog=Json.obj().put("backend_id","backend-1");
                    catalog.set("principal",Json.obj().put("subject_issuer","w3").put("subject","user-1"));
                    catalog.set("items",Json.arr().add(Json.obj().put("session_id","created-session").put("resource_epoch",1).put("revision",1)
                        .put("title","Created").put("status","completed").put("workspace_label","Project")));
                    return TestBackend.Response.json(200,Json.write(catalog));
                }
                if(call.path().endsWith("/observe"))return TestBackend.Response.json(200,"{\"status\":\"committed\"}");
                if(call.path().equals(TestBackend.PREFIX+"/sessions")) {
                    creates++;key=Json.text(Json.read(call.body()),"request_key");
                    if("lost".equals(mode))return TestBackend.Response.json(503,"{\"error\":{\"code\":\"OUTCOME_UNKNOWN\"}}");
                    if("preflight-rejected".equals(mode))return TestBackend.Response.json(422,"{\"creation_outcome\":\"not_started\",\"error\":{\"code\":\"INVALID_REQUEST\"}}");
                    return result("wrong-key".equals(mode)?"another-request":key);
                }
                if(call.path().contains("/session-creations/")) {
                    if("expired".equals(mode))return TestBackend.Response.json(403,"{\"error\":{\"code\":\"PERMISSION_DENIED\"}}");
                    return result(key);
                }
                return TestBackend.Response.json(200,Json.write(Json.obj().put("session_id","created-session").put("resource_epoch",1).put("title","Created")
                    .put("status","completed").put("revision",1).put("observed_at",Instant.now().toString())
                    .set("workspace",Json.obj().put("workspace_id",WORKSPACE).put("label","Project"))));
            });
            backend.creation=true;backend.workspaces=true;
            module=new L3Module(db,Clock.systemUTC(),TestFixtures.settings(),new TestFixtures.RecordingIntake(),ServiceCredentialResolver.NOOP,TestFixtures.identities());
            TestFixtures.seed(module,backend.baseUrl());
            if(sink)module.events().registerSink(TestFixtures.SINK,TestFixtures.SERVICE,"fixture-only");
        }
        TestBackend.Response result(String request) {
            return TestBackend.Response.json(200,Json.write(Json.obj().put("backend_id","backend-1").put("request_key",request)
                .put("session_id","created-session").put("resource_epoch",1).put("workspace_id",WORKSPACE).put("title","Created").put("status","completed")));
        }
        String workspace(){return module.access().creationWorkspaces(TestFixtures.call()).toCompletableFuture().join().getFirst().sessionWorkspaceRef();}
        SessionCreation.Created create(String workspace,boolean lookup){return module.access().createSession(TestFixtures.call(),"original-create",workspace,"First message",lookup).toCompletableFuture().join();}
        ChannelContractException failure(String workspace,boolean lookup) {
            return assertInstanceOf(ChannelContractException.class,assertThrows(CompletionException.class,()->create(workspace,lookup)).getCause());
        }
        public void close(){module.close();backend.close();db.close();}
    }
    @Test void creationAndExistingCatalogShareTargetAndWorkspaceMapping() throws Exception {
        try(var f=new Fixture(true)) {
            String workspace=f.workspace();var created=f.create(workspace,false);
            var catalog=f.module.access().discoverOwnedSessions(TestFixtures.call()).toCompletableFuture().join();
            assertEquals(created.session().targetRef(),catalog.candidates().getFirst().summary().targetRef());
            var read=f.module.access().query(TestFixtures.command("read-created",created.session().targetRef(),CommandType.SESSION_GET,new CommandPayload.SessionGet()),TestFixtures.call()).toCompletableFuture().join();
            var summary=((OperationResult.SessionGet)assertInstanceOf(ReceiptPayload.Completed.class,read.payload()).result()).session();
            assertEquals(workspace,summary.workspace().sessionWorkspaceRef());assertEquals(1,f.creates);
        }
    }
    @Test void missingSinkAndWorkspaceDenialAreProvenBeforeAnyCreationWrite() throws Exception {
        try(var f=new Fixture(false)) {assertInstanceOf(SessionCreation.NotStarted.class,f.failure(f.workspace(),false));assertEquals(0,f.creates);}
        try(var f=new Fixture(true)) {String workspace=f.workspace();f.mode="denied";assertInstanceOf(SessionCreation.NotStarted.class,f.failure(workspace,false));assertEquals(0,f.creates);}
        try(var f=new Fixture(true)) {String workspace=f.workspace();f.mode="catalog-down";assertInstanceOf(SessionCreation.NotStarted.class,f.failure(workspace,false));assertEquals(0,f.creates);}
    }
    @Test void temporaryAuthorizationFailureCannotTurnAnUncertainCreationIntoARejection() throws Exception {
        try(var f=new Fixture(true)) {
            String workspace=f.workspace();f.mode="lost";
            assertEquals(ChannelErrorCode.OUTCOME_UNKNOWN,f.failure(workspace,false).code());
            f.mode="expired";var denied=f.failure(workspace,true);
            assertEquals(ChannelErrorCode.PERMISSION_DENIED,denied.code());assertFalse(denied instanceof SessionCreation.NotStarted);
            f.mode="ok";assertNotNull(f.create(workspace,true));assertEquals(1,f.creates);
        }
    }
    @Test void originalRequestIdentityMustBeEchoedBeforeCreationIsConfirmed() throws Exception {
        try(var f=new Fixture(true)) {
            String workspace=f.workspace();f.mode="wrong-key";
            assertEquals(ChannelErrorCode.OUTCOME_UNKNOWN,f.failure(workspace,false).code());
            f.mode="ok";assertNotNull(f.create(workspace,true));assertEquals(1,f.creates);
        }
    }
    @Test void explicitHostPreflightEvidenceAllowsADefinitiveRejection() throws Exception {
        try(var f=new Fixture(true)) {
            String workspace=f.workspace();f.mode="preflight-rejected";
            assertInstanceOf(SessionCreation.NotStarted.class,f.failure(workspace,false));assertEquals(1,f.creates);
        }
    }
    @Test void anExpiredCallCannotStartCreationAfterPreflight() throws Exception {
        try(var f=new Fixture(true)) {
            String workspace=f.workspace();var original=TestFixtures.call();
            var expired=new TrustedCall(original.tenantRef(),original.workspaceRef(),original.entryRef(),original.principalRef(),null,1,Instant.now().minusSeconds(1));
            var error=assertThrows(CompletionException.class,()->f.module.access().createSession(expired,"expired-original",workspace,"First",false).toCompletableFuture().join()).getCause();
            assertInstanceOf(SessionCreation.NotStarted.class,error);assertEquals(0,f.creates);
        }
    }
}
