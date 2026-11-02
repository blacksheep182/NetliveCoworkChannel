package com.netlivecowork.channel.l3;
import static org.junit.jupiter.api.Assertions.*;
import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.*;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class WorkspaceMetadataTest {
    @TempDir Path temp;
    private ReceiptPacket query(boolean negotiated,boolean returned) throws Exception {
        var body=Json.obj().put("session_id","session-1").put("resource_epoch",1).put("title","Real")
            .put("status","completed").put("revision",1).put("observed_at","2026-11-01T00:00:00Z");
        if(returned)body.set("workspace",Json.obj().put("workspace_id","ws-0123456789abcdef0123456789abcdef").put("label","project"));
        try(var db=TestFixtures.db(temp);var backend=new TestBackend(call->TestBackend.Response.json(200,Json.write(body)))) {
            backend.workspaces=negotiated;
            try(var module=new L3Module(db,Clock.systemUTC(),TestFixtures.settings(),new TestFixtures.RecordingIntake(),ServiceCredentialResolver.NOOP,TestFixtures.identities())) {
                TestFixtures.seed(module,backend.baseUrl());
                var receipt=module.access().query(TestFixtures.command("get","target-1",CommandType.SESSION_GET,new CommandPayload.SessionGet()),TestFixtures.call()).toCompletableFuture().get();
                assertTrue(backend.workspaceHeaders.stream().anyMatch(s->s.contains("session-workspaces-v1")));
                return receipt;
            }
        }
    }
    @Test void negotiatedRealHttpMetadataBecomesOpaquePublicGrouping() throws Exception {
        var result=assertInstanceOf(ReceiptPayload.Completed.class,query(true,true).payload());
        var summary=((OperationResult.SessionGet)result.result()).session();
        assertEquals("project",summary.workspace().label());assertTrue(summary.workspace().sessionWorkspaceRef().startsWith("sws-"));
        assertEquals(result.result(),PacketJson.decOperationResult(PacketJson.enc(result.result())));
    }
    @Test void missingAndUnnegotiatedWorkspaceFactsAreRejected() throws Exception {
        assertInstanceOf(ReceiptPayload.Rejected.class,query(true,false).payload());
        assertInstanceOf(ReceiptPayload.Rejected.class,query(false,true).payload());
        assertNull(((OperationResult.SessionGet)assertInstanceOf(ReceiptPayload.Completed.class,query(false,false).payload()).result()).session().workspace());
    }
}
