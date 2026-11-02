package com.netlivecowork.channel.l3;

import static org.junit.jupiter.api.Assertions.*;

import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.infra.Db;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Protocol response and durable callback binding tests need no network server. */
class BackendProtocolRegressionTest {
    @TempDir Path directory;
    private Db db;
    private BackendRegistry registry;
    private DispatchStore dispatches;
    private BackendProtocol protocol;
    private EventIngestService events;
    private Dispatch row;

    @BeforeEach void setup() {
        db = TestFixtures.db(directory);
        Clock clock = Clock.systemUTC();
        registry = new BackendRegistry(db,clock);
        registry.upsertService(new BackendService(TestFixtures.SERVICE,TestFixtures.TENANT,TestFixtures.WORKSPACE,
            "Backend","http://127.0.0.1:1",AccessType.LOCAL,null,true));
        registry.upsertTarget(new TargetRegistration(TestFixtures.TARGET,TestFixtures.TENANT,TestFixtures.WORKSPACE,
            "user-1",TestFixtures.SERVICE,TestFixtures.SESSION,1,"Test",null,java.util.List.of(),
            TargetSummary.Availability.ONLINE,1,true));
        dispatches = new DispatchStore(db,clock);
        protocol = new BackendProtocol(null,TestFixtures.identities(),clock,db);
        events = new EventIngestService(db,clock,TestFixtures.settings(),registry,dispatches);
        events.registerSink(TestFixtures.SINK,TestFixtures.SERVICE,"token",1);
        var command = TestFixtures.submit("op-regression","Hello");
        row = dispatches.create(command,new TargetResolver(registry).resolve(TestFixtures.TARGET,
            TestFixtures.TENANT,TestFixtures.WORKSPACE));
        ReceiptPacket receipt = ReceiptPacket.agentAccepted(new Producer(Layer.L3,"l3"),command.context(),
            new Correlation(command.correlation().flowId(),command.packetId()),clock.instant(),"placeholder");
        protocol.bind(row,TestFixtures.call(),new BackendProtocol.Authorized(Map.of(),"digest-1"),TestFixtures.SINK,receipt);
        row = dispatches.claim(row.operationRef(),"worker",clock.instant(),Duration.ofSeconds(30));
    }
    @AfterEach void close() { db.close(); }

    private com.fasterxml.jackson.databind.JsonNode rejection(String key,String digest) {
        var result = Json.obj().put("request_key",key).put("request_digest",digest).put("status","rejected")
            .put("retain_until","2027-01-01T00:00:00Z");
        result.set("error",Json.obj().put("code","SESSION_BUSY").put("message","busy").put("retryable",false));
        return result;
    }

    private String profileEvent(String eventId,String key,String text) {
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)Json.read(TestFixtures.agentEvent(eventId,key,text));
        ((com.fasterxml.jackson.databind.node.ObjectNode)node.get("payload")).put("revision",1);
        return Json.write(node);
    }

    @Test void durableRejectionConvergesAndKeepsOriginalCorrelation() {
        var result = protocol.lookupResult(rejection(row.requestKey(),"digest-1"),row,TestFixtures.call(),dispatches);
        assertEquals(DispatchStatus.NOT_SENT,result.status());
        assertInstanceOf(ReceiptPayload.Rejected.class,result.receipt().payload());
        assertEquals(row.packetId(),result.receipt().correlation().causationId());
        assertEquals(Dispatch.Phase.NOT_SENT,dispatches.load(row.operationRef()).phase());
        assertEquals("UNAVAILABLE", result.error().code());
        assertFalse(result.error().retryable());
        assertTrue(result.error().message().contains("仍在运行"));
    }

    @Test void durablePendingRejectionUsesTheSameReasonAsDirectRejection() {
        var value = rejection(row.requestKey(), "digest-1");
        ((com.fasterxml.jackson.databind.node.ObjectNode) value.path("error")).put("code", "INTERACTION_PENDING");
        var expected = BackendProtocol.rejectionError(value.path("error"));
        var result = protocol.lookupResult(value,row,TestFixtures.call(),dispatches);
        assertEquals(DispatchStatus.NOT_SENT, result.status());
        assertEquals(expected, result.error());
        assertFalse(result.error().retryable());
        assertTrue(result.error().message().contains("CoWork"));
    }

    @Test void absentStateNeverInventsIdleAndPartialOrInvalidSnapshotsAreRejected() {
        assertNull(BackendProtocol.sessionExecution(Json.read("{\"title\":\"Legacy\"}")));
        for (String body : java.util.List.of(
            "{\"status\":\"waiting\"}",
            "{\"status\":\"invented\",\"revision\":1,\"observed_at\":\"2026-10-31T03:00:00Z\"}",
            "{\"status\":\"idle\",\"revision\":0,\"observed_at\":\"2026-10-31T03:00:00Z\"}")) {
            assertThrows(ChannelContractException.class, () -> BackendProtocol.sessionExecution(Json.read(body)));
        }
    }

    @Test void mismatchedRejectionCannotChangeDispatchFacts() {
        assertThrows(ChannelContractException.class,() -> protocol.lookupResult(
            rejection("another-request","digest-1"),row,TestFixtures.call(),dispatches));
        assertThrows(ChannelContractException.class,() -> protocol.lookupResult(
            rejection(row.requestKey(),"another-digest"),row,TestFixtures.call(),dispatches));
        assertEquals(Dispatch.Phase.SENT_UNKNOWN,dispatches.load(row.operationRef()).phase());
    }

    @Test void directRejectionRequiresMatchingRequestKey() {
        var response = Json.obj().put("request_key",row.requestKey());
        response.set("error",Json.obj().put("code","SESSION_BUSY").put("message","busy").put("retryable",false));
        assertTrue(BackendProtocol.definiteRejection(new PythonBackendClient.Reply(409,Json.write(response),null),row.requestKey()));
        response.remove("request_key");
        assertFalse(BackendProtocol.definiteRejection(new PythonBackendClient.Reply(409,Json.write(response),null),row.requestKey()));
    }

    @Test void unknownLookupNeverBecomesPermissionToResend() {
        var response = Json.obj().put("request_key",row.requestKey()).put("status","unknown").put("reason","NOT_FOUND");
        assertEquals(DispatchStatus.OUTCOME_UNKNOWN,protocol.lookupResult(response,row,TestFixtures.call(),dispatches).status());
        assertEquals(Dispatch.Phase.SENT_UNKNOWN,dispatches.load(row.operationRef()).phase());
    }

    @Test void originalCallbackSurvivesPathRepairAndRestartWithSameIdentity() {
        events.registerSink(TestFixtures.SINK,TestFixtures.SERVICE,"new-token",2);
        events = new EventIngestService(db,Clock.systemUTC(),TestFixtures.settings(),registry,dispatches);
        events.registerSink(TestFixtures.SINK,TestFixtures.SERVICE,"new-token",2);
        String body = profileEvent("old-event",row.requestKey(),"Answer");
        assertTrue(events.ingest(TestFixtures.SINK,"new-token",1,1,body).accepted());
        assertTrue(events.ingest(TestFixtures.SINK,"new-token",1,1,body).accepted());
        assertEquals("1",TestFixtures.scalar(db,"SELECT COUNT(*) FROM backend_events"));
        assertFalse(events.ingest(TestFixtures.SINK,"token",1,1,body).accepted());
    }

    @Test void pastOrFutureVersionNeedsExactOriginalTransportBinding() {
        events.registerSink(TestFixtures.SINK,TestFixtures.SERVICE,"token",3);
        assertFalse(events.ingest(TestFixtures.SINK,"token",1,1,
            profileEvent("unknown", "unknown-request","Answer")).accepted());
        String body = profileEvent("event",row.requestKey(),"Answer");
        assertFalse(events.ingest(TestFixtures.SINK,"token",1,2,body).accepted());
        assertFalse(events.ingest(TestFixtures.SINK,"token",1,4,body).accepted());
        assertFalse(events.ingest(TestFixtures.SINK,"token",2,1,body).accepted());
        assertThrows(IllegalArgumentException.class,() -> events.registerSink(TestFixtures.SINK,"other-backend","token",4));
        assertThrows(IllegalArgumentException.class,() -> events.registerSink(TestFixtures.SINK,TestFixtures.SERVICE,"token",2));
    }
}
