package com.netlivecowork.channel.app;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.Json;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
class CoworkSessionAccessControllerTest {
    ChannelRuntime runtime;CoworkSessionAccessController controller;
    SessionAccess.Manager manager=new SessionAccess.Manager("actual-owner","verified",Instant.now().plusSeconds(30),"w3","actual-user");
    @BeforeEach void setup(){runtime=mock(ChannelRuntime.class,RETURNS_DEEP_STUBS);controller=new CoworkSessionAccessController(runtime);when(runtime.coworkManager("service","Bearer server-only","actual-w3-proof")).thenReturn(manager);}
    @Test void realAuthenticatedManagerIsTheOnlyPrincipalPassedToL2() {
        when(runtime.l2().getSessionAccess(manager)).thenReturn(new SessionAccess.View(3,"actual-owner",List.of()));
        var response=controller.get("service","Bearer server-only","actual-w3-proof");assertEquals(200,response.getStatusCode().value());
        assertEquals("actual-owner",response.getBody().path("principalRef").asText());verify(runtime.l2()).getSessionAccess(manager);
    }
    @Test void emptyExactSelectionIsSavedWithCasAndOriginalId() {
        var body=Json.obj().put("requestId","r").put("expectedRevision",3);body.putArray("targetRefs");
        when(runtime.l2().saveSessionAccess(manager,new SessionAccess.Save("r",3,List.of()))).thenReturn(new SessionAccess.Saved("r",4,List.of()));
        var response=controller.save("service","Bearer server-only","actual-w3-proof",body);assertEquals("committed",response.getBody().path("status").asText());
        verify(runtime.l2()).saveSessionAccess(manager,new SessionAccess.Save("r",3,List.of()));
    }
    @Test void forgedOwnerAndPageTokenFieldsAreRejectedBeforeAuthentication() {
        for(String field:List.of("owner","principalRef","token","session_id")) {
            var body=Json.obj().put("requestId","r").put("expectedRevision",0).put(field,"forged");body.putArray("targetRefs");
            assertEquals(400,controller.save("service","Bearer server-only","actual-w3-proof",body).getStatusCode().value());
        }
        verify(runtime,never()).coworkManager(any(),any(),any());
    }
    @Test void serviceCredentialWithoutCurrentW3ProofFailsClosed() {
        when(runtime.coworkManager("service","Bearer server-only",null)).thenThrow(new ChannelContractException(ChannelErrorCode.AUTH_REQUIRED,"W3 required"));
        assertEquals(401,controller.get("service","Bearer server-only",null).getStatusCode().value());verify(runtime,never()).l2();
    }
    @Test void originalLookupDoesNotRepeatTheSave() {
        when(runtime.l2().lookupSessionAccess(manager,"r")).thenReturn(new SessionAccess.Saved("r",4,List.of()));
        assertEquals("r",controller.lookup("service","r","Bearer server-only","actual-w3-proof").getBody().path("requestId").asText());
        verify(runtime.l2(),never()).saveSessionAccess(any(),any());
    }
}
