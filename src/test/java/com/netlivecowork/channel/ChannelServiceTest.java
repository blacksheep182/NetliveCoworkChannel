package com.netlivecowork.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.netlivecowork.channel.ChannelConfig.*;

class ChannelServiceTest {
    @TempDir Path root;
    final ObjectMapper json=new ObjectMapper();
    final Target a=new Target("local","workspace-a","ses_a"), b=new Target("local","workspace-b","ses_b");
    HttpServer server;
    ChannelStore store;
    ChannelConfig config;
    ChannelService service;
    BackendClient backend;
    final AtomicInteger sends=new AtomicInteger(), resolves=new AtomicInteger();
    final List<JsonNode> history=new ArrayList<>();
    ArrayNode pending=json.createArrayNode();
    String status="PAUSED";
    boolean loseSendResponse;
    String lastResolvedSession="",lastAction="";

    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1",exchange->{
            String path=exchange.getRequestURI().getPath(), query=exchange.getRequestURI().getQuery();
            JsonNode response=json.createObjectNode(); int code=200;
            try {
                if (path.endsWith("/prepare")) response=json.createObjectNode();
                else if (path.endsWith("/history")) {
                    int end=history.size();
                    if (query!=null && query.contains("before=")) end=Integer.parseInt(java.net.URLDecoder.decode(query.split("before=")[1],java.nio.charset.StandardCharsets.UTF_8).substring(2));
                    int start=Math.max(0,end-200);
                    var page=json.createObjectNode().put("has_more",start>0).put("before","d:"+start);
                    var events=json.createArrayNode(); for(int i=start;i<end;i++) events.add(history.get(i));page.set("events",events);response=page;
                } else if (path.endsWith("/pending")) response=pending;
                else if (path.endsWith("/messages")) {
                    var body=json.readTree(exchange.getRequestBody());
                    if (!body.path("require_explicit_hitl").asBoolean()) throw new IllegalStateException("Missing explicit HITL protection");
                    sends.incrementAndGet();
                    String text=body.path("content").asText();
                    history.add(json.createObjectNode().put("event_id","d:"+history.size()).put("type","text_done").put("text","回复："+text));
                    if (text.equals("ask") || text.equals("approval")) {
                        pending=json.createArrayNode().add(json.createObjectNode().put("id","hitl_1").put("form",text.equals("ask") ? "question" : "approval").put("question","请选择操作"));
                        status="PAUSED_HITL";
                    }
                    if (loseSendResponse) { exchange.close(); return; }
                } else if (path.contains("/hitl/") && !exchange.getRequestMethod().equals("GET")) {
                    resolves.incrementAndGet();lastAction=path.substring(path.lastIndexOf('/')+1);
                    lastResolvedSession=query;pending=json.createArrayNode();status="PAUSED";
                    history.add(json.createObjectNode().put("event_id","d:"+history.size()).put("type","text_done").put("text","已继续执行"));
                    response=json.createObjectNode().put("status",lastAction.equals("reject") ? "rejected" : "accepted");
                } else response=json.createObjectNode().put("status",status).put("llm_account","chosen").put("llm_model","model");
                byte[] bytes=json.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        config=new ChannelConfig("x".repeat(32),root.resolve("channel.db"),
            List.of(new Backend("local",URI.create("http://127.0.0.1:"+server.getAddress().getPort()),null)),
            List.of(new Connection("fake","fake",null,null)),
            List.of(new Binding("alice","fake","wx-alice","chat",List.of(a,b))));
        store=new ChannelStore(config,json);backend=new BackendClient(config,json);
        service=new ChannelService(config,store,backend,new ChannelAdapters(config,List.of(new FakeAdapter())),json);
    }
    @AfterEach void cleanup() throws Exception { if (server!=null) server.stop(0);if (store!=null) store.close(); }
    void input(String id,String text) { service.receive(new ChannelService.Inbound("fake",id,"wx-alice","chat",text,"ctx")); }
    void select() { input("select","/use 1");service.tick(); }
    boolean delivered(String text) { return store.all("outbox").stream().anyMatch(o->o.path("state").asText().equals("sent") && o.path("text").asText().contains(text)); }

    @Test void text_roundtrip_deduplicates_and_preserves_plain_language() {
        select();input("m1","分析切换工作空间的代码");service.tick();input("m1","分析切换工作空间的代码");service.tick();
        assertThat(sends.get()).isEqualTo(1);assertThat(delivered("回复：分析切换工作空间的代码")).isTrue();
        assertThatThrownBy(()->input("m1","different")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void no_binding_or_selection_never_dispatches() {
        input("missing","hello");
        service.receive(new ChannelService.Inbound("fake","other","mallory","chat","hello","ctx"));
        service.tick();assertThat(sends.get()).isZero();assertThat(delivered("尚未授权")).isTrue();
    }
    @Test void unsupported_command_and_incomplete_approval_do_not_run() {
        select();input("c1","/something-dangerous");input("c2","approve");service.tick();assertThat(sends.get()).isZero();
    }
    @Test void question_blocks_plain_text_and_answer_returns_to_original_target() {
        select();input("ask","ask");service.tick();assertThat(delivered("answer hitl_1")).isTrue();
        input("plain","yes");service.tick();assertThat(sends.get()).isEqualTo(1);assertThat(resolves.get()).isZero();
        input("switch","/use 2");input("answer","answer hitl_1 use postgres");service.tick();
        assertThat(resolves.get()).isEqualTo(1);assertThat(lastResolvedSession).isEqualTo("session_id=ses_a");
        assertThat(delivered("已继续执行")).isTrue();
    }
    @Test void approval_requires_matching_action_and_repeated_decision_is_idempotent() {
        select();input("ask","approval");service.tick();
        input("wrong","answer hitl_1 yes");service.tick();assertThat(resolves.get()).isZero();
        input("approve","approve hitl_1");service.tick();
        input("repeat","approve hitl_1");input("opposite","reject hitl_1 no");service.tick();
        assertThat(resolves.get()).isEqualTo(1);assertThat(lastAction).isEqualTo("approve");
    }
    @Test void reject_and_stale_interactions_do_not_approve() {
        select();input("ask","approval");service.tick();input("reject","reject hitl_1 no");input("stale","approve not-yours");service.tick();
        assertThat(lastAction).isEqualTo("reject");assertThat(resolves.get()).isEqualTo(1);
    }
    @Test void lost_dispatch_response_is_unknown_and_never_automatically_repeated() {
        select();loseSendResponse=true;input("lost","hello");service.tick();service.tick();
        assertThat(sends.get()).isEqualTo(1);
        assertThat(store.all("inbox").stream().anyMatch(i->i.path("state").asText().equals("unknown"))).isTrue();
    }
    @Test void history_pagination_replays_every_frame_and_reports_a_gap() {
        for(int i=0;i<450;i++) history.add(json.createObjectNode().put("event_id","d:"+i).put("type","text_done").put("text","frame"+i));
        assertThat(backend.after(a,"d:10")).hasSize(439);
        assertThat(backend.after(a,"")).hasSize(450);
        assertThatThrownBy(()->backend.after(a,"m:old:1")).isInstanceOf(IllegalStateException.class);
    }
    @Test void restart_keeps_dedup_and_marks_in_flight_unknown() throws Exception {
        select();input("saved","hello");service.tick();
        var partial=json.createObjectNode().put("id","partial").put("state","sending");store.put("outbox","partial",partial);
        store.close();store=new ChannelStore(config,json);service=new ChannelService(config,store,backend,new ChannelAdapters(config,List.of(new FakeAdapter())),json);
        input("saved","hello");service.tick();assertThat(sends.get()).isEqualTo(1);
        assertThat(store.get("outbox","partial").path("state").asText()).isEqualTo("unknown");
    }
    @Test void revoked_target_suppresses_pending_delivery() {
        select();input("m1","hello");
        var limited=new ChannelConfig(config.adminToken(),config.dataFile(),config.backends(),config.connections(),List.of());
        service=new ChannelService(limited,store,backend,new ChannelAdapters(limited,List.of(new FakeAdapter())),json);service.tick();assertThat(sends.get()).isZero();
    }
    @Test void rebinding_identity_under_same_id_revokes_old_unsent_messages() {
        select();input("old-question","ask");service.tick();input("old-identity","hello");
        var changed=new ChannelConfig(config.adminToken(),config.dataFile(),config.backends(),config.connections(),
            List.of(new Binding("alice","fake","new-user","new-chat",List.of(a,b))));
        service=new ChannelService(changed,store,backend,new ChannelAdapters(changed,List.of(new FakeAdapter())),json);service.tick();
        service.receive(new ChannelService.Inbound("fake","new-answer","new-user","new-chat","answer hitl_1 stolen","ctx"));
        service.receive(new ChannelService.Inbound("fake","new-message","new-user","new-chat","hello","ctx"));service.tick();
        assertThat(sends.get()).isEqualTo(1);assertThat(resolves.get()).isZero();
        assertThat(store.get("inbox",ChannelService.key("fake","new-message")).path("state").asText()).isEqualTo("rejected");
    }
    @Test void outbox_and_cursor_commit_together_even_when_commit_fails() {
        select();status="RUNNING";
        var stream=json.createObjectNode().put("id","stream").put("binding","alice").put("active",true).put("cursor","");
        stream.set("target",json.valueToTree(a));stream.set("source",json.valueToTree(new ChannelService.Inbound("fake","start","wx-alice","chat","hello","ctx")));
        store.put("stream","stream",stream);
        history.add(json.createObjectNode().put("event_id","d:0").put("type","text_done").put("text","atomic reply"));
        var failing=spy(store);doThrow(new IllegalStateException("disk full")).when(failing).put(eq("stream"),eq("stream"),argThat(n->n.path("cursor").asText().equals("d:0")));
        new ChannelService(config,failing,backend,new ChannelAdapters(config,List.of(new FakeAdapter())),json).tick();
        assertThat(store.get("stream","stream").path("cursor").asText()).isEmpty();
        assertThat(delivered("atomic reply")).isFalse();service.tick();assertThat(delivered("atomic reply")).isTrue();
    }
}
