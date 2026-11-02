package com.netlivecowork.channel.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.netlivecowork.channel.contract.ChannelResponse;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.l1.web.WebAdapterInstance;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebControllerTest {
    private WebAdapterInstance adapter;
    private WebController controller;

    @BeforeEach void setup() {
        var runtime = mock(ChannelRuntime.class, RETURNS_DEEP_STUBS);
        adapter = mock(WebAdapterInstance.class);
        when(runtime.l2().connections().instance("web-test")).thenReturn(adapter);
        when(adapter.streamId()).thenReturn("new-stream");
        controller = new WebController(runtime);
    }

    @Test void webFacadeConvertsPlainParametersThroughExistingAdapterMethods() {
        var done=new ChannelResponse.Completed("nav",new com.netlivecowork.channel.contract.OperationResult.SessionSelect("scope","target",3,"sws"));
        when(adapter.selectSession("secret","nav","view-123456789012","target",2)).thenReturn(done);
        var body=Json.obj().put("requestId","nav").put("viewId","view-123456789012").put("targetRef","target").put("expectedBindingRevision",2);
        assertEquals(200,controller.selectSession("web-test",body,"Bearer secret").getStatusCode().value());
        verify(adapter).selectSession("secret","nav","view-123456789012","target",2);
        when(adapter.getSelection("secret","current","view-123456789012")).thenReturn(new ChannelResponse.Completed("current",
            new com.netlivecowork.channel.contract.OperationResult.SelectionGet(new com.netlivecowork.channel.contract.NavigationSelection("scope","sws","target",3))));
        assertEquals(200,controller.selection("web-test",Json.obj().put("requestId","current").put("viewId","view-123456789012"),"Bearer secret").getStatusCode().value());
        verify(adapter).getSelection("secret","current","view-123456789012");
        when(adapter.submitChat("secret","send","/use text","target","view-123456789012",3)).thenReturn(new ChannelResponse.Accepted("send","op",null));
        body.put("requestId","send").put("expectedBindingRevision",3).put("text","/use text");
        assertEquals(200,controller.chat("web-test",body,"Bearer secret").getStatusCode().value());
        verify(adapter).submitChat("secret","send","/use text","target","view-123456789012",3);
    }

    @Test void oldStreamResetsCursorBeforeReadingBufferedMessages() {
        when(adapter.poll("secret", 0)).thenReturn(List.of(new WebAdapterInstance.Delivered(1, "message.ready", "助手", "新回复")));
        var response = controller.poll("web-test", null, 900, "old-stream", "Bearer secret");
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().path("next").asInt());
        assertEquals("new-stream", response.getBody().path("streamId").asText());
        assertEquals("新回复", response.getBody().path("messages").get(0).path("text").asText());
        verify(adapter).poll("secret", 0);
    }

    @Test void normalChatUsesBearerAndTheAdaptersLiteralChatPort() {
        when(adapter.submitChat("secret","chat-1","/list is text","target-2"))
            .thenReturn(new ChannelResponse.Accepted("chat-1","op-1",null));
        var body=Json.obj().put("requestId","chat-1").put("text","/list is text").put("targetRef","target-2");
        assertEquals(200,controller.chat("web-test",body,"Bearer secret").getStatusCode().value());
        verify(adapter).submitChat("secret","chat-1","/list is text","target-2");
        verify(adapter,never()).submitInbound(any(),any(),any(),any());
        assertEquals(401,controller.chat("web-test",body,"Basic secret").getStatusCode().value());
    }

    @Test void stateQueryAndRepliesCarryExplicitLogicalSessionRefs() {
        when(adapter.getSession("secret","query-1","target-2")).thenReturn(new ChannelResponse.Completed("query-1",
            new com.netlivecowork.channel.contract.OperationResult.SessionGet(
                com.netlivecowork.channel.contract.SessionSummary.of("target-2","Second"))));
        assertEquals(200,controller.session("web-test",Json.obj().put("requestId","query-1").put("targetRef","target-2"),
            "Bearer secret").getStatusCode().value());
        verify(adapter).getSession("secret","query-1","target-2");
        when(adapter.poll("secret",0)).thenReturn(List.of(new WebAdapterInstance.Delivered(1,"message.ready","助手","第二条回复","target-2")));
        assertEquals("target-2",controller.poll("web-test",null,0,"new-stream","Bearer secret")
            .getBody().path("messages").get(0).path("targetRef").asText());
    }

    @Test void sameStreamAndLegacyPollKeepTheirCursor() {
        when(adapter.poll("secret", 42)).thenReturn(List.of());
        assertEquals(42, controller.poll("web-test", null, 42, "new-stream", "Bearer secret").getBody().path("next").asInt());
        assertEquals(42, controller.poll("web-test", "secret", 42, null, null).getBody().path("next").asInt());
        verify(adapter, times(2)).poll("secret", 42);
    }

    @Test void invalidAuthorizationDoesNotFallBackToLegacyToken() {
        assertEquals(401, controller.poll("web-test", "valid-legacy", 0, null, "Basic invalid").getStatusCode().value());
        verify(adapter, never()).poll(any(), anyLong());
    }

    @Test void lookupUsesBearerAndNeverSubmitsAnInput() {
        when(adapter.lookupInbound("secret", "r-original")).thenReturn(new ChannelResponse.Accepted("r-original", "op-original", "task-original"));
        var response = controller.lookup("web-test", "r-original", "Bearer secret");
        assertEquals("task-original", response.getBody().path("taskRef").asText());
        verify(adapter).lookupInbound("secret", "r-original");
        verify(adapter, never()).submitInbound(any(), any(), any(), any());
    }

    @Test void sessionQueryUsesAuthenticatedTokenAndTypedPagination() {
        when(adapter.listSessions("secret","page-2","20",20)).thenReturn(new ChannelResponse.Completed("page-2",
            new com.netlivecowork.channel.contract.OperationResult.SessionList(List.of(),null)));
        var body=Json.obj().put("requestId","page-2").put("cursor","20").put("limit",20);
        assertEquals(200,controller.sessions("web-test",body,"Bearer secret").getStatusCode().value());
        verify(adapter).listSessions("secret","page-2","20",20);
    }

    @Test void explicitConfirmedTargetIsForwardedToL1() {
        when(adapter.submitInbound("secret", "r-1", "你好", "target-confirmed")).thenReturn(new ChannelResponse.Accepted("r-1", "op-1", null));
        var body = Json.obj().put("token", "secret").put("requestId", "r-1").put("text", "你好").put("targetRef", "target-confirmed");
        assertEquals(200, controller.send("web-test", body).getStatusCode().value());
        verify(adapter).submitInbound("secret", "r-1", "你好", "target-confirmed");
    }
}
