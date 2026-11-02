package com.netlivecowork.channel.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netlivecowork.channel.contract.ChannelDelivery;
import com.netlivecowork.channel.contract.ChannelResponse;
import com.netlivecowork.channel.contract.EventType;
import com.netlivecowork.channel.infra.Digests;
import com.netlivecowork.channel.l1.fake.FakeAdapterInstance;
import com.netlivecowork.channel.l3.IngestResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * 跨层贯通：平台入口 → 受理 → L3 派发 → 后端事件回传 → 规范化 → 固定目的地投递。
 * 后端用真实 HTTP 桩代替 Python 服务，按 backend-http-v1 应答 capabilities/facts/受理，
 * 归属证据经真实 facts 链路落库，而不是手工改写 L2 内部表。
 */
class ChannelRuntimeE2ETest {

    private static final String PREFIX = "/api/v1/channel/v1";
    private static final String BACKEND_ID = "backend-e2e";

    @Test
    void inboundIsDispatchedAndCompleteMessageIsDeliveredOnce() throws Exception {
        AtomicInteger submits = new AtomicInteger();
        AtomicReference<String> lastBody = new AtomicReference<>();
        // facts 起始不可用：无核实归属时保持门控，翻可用后由 syncTargets 走真实链路落库。
        AtomicBoolean factsHealthy = new AtomicBoolean(false);
        HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod()) && (PREFIX + "/capabilities").equals(path)) {
                respond(exchange, 200, """
                    {"protocol_version":"1","profile":"personal-text-v1","backend_id":"%s",\
                    "features":{"resource_facts":true,"delegated_calls":true,"session_read":true,\
                    "message_submit":true,"request_lookup":true,"event_push":true,"event_replay":true},\
                    "limits":{},"retention":{"request_min_seconds":60,"acked_event_min_seconds":60},\
                    "auth":{"service":"bearer","delegation":"backend-adapter","adapter_id":"w3-desktop-session"}}
                    """.formatted(BACKEND_ID));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())
                    && (PREFIX + "/sessions/session-1/facts").equals(path)) {
                if (!factsHealthy.get()) {
                    respond(exchange, 503, "{\"error\":\"unverified\"}");
                    return;
                }
                String epoch = exchange.getRequestHeaders().getFirst("X-Expected-Resource-Epoch");
                Instant now = Instant.now();
                respond(exchange, 200, """
                    {"backend_id":"%s","tenant_id":"tenant-e2e","workspace_id":"workspace-e2e",\
                    "session_id":"session-1","resource_epoch":%s,\
                    "owner":{"subject_issuer":"w3","subject":"w3-user-1"},"revision":1,\
                    "observed_at":"%s","valid_until":"%s",\
                    "capabilities":["session.read","message.submit"],"delegation":{"status":"available"}}
                    """.formatted(BACKEND_ID, epoch == null ? "1" : epoch, now, now.plusSeconds(30)));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && (PREFIX + "/sessions/session-1").equals(path)) {
                respond(exchange,200,"{\"session_id\":\"session-1\",\"resource_epoch\":1,\"title\":\"E2E\",\"status\":\"idle\",\"revision\":1,\"observed_at\":\""+Instant.now()+"\"}");
                return;
            }
            if ("POST".equals(exchange.getRequestMethod())
                    && (PREFIX + "/sessions/session-1/messages").equals(path)) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                lastBody.set(body);
                submits.incrementAndGet();
                String requestKey = com.netlivecowork.channel.contract.json.Json.read(body)
                    .path("request_key").asText();
                respond(exchange, 202, "{\"request_key\":\"" + requestKey + "\",\"status\":\"accepted\","
                    + "\"accepted_at\":\"" + Instant.now() + "\",\"retain_until\":\""
                    + Instant.now().plusSeconds(3_600) + "\"}");
                return;
            }
            respond(exchange, 404, "{}");
        });
        backend.start();

        Path dataDir = Files.createTempDirectory("channel-e2e");
        Path dataFile = dataDir.resolve("channel.db");
        Path proofFile = dataDir.resolve("w3-proof.txt");
        Files.writeString(proofFile, "e2e-proof-secret");
        ChannelProperties properties = new ChannelProperties("admin-token", dataFile.toString(),
            "tenant-e2e", "workspace-e2e", true, "test-key",
            new ChannelProperties.L3("l3-sink-1", "sink-token", false, false, 5_000, 3, 10, 100, 5),
            List.of(new ChannelProperties.Service("svc-local", "本机 Agent",
                "http://127.0.0.1:" + backend.getAddress().getPort(), "LOCAL", null, true,
                BACKEND_ID, "client-e2e", "tenant-e2e", "workspace-e2e", "w3-desktop-session",
                List.of(new ChannelProperties.Principal("caller-e2e", "w3", "w3-user-1",
                    "file:" + proofFile)))),
            List.of(new ChannelProperties.Target("target-a", "svc-local", "session-1", 1, "报表助手", null,
                List.of(), "ONLINE", "caller-e2e", 1, true)),
            List.of(new ChannelProperties.Sink("l3-sink-1", "svc-local", "sink-token")),
            List.of(new ChannelProperties.Entry("fake-1", "测试入口", "fake", 1, 1, true, null, Map.of(),
                Map.of(), null, "default"),new ChannelProperties.Entry("web-persistent", "持久网页", "web", 1, 1, true, null,
                Map.of("users",Map.of("alice","e2e-web-token")),Map.of(),null,"default")));

        ChannelRuntime runtime = ChannelRuntime.create(properties);
        try {
            runtime.start();
            FakeAdapterInstance fake = assertInstanceOf(FakeAdapterInstance.class,
                runtime.l2().connections().instance("fake-1"));
            assertNotNull(fake);
            runtime.l2().management().bindIdentity("fake-1", "USER", "alice", "caller-e2e");

            // 后端无法核实归属：配置 owner 只是种子，未验证证据不产生执行权。
            assertInstanceOf(ChannelResponse.Rejected.class, fake.submit("req-unverified", "alice", "你好"),
                "unverified ownership must stay gated");
            // 后端事实可用后重同步：归属经 L3 facts 真实落库，个人文本链路放行。
            factsHealthy.set(true);
            runtime.l2().management().syncTargets();
            assertEquals("caller-e2e", targetOwner(dataFile, "target-a"),
                "verified backend facts must establish ownership");

            assertInstanceOf(ChannelResponse.Completed.class,fake.submit("req-e2e-list","alice","/list"));
            // 先按平台命令选择逻辑目标，再提交普通输入
            ChannelResponse selected = fake.submit("req-e2e-use", "alice", "/use 1");
            assertInstanceOf(ChannelResponse.Completed.class, selected, selected.toString());

            ChannelResponse response = fake.submit("req-e2e-1", "alice", "你好");
            ChannelResponse.Accepted accepted = assertInstanceOf(ChannelResponse.Accepted.class, response,
                response.toString());
            await(runtime, () -> submits.get() == 1);
            assertTrue(lastBody.get().contains("\"request_key\""));
            assertTrue(lastBody.get().contains("你好"));

            // 后端主动回传完整消息：requestKey 绑定本次派发，L3 解析回原 operationRef
            String requestKey = "rk-" + Digests
                .sha256("svc-local\u0000session-1\u0000" + accepted.operationRef()).substring(0, 32);
            String event = """
                {"version":"1","eventId":"evt-1","type":"message.ready",
                 "occurredAt":"2026-10-27T00:00:01Z","resource":{"sessionId":"session-1"},
                 "correlation":{"requestKey":"%s"},
                 "payload":{"content":[{"partId":"p1","type":"text","text":"月度分析已完成","format":"plain"}],
                            "backendMessageId":"bm-1"}}
                """.formatted(requestKey);
            IngestResult ingested = runtime.l3().events().ingest("l3-sink-1", "sink-token", event);
            assertTrue(ingested.accepted(), () -> "ingest rejected: " + ingested.error());

            await(runtime, () -> !fake.deliveries().isEmpty());
            ChannelDelivery delivery = fake.deliveries().get(0);
            assertEquals(EventType.MESSAGE_READY, delivery.packet().type());
            assertEquals("fake-1", delivery.routing().entryRef());
            assertTrue(delivery.presentation().sourceLabel().contains("报表助手"));
            assertEquals("月度分析已完成", extractText(delivery));

            // 同一后端事件重发：保持相同事件身份，不产生第二次投递（INV-31、INV-66）
            assertTrue(runtime.l3().events().ingest("l3-sink-1", "sink-token", event).accepted());
            for (int i = 0; i < 5; i++) {
                runtime.tick();
            }
            assertEquals(1, fake.deliveries().size(), () -> "deliveries: " + fake.deliveries().stream()
                .map(item -> item.deliveryId() + "/" + item.packet().packetId()).toList() + " " + counts(dataFile));
            assertEquals(1, submits.get());

            // Actual Web L1 -> L2 -> HTTP L3 submission and receiver custody, with terminal-before-body ordering.
            var web=assertInstanceOf(com.netlivecowork.channel.l1.web.WebAdapterInstance.class,runtime.l2().connections().instance("web-persistent"));
            runtime.l2().management().bindIdentity("web-persistent","USER","alice","caller-e2e");
            assertInstanceOf(ChannelResponse.Completed.class,web.submitInbound("e2e-web-token","web-list","/list"));
            assertInstanceOf(ChannelResponse.Completed.class,web.submitInbound("e2e-web-token","web-use","/use 1"));
            var webAccepted=assertInstanceOf(ChannelResponse.Accepted.class,web.submitInbound("e2e-web-token","web-long","长任务完成提醒"));
            await(runtime,()->submits.get()==2);
            String webKey="rk-"+Digests.sha256("svc-local\u0000session-1\u0000"+webAccepted.operationRef()).substring(0,32);
            var completed=com.netlivecowork.channel.contract.json.Json.obj().put("version","1").put("eventId","web-done").put("type","task.completed").put("occurredAt","2026-10-27T00:00:03Z");
            completed.set("resource",com.netlivecowork.channel.contract.json.Json.obj().put("sessionId","session-1").put("taskId","web-task"));
            completed.set("correlation",com.netlivecowork.channel.contract.json.Json.obj().put("requestKey",webKey));
            completed.set("payload",com.netlivecowork.channel.contract.json.Json.obj().put("revision",3).set("resultMessageIds",com.netlivecowork.channel.contract.json.Json.arr().add("web-result")));
            var completedIngest=runtime.l3().events().ingest("l3-sink-1","sink-token",1L,1L,completed.toString());
            assertTrue(completedIngest.accepted(),()->"Completion ingest rejected: "+completedIngest.error());
            var webEvent=(com.fasterxml.jackson.databind.node.ObjectNode)com.netlivecowork.channel.contract.json.Json.read(event);
            webEvent.put("eventId","web-message");
            ((com.fasterxml.jackson.databind.node.ObjectNode)webEvent.path("resource")).put("taskId","web-task");
            ((com.fasterxml.jackson.databind.node.ObjectNode)webEvent.path("correlation")).put("requestKey",webKey);
            ((com.fasterxml.jackson.databind.node.ObjectNode)webEvent.path("payload")).put("backendMessageId","web-result").put("revision",1);
            var messageIngest=runtime.l3().events().ingest("l3-sink-1","sink-token",1L,1L,webEvent.toString());
            assertTrue(messageIngest.accepted(),()->"Message ingest rejected: "+messageIngest.error());
            var request=new com.netlivecowork.channel.contract.DeliveryInbox.Request("runtime-browser-000001",null,20);
            await(runtime,()->web.readInbox("e2e-web-token",request).page().items().size()==2);
            var page=web.readInbox("e2e-web-token",request).page();
            assertEquals(1,page.items().stream().filter(i->i.delivery().presentation().notification()!=null).count());
            assertEquals(1,page.items().stream().filter(i->i.delivery().packet().type()==EventType.MESSAGE_READY).count());
            var webController=new WebController(runtime);
            var httpPage=webController.inbox("web-persistent","Bearer e2e-web-token","http-browser-00000001",null,20);
            assertEquals(200,httpPage.getStatusCode().value());assertEquals(2,httpPage.getBody().path("messages").size());
            runtime.close();
            try(var restarted=ChannelRuntime.create(properties)) {
                restarted.start();
                var restored=assertInstanceOf(com.netlivecowork.channel.l1.web.WebAdapterInstance.class,restarted.l2().connections().instance("web-persistent"));
                var restoredPage=restored.readInbox("e2e-web-token",request).page();
                assertEquals(page.items().stream().map(i->i.delivery().deliveryId()).toList(),restoredPage.items().stream().map(i->i.delivery().deliveryId()).toList());
                assertEquals(page.items().stream().map(i->i.sequence()).toList(),restoredPage.items().stream().map(i->i.sequence()).toList());
                assertEquals(2,submits.get(),"Restart must not execute the task again");
            }
        } finally {
            runtime.close();
            backend.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
        exchange.close();
    }

    /** 直查 L2 内部表核对归属落库；L2Module 的仓储视图不跨模块暴露。 */
    private String targetOwner(Path dataFile, String targetRef) throws java.sql.SQLException {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dataFile);
             var statement = connection.prepareStatement(
                 "SELECT owner_principal_ref FROM l2_targets WHERE target_ref=?")) {
            statement.setString(1, targetRef);
            try (var rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private String counts(Path dataFile) {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dataFile);
             var statement = connection.createStatement()) {
            StringBuilder text = new StringBuilder("counts{");
            for (String table : List.of("backend_events", "l2_backend_events", "l2_outbox",
                "l2_reply_relations", "l2_outbox_relations")) {
                try (var rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    text.append(table).append('=').append(rs.next() ? rs.getInt(1) : -1).append(' ');
                } catch (java.sql.SQLException e) {
                    text.append(table).append("=n/a ");
                }
            }
            return text.append('}').toString();
        } catch (java.sql.SQLException e) {
            return "counts{error=" + e.getMessage() + "}";
        }
    }

    private String extractText(ChannelDelivery delivery) {
        var message = ((com.netlivecowork.channel.contract.EventPayload.MessageReady)
            delivery.packet().payload()).message();
        return ((com.netlivecowork.channel.contract.ContentPart.Text) message.content().get(0)).text();
    }

    private void await(ChannelRuntime runtime, BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 250 && !condition.getAsBoolean(); i++) {
            runtime.tick();
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), "condition was not reached in time");
    }
}
