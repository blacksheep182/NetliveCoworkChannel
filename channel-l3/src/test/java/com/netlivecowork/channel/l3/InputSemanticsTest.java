package com.netlivecowork.channel.l3;

import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InputSemanticsTest {
    @Test void preservesBackendContinuationAndRawWaiting() {
        var n=Json.obj().put("status","waiting").put("revision",17)
            .put("observed_at","2026-10-31T09:00:00Z").put("raw_status","PAUSED");
        n.set("input",Json.obj().put("allowed",true).put("mode","continue").put("reason","user_turn"));
        var actual=BackendProtocol.sessionExecution(n);
        assertEquals("waiting",actual.status());assertEquals("PAUSED",actual.rawStatus());
        assertTrue(actual.input().allowed());assertEquals("continue",actual.input().mode());
        var result=new OperationResult.SessionGet(new SessionSummary("target1","会话",null,true,actual));
        assertEquals(result,PacketJson.decOperationResult(PacketJson.enc(result)));
    }
    @Test void legacyWaitingDoesNotInventContinuation() {
        var n=Json.obj().put("status","waiting").put("revision",1).put("observed_at","2026-10-31T09:00:00Z");
        assertNull(BackendProtocol.sessionExecution(n).input());
    }
    @Test void rejectsContradictoryOrIncompleteInputFacts() {
        assertThrows(ChannelContractException.class,()->new InputAvailability(true,"blocked","ready"));
        assertThrows(ChannelContractException.class,()->new InputAvailability(true,"continue","interaction_required"));
        assertThrows(ChannelContractException.class,()->new SessionExecutionState("waiting",1,Instant.now(),"PAUSED",null));
        var n=Json.obj().put("status","waiting").put("revision",1).put("observed_at","2026-10-31T09:00:00Z").put("raw_status","PAUSED");
        n.set("input",Json.obj().put("allowed","true").put("mode","continue").put("reason","user_turn"));
        assertThrows(RuntimeException.class,()->BackendProtocol.sessionExecution(n));
    }
}
