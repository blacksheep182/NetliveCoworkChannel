package com.netlivecowork.channel.l3;

import static org.junit.jupiter.api.Assertions.*;
import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.infra.Db;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class ConnectorRoutingTest {
    private static final DeviceSecrets PROTECTION=new DeviceSecrets(){
        public String seal(String v){return Base64.getEncoder().encodeToString(v.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        public String open(String v){return new String(Base64.getDecoder().decode(v),java.nio.charset.StandardCharsets.UTF_8);}
    };
    private L3Module module(Db db){return new L3Module(db,Clock.systemUTC(),TestFixtures.settings(),new TestFixtures.RecordingIntake(),
        ref->"backend-secret",TestFixtures.identities(),PROTECTION);}
    private void connector(L3Module m){
        TestFixtures.seed(m,"http://127.0.0.1:1");
        m.registry().upsertService(new BackendService(TestFixtures.SERVICE,TestFixtures.TENANT,TestFixtures.WORKSPACE,"Desktop",null,
            AccessType.CONNECTOR,"credential",true,"device-1"));
    }
    private DeviceRegistry.Credential credential(L3Module m){
        var ticket=m.devices().issue(new DeviceAccess.Authorization(TestFixtures.TENANT,TestFixtures.WORKSPACE,UUID.randomUUID().toString(),TestFixtures.SERVICE,"device-1"));
        return m.devices().redeem(ticket.ticket(),"a-fixed-original-exchange-secret-123456789");
    }
    static class Wire implements ConnectorHub.Wire {
        final List<String> sent=new CopyOnWriteArrayList<>();boolean closed;
        public void send(String s)throws IOException{sent.add(s);}
        public void close(){closed=true;}
    }
    @Test void cloudAndSameMachineUseRealHttpWithoutDeviceConnections()throws Exception{
        for(AccessType type:List.of(AccessType.CLOUD,AccessType.LOCAL))try(Db db=Db.memory();
            TestBackend backend=new TestBackend(c->TestBackend.Response.json(200,"{\"ok\":true}"));L3Module m=module(db)){
            TestFixtures.seed(m,backend.baseUrl());
            var service=new BackendService(TestFixtures.SERVICE,TestFixtures.TENANT,TestFixtures.WORKSPACE,"Direct",backend.baseUrl(),type,null,true);
            var transport=new RoutedBackendTransport(new PythonBackendClient(TestFixtures.settings(),ServiceCredentialResolver.NOOP),m.connectors());
            assertEquals(200,transport.exchange(service,"GET","/api/v1/channel/v1/probe",null,Map.of()).status());
            assertEquals(1,backend.callCount());assertEquals("offline",m.connectors().state("device-1"));
        }
    }
    @Test void connectorCarriesProtocolQueriesAndOriginalSubmitWithoutChangingItsBody()throws Exception{
        try(Db db=Db.memory();TestBackend backend=new TestBackend(c->{
            String key=Json.read(c.body()).path("request_key").asText();
            return TestBackend.Response.json(202,Json.write(Json.obj().put("request_key",key).put("status","accepted")
                .put("accepted_at",Instant.now().toString()).put("retain_until",Instant.now().plusSeconds(60).toString())));
        });L3Module m=module(db)){
            connector(m);var credential=credential(m);var current=new AtomicReference<ConnectorHub.Connection>();
            var frames=new CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();var http=HttpClient.newHttpClient();
            Wire wire=new Wire(){public void send(String text)throws IOException{
                super.send(text);var frame=Json.read(text);if(!frame.path("type").asText().equals("request"))return;frames.add(frame);
                try{var req=HttpRequest.newBuilder(URI.create(backend.baseUrl()+frame.path("path").asText()));
                    frame.path("headers").fields().forEachRemaining(e->req.header(e.getKey(),e.getValue().asText()));
                    req.method(frame.path("method").asText(),frame.path("body").isNull()?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(frame.path("body").asText()));
                    var reply=http.send(req.build(),HttpResponse.BodyHandlers.ofString());
                    current.get().receive(Json.write(Json.obj().put("type","response").put("id",frame.path("id").asText())
                        .put("generation",current.get().generation()).put("status",reply.statusCode()).put("body",reply.body()).set("headers",Json.obj())));
                }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
            }};
            current.set(m.connectors().open(credential.token(),wire));
            var command=TestFixtures.submit("op-connector","same original message");
            var outcome=m.access().submitCommand(command,TestFixtures.call()).toCompletableFuture().get();
            assertEquals(DispatchStatus.AGENT_ACCEPTED,outcome.status());assertEquals(1,backend.callCount());
            assertTrue(frames.stream().anyMatch(f->f.path("path").asText().endsWith("/facts")));
            assertTrue(frames.stream().anyMatch(f->f.path("path").asText().endsWith("/capabilities")));
            var submitted=Json.read(backend.lastCall().body());assertTrue(submitted.has("request_key"));assertFalse(submitted.has("request"));
            assertFalse(submitted.has("deviceRef"));assertFalse(submitted.has("generation"));
            assertTrue(backend.lastCall().body().contains("same original message"));
            assertNull(m.registry().service(TestFixtures.SERVICE).baseUrl());
        }
    }
    @Test void offlineDeviceIsPendingAndNeverFallsBackToHttp()throws Exception{
        try(Db db=Db.memory();L3Module m=module(db)){connector(m);
            var result=m.access().submitCommand(TestFixtures.submit("offline","hello"),TestFixtures.call()).toCompletableFuture().get();
            assertEquals(DispatchStatus.NOT_SENT,result.status());assertNull(result.receipt());
            assertEquals("REGISTERED",TestFixtures.scalar(db,"SELECT phase FROM dispatches WHERE operation_ref='offline'"));
        }
    }
    @Test void oneTimeTicketSupportsOnlyTheOriginalExchangeAndRevocationBlocksRecovery(){
        try(Db db=Db.memory();L3Module m=module(db)){connector(m);
            var grant=new DeviceAccess.Authorization(TestFixtures.TENANT,TestFixtures.WORKSPACE,"enroll-1",TestFixtures.SERVICE,"device-1");
            var t=m.devices().issue(grant);String exchange="original-exchange-123456789012345678901";
            var first=m.devices().redeem(t.ticket(),exchange);assertEquals(first,m.devices().redeem(t.ticket(),exchange));
            assertThrows(ChannelContractException.class,()->m.devices().issue(grant));
            assertThrows(ChannelContractException.class,()->m.devices().redeem(t.ticket(),exchange+"other"));
            assertEquals("device-1",m.devices().authenticate(first.token()));m.connectors().revoke("device-1");
            assertThrows(ChannelContractException.class,()->m.devices().authenticate(first.token()));
            assertThrows(ChannelContractException.class,()->m.devices().redeem(t.ticket(),exchange));
            assertFalse(TestFixtures.scalar(db,"SELECT token_digest FROM connector_devices WHERE device_ref='device-1'").contains(first.token()));
        }
    }
    @Test void sameDeviceReferenceCannotJoinDifferentResourceDomains()throws Exception{
        try(Db db=Db.memory();L3Module m=module(db)){connector(m);var credential=credential(m);
            m.connectors().open(credential.token(),new Wire());
            var foreign=new BackendService("foreign-service","other-tenant",TestFixtures.WORKSPACE,"Foreign",null,
                AccessType.CONNECTOR,"credential",true,"device-1");
            m.registry().upsertService(foreign);
            assertThrows(ChannelContractException.class,()->m.devices().issue(new DeviceAccess.Authorization(
                "other-tenant",TestFixtures.WORKSPACE,"foreign-enrollment","foreign-service","device-1")));
            assertThrows(ChannelContractException.class,()->m.connectors().exchange(foreign,"GET",
                "/api/v1/channel/v1/capabilities",null,Map.of()));
            assertEquals("online",m.connectors().state("device-1"));
        }
    }
    @Test void reenrollmentNeverRevivesOldQueuedOrNotYetRegisteredOperations()throws Exception{
        try(Db db=Db.memory();L3Module m=module(db)){connector(m);credential(m);
            var queued=TestFixtures.submit("queued-before-revocation","old");
            var orphan=TestFixtures.submit("not-yet-dispatched-before-revocation","old");
            var store=new DispatchStore(db,Clock.systemUTC());
            store.create(queued,new TargetResolver(m.registry()).resolve(TestFixtures.TARGET,TestFixtures.TENANT,TestFixtures.WORKSPACE));
            m.registry().upsertService(new BackendService(TestFixtures.SERVICE,TestFixtures.TENANT,TestFixtures.WORKSPACE,"Desktop",null,
                AccessType.CONNECTOR,"credential",false,"device-1"));
            m.connectors().revoke("device-1");
            assertTrue(store.closed(queued,TestFixtures.call()));
            connector(m); // Explicitly restore the service; old operations must remain closed.
            var fresh=credential(m);m.connectors().open(fresh.token(),new Wire());
            var rejected=m.access().submitCommand(orphan,TestFixtures.call()).toCompletableFuture().get();
            assertEquals(DispatchStatus.NOT_SENT,rejected.status());assertEquals("PERMISSION_DENIED",rejected.error().code());
            assertTrue(store.closed(orphan,TestFixtures.call()));
            assertTrue(m.devices().permitsOriginal(TestFixtures.SERVICE,Instant.now().plusSeconds(1)));
        }
    }
    @Test void expiredAndCrossDomainEnrollmentAreRejected(){
        try(Db db=Db.memory();L3Module m=module(db)){connector(m);
            var grant=new DeviceAccess.Authorization(TestFixtures.TENANT,TestFixtures.WORKSPACE,"expired",TestFixtures.SERVICE,"device-1");
            var ticket=m.devices().issue(grant);db.update("UPDATE connector_enrollments SET expires_at=?",Instant.EPOCH);
            assertThrows(ChannelContractException.class,()->m.devices().redeem(ticket.ticket(),"original-exchange-123456789012345678901"));
            assertThrows(ChannelContractException.class,()->m.devices().issue(new DeviceAccess.Authorization("other",TestFixtures.WORKSPACE,"cross",TestFixtures.SERVICE,"device-1")));
        }
    }
    @Test void newConnectionReplacesOldAndLateDisconnectCannotRemoveNewRoute()throws Exception{
        try(Db db=Db.memory();L3Module m=module(db)){connector(m);var credential=credential(m);var oldWire=new Wire();var newWire=new Wire();
            var old=m.connectors().open(credential.token(),oldWire);var next=m.connectors().open(credential.token(),newWire);
            assertTrue(next.generation()>old.generation());assertTrue(oldWire.closed);old.disconnected();
            assertEquals("online",m.connectors().state("device-1"));
            assertThrows(IOException.class,()->old.receive(Json.write(Json.obj().put("type","ping").put("generation",old.generation()))));
            assertEquals("online",m.connectors().state("device-1"));
            m.connectors().revoke("device-1");assertTrue(newWire.closed);assertEquals("revoked",m.connectors().state("device-1"));
        }
    }
    @Test void restartedHubStartsOfflineAndKeepsPersistentGeneration()throws Exception{
        try(Db db=Db.memory()){
            DeviceRegistry.Credential credential;long generation;
            try(L3Module m=module(db)){connector(m);credential=credential(m);generation=m.connectors().open(credential.token(),new Wire()).generation();}
            try(L3Module m=module(db)){assertEquals("offline",m.connectors().state("device-1"));assertTrue(m.connectors().open(credential.token(),new Wire()).generation()>generation);}
        }
    }
    @Test void crossServiceEventCannotBorrowAnotherSink()throws Exception{
        try(Db db=Db.memory();L3Module m=module(db)){connector(m);var credential=credential(m);Wire wire=new Wire();var c=m.connectors().open(credential.token(),wire);
            m.events().registerSink("other-sink","another-service","sink-token");
            var headers=Json.obj().put("Authorization","Bearer sink-token").put("X-Event-Sink-Ref","other-sink")
                .put("X-Backend-Resource-Epoch","1").put("X-Event-Sink-Revision","1");
            c.receive(Json.write(Json.obj().put("type","event").put("id","event-1").put("generation",c.generation())
                .put("serviceRef",TestFixtures.SERVICE).<com.fasterxml.jackson.databind.node.ObjectNode>set("headers",headers).set("body",Json.obj())));
            assertEquals(403,Json.read(wire.sent.getLast()).path("status").asInt());
        }
    }
    @Test void closedBeforeFirstSubmitSurvivesRestartAndPreventsAllHttp()throws Exception{
        try(Db db=Db.memory();L3Module m=module(db)){connector(m);var command=TestFixtures.submit("close-first","hello");
            assertTrue(m.access().closeUnsent(command,TestFixtures.call()).toCompletableFuture().get());
            assertEquals(DispatchStatus.NOT_SENT,m.access().lookupOperation("close-first",TestFixtures.call()).toCompletableFuture().get().status());
            assertEquals(DispatchStatus.NOT_SENT,m.access().submitCommand(command,TestFixtures.call()).toCompletableFuture().get().status());
            assertNull(new DispatchStore(db,Clock.systemUTC()).load("close-first"));
            assertTrue(new DispatchStore(db,Clock.systemUTC()).closed(command,TestFixtures.call()));
        }
    }
    @Test void closeAndClaimHaveExactlyOneWinnerAndUnknownCannotBeClosed()throws Exception{
        try(Db db=Db.memory();L3Module m=module(db);var executor=Executors.newVirtualThreadPerTaskExecutor()){
            connector(m);var store=new DispatchStore(db,Clock.systemUTC());var resolved=new TargetResolver(m.registry()).resolve(TestFixtures.TARGET,TestFixtures.TENANT,TestFixtures.WORKSPACE);
            for(int i=0;i<40;i++){
                var command=TestFixtures.submit("race-"+i,"hello");store.create(command,resolved);var barrier=new CyclicBarrier(2);
                var claim=executor.submit(()->{barrier.await();return store.claim(command.context().operationRef(),"owner",Instant.now(),Duration.ofSeconds(10));});
                var close=executor.submit(()->{barrier.await();return store.closeUnsent(command,TestFixtures.call());});
                assertNotEquals(claim.get()!=null,close.get());
                if(claim.get()!=null)assertFalse(store.closeUnsent(command,TestFixtures.call()));
            }
        }
    }
    @Test void possiblePreviousWriteRemainsUnknownAcrossReconnectAndLateErrorsCannotUndoAcceptance()throws Exception{
        try(Db db=Db.memory();L3Module m=module(db)){connector(m);var command=TestFixtures.submit("unknown","hello");var store=new DispatchStore(db,Clock.systemUTC());
            store.create(command,new TargetResolver(m.registry()).resolve(TestFixtures.TARGET,TestFixtures.TENANT,TestFixtures.WORKSPACE));
            store.claim("unknown","old",Instant.now(),Duration.ofSeconds(1));credential(m);
            assertEquals(DispatchStatus.OUTCOME_UNKNOWN,m.access().submitCommand(command,TestFixtures.call()).toCompletableFuture().get().status());
            store.recordAccepted("unknown",null);store.recordUnknown("unknown",null,"late disconnect");
            assertEquals(Dispatch.Phase.AGENT_ACCEPTED,store.load("unknown").phase());
        }
    }
    @Test void transportRejectsArbitraryProxyRoutesAndCloudPlaintextEndpoints(){
        for(String path:List.of("https://other/","/api/v1/channel/v1/../admin","/api/v1/channel/v1/%252e%252e/admin","/api/v1/sessions/1","/api/v1/channel/v1/xx#fragment"))assertFalse(ConnectorHub.allowed("GET",path),path);
        assertThrows(IllegalArgumentException.class,()->new BackendService("s","t","w","Cloud","http://remote.example",AccessType.CLOUD,null,true));
    }
}
