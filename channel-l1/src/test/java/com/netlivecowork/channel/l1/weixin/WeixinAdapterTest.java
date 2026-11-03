package com.netlivecowork.channel.l1.weixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.ChannelResponse;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.DeliveryAttempt;
import com.netlivecowork.channel.contract.DeliveryReceipt;
import com.netlivecowork.channel.contract.VerifiedIngress;
import com.netlivecowork.channel.contract.adapter.ActivationResult;
import com.netlivecowork.channel.contract.adapter.AdapterConfig;
import com.netlivecowork.channel.contract.adapter.AdapterInstanceConfig;
import com.netlivecowork.channel.contract.adapter.AuthSession;
import com.netlivecowork.channel.contract.adapter.ConnectResult;
import com.netlivecowork.channel.contract.adapter.ConnectionAttempt;
import com.netlivecowork.channel.contract.adapter.ConnectionEvent;
import com.netlivecowork.channel.contract.adapter.ProbeResult;
import com.netlivecowork.channel.contract.adapter.StopReason;
import com.netlivecowork.channel.contract.adapter.ValidationResult;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.l1.support.Packets;
import com.netlivecowork.channel.l1.support.TestHost;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WeixinAdapterTest {

    private static final String SOURCE_LABEL = "报表助手 · 月度分析";

    @TempDir
    Path root;

    private final ObjectMapper json = Json.mapper();
    private final AtomicReference<JsonNode> updates = new AtomicReference<>();
    private final AtomicReference<JsonNode> sendBody = new AtomicReference<>();
    private final AtomicReference<String> sendAuthorization = new AtomicReference<>();
    private final AtomicReference<String> polledCursor = new AtomicReference<>();
    private final AtomicInteger qrRequests = new AtomicInteger();
    private final AtomicInteger qrStatusCalls = new AtomicInteger();
    private final AtomicInteger sendCount = new AtomicInteger();
    private final AtomicInteger requests = new AtomicInteger();

    private HttpServer server;
    private String baseUrl;
    private Path secretFile;
    private TestHost host;
    private WeixinAdapterFactory factory;
    private volatile int sendStatus = 200;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/ilink/bot", exchange -> {
            requests.incrementAndGet();
            try {
                String path = exchange.getRequestURI().getPath();
                JsonNode response;
                if (path.endsWith("get_bot_qrcode")) {
                    qrRequests.incrementAndGet();
                    response = json.createObjectNode().put("ret", 0).put("qrcode", "challenge-1")
                        .put("qrcode_img_content", "https://example.invalid/qr");
                } else if (path.endsWith("get_qrcode_status")) {
                    response = qrStatusCalls.incrementAndGet() <= 1
                        ? json.createObjectNode().put("ret", 0).put("status", "wait")
                        : json.createObjectNode().put("ret", 0).put("status", "confirmed")
                            .put("bot_token", "tok-1").put("ilink_bot_id", "bot-a").put("baseurl", baseUrl);
                } else {
                    JsonNode body = json.readTree(exchange.getRequestBody());
                    if (path.endsWith("getupdates")) {
                        polledCursor.set(body.path("get_updates_buf").asText());
                        response = updates.get();
                    } else {
                        sendCount.incrementAndGet();
                        sendBody.set(body);
                        sendAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                        if (sendStatus != 200) {
                            exchange.sendResponseHeaders(sendStatus, -1);
                            return;
                        }
                        response = json.createObjectNode().put("ret", 0).put("message_id", "out-1");
                    }
                }
                byte[] bytes = json.writeValueAsBytes(response);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception e) {
                // 测试桩只记录观测值；协议失败由被测代码与断言体现
            } finally {
                exchange.close();
            }
        });
        server.start();
        secretFile = root.resolve("weixin.json");
        host = new TestHost("entry-wx");
        host.destinations.put("dest-alice", Json.obj().put("conversation", "alice").put("contextToken", "ctx-1"));
        updates.set(json.createObjectNode().put("ret", 0));
        factory = new WeixinAdapterFactory();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void validateConfigIsOfflineAndRequiresSecureEndpoint() {
        JsonNode valid = Json.obj().put("secretFile", secretFile.toString()).put("baseurl", baseUrl);
        assertTrue(factory.validateConfig(config(valid)).valid());

        ValidationResult missing = factory.validateConfig(config(Json.obj()));
        assertFalse(missing.valid());
        assertEquals("settings.secretFile", missing.issues().get(0).path());

        ValidationResult insecure = factory.validateConfig(config(
            Json.obj().put("secretFile", secretFile.toString()).put("baseurl", "http://example.com")));
        assertFalse(insecure.valid());
        assertEquals("settings.baseurl", insecure.issues().get(0).path());

        ValidationResult notUrl = factory.validateConfig(config(
            Json.obj().put("secretFile", secretFile.toString()).put("baseurl", "not a url")));
        assertFalse(notUrl.valid());

        ValidationResult inlineToken = factory.validateConfig(config(
            Json.obj().put("secretFile", secretFile.toString()).put("botToken", "tok-1")));
        assertFalse(inlineToken.valid());
        assertEquals("settings.botToken", inlineToken.issues().get(0).path());

        assertEquals(0, requests.get(), "validateConfig must not touch the network");
    }

    @Test
    void deliveryRendersSourceLabelAndFixedDestination() throws IOException {
        writeSecret("tok-1", "bot-a");
        WeixinAdapterInstance instance = instance(null);
        assertEquals("weixin:bot-a", instance.activate().toCompletableFuture().join().externalAccountKey());

        DeliveryReceipt receipt = deliver(instance, "delivery-1");

        DeliveryReceipt.Accepted accepted = assertInstanceOf(DeliveryReceipt.Accepted.class, receipt);
        assertEquals("delivery-1", accepted.deliveryId());
        assertEquals("out-1", accepted.receiptRef());

        JsonNode message = sendBody.get().path("msg");
        assertEquals("alice", message.path("to_user_id").asText());
        assertEquals("ctx-1", message.path("context_token").asText());
        assertEquals("delivery-1", message.path("client_id").asText());
        String text = message.path("item_list").get(0).path("text_item").path("text").asText();
        assertTrue(text.startsWith("【" + SOURCE_LABEL + "】"), text);
        assertTrue(text.contains("月度分析已完成"), text);
        assertEquals("Bearer tok-1", sendAuthorization.get());
    }

    @Test
    void imageContentIsUnsupported() throws IOException {
        writeSecret("tok-1", "bot-a");
        WeixinAdapterInstance instance = instance(null);
        instance.activate().toCompletableFuture().join();

        DeliveryReceipt receipt = instance.deliver(Packets.delivery(Packets.messageReady(List.of(
                new ContentPart.Text("p1", "看图"), new ContentPart.Image("p2", "file-1", "截图"))),
            "delivery-2", "entry-wx", "dest-alice", 2, SOURCE_LABEL),
            DeliveryAttempt.withTimeout(5_000)).toCompletableFuture().join();

        assertEquals("UNSUPPORTED_CONTENT",
            assertInstanceOf(DeliveryReceipt.Failed.class, receipt).error().code());
        assertEquals(0, sendCount.get());
    }

    @Test
    void accountMismatchBlocksBusiness() throws IOException {
        writeSecret("tok-1", "bot-a");
        WeixinAdapterInstance instance = instance("weixin:bot-b");

        ActivationResult activation = instance.activate().toCompletableFuture().join();
        assertNull(activation.externalAccountKey());
        assertEquals("weixin:bot-a", host.stateValue("account").asText());

        DeliveryReceipt receipt = deliver(instance, "delivery-3");
        assertEquals("ACCOUNT_MISMATCH",
            assertInstanceOf(DeliveryReceipt.Failed.class, receipt).error().code());
        assertEquals(0, sendCount.get());
    }

    @Test
    void qrAuthCompletesAndKeepsTokenInSecretFile() throws IOException {
        WeixinAdapterInstance instance = instance(null);

        AuthSession begin = instance.beginAuth().toCompletableFuture().join();
        assertEquals(AuthSession.Status.PENDING, begin.status());
        assertInstanceOf(AuthSession.Presentation.Qr.class, begin.presentation());
        assertEquals("https://example.invalid/qr",
            ((AuthSession.Presentation.Qr) begin.presentation()).value());
        assertEquals(1, qrRequests.get());
        assertFalse(begin.toString().contains("tok-1"), begin.toString());

        assertEquals(AuthSession.Status.PENDING,
            instance.authStatus(begin.authRef()).toCompletableFuture().join().status());

        AuthSession confirmed = instance.authStatus(begin.authRef()).toCompletableFuture().join();
        assertEquals(AuthSession.Status.COMPLETED, confirmed.status());
        assertEquals("weixin:bot-a", confirmed.externalAccountKey());
        assertNull(confirmed.presentation());
        assertFalse(confirmed.toString().contains("tok-1"), confirmed.toString());

        JsonNode saved = json.readTree(secretFile.toFile());
        assertEquals("tok-1", saved.path("bot_token").asText());
        assertEquals("bot-a", saved.path("ilink_bot_id").asText());
        assertEquals(baseUrl, saved.path("baseurl").asText());
    }

    @Test
    void sendFailureIsUnknown() throws IOException {
        writeSecret("tok-1", "bot-a");
        WeixinAdapterInstance instance = instance(null);
        instance.activate().toCompletableFuture().join();
        sendStatus = 500;

        DeliveryReceipt receipt = deliver(instance, "delivery-4");

        assertEquals("OUTCOME_UNKNOWN",
            assertInstanceOf(DeliveryReceipt.Unknown.class, receipt).error().code());
    }

    @Test
    void deactivatedInstanceRejectsDelivery() throws IOException {
        writeSecret("tok-1", "bot-a");
        WeixinAdapterInstance instance = instance(null);
        instance.activate().toCompletableFuture().join();
        instance.deactivate(StopReason.DISABLED).toCompletableFuture().join();

        DeliveryReceipt receipt = deliver(instance, "delivery-5");

        assertEquals("UNAVAILABLE",
            assertInstanceOf(DeliveryReceipt.Failed.class, receipt).error().code());
        assertEquals(0, sendCount.get());
    }

    @Test
    void cursorAdvancesOnlyAfterIngressAccepted() throws IOException {
        writeSecret("tok-1", "bot-a");
        WeixinAdapterInstance instance = instance(null);
        instance.activate().toCompletableFuture().join();
        updates.set(json.readTree("""
            {"ret":0,"get_updates_buf":"next","msgs":[{"message_id":"m1","from_user_id":"alice",
             "context_token":"ctx","item_list":[{"type":1,"text_item":{"text":"hello"}}]}]}
            """));

        assertEquals(1_000L, instance.pollOnce());

        assertEquals("", polledCursor.get());
        assertEquals("weixin-m1", host.resolved.get(0).requestId());
        assertEquals("weixin:bot-a:user:alice", host.resolved.get(0).externalSubject().key());
        assertEquals("ctx", host.resolved.get(0).externalReplyTarget().protocolData()
            .path("contextToken").asText());
        assertEquals(1, host.submitted.size());
        assertEquals("next", host.stateValue("cursor").path("cursor").asText());
    }

    @Test
    void groupMessageKeepsTheSenderAndRoutesToTheGroup() throws IOException {
        writeSecret("tok-1", "bot-a");
        WeixinAdapterInstance instance = instance(null);
        instance.activate().toCompletableFuture().join();
        updates.set(json.readTree("""
            {"ret":0,"get_updates_buf":"next","msgs":[{"message_id":"group-1",
             "from_user_id":"alice","group_id":"room-1","context_token":"group-ctx",
             "item_list":[{"type":1,"text_item":{"text":"hello group"}}]}]}
            """));

        assertEquals(1_000L, instance.pollOnce());

        var ingress = host.resolved.get(0);
        assertEquals("weixin:bot-a:user:alice", ingress.externalSubject().key());
        assertEquals("weixin-group", ingress.externalScope().namespace());
        assertEquals("room-1", ingress.externalScope().key());
        assertEquals(VerifiedIngress.Audience.SHARED, ingress.externalReplyTarget().audience());
        assertEquals("room-1", ingress.externalReplyTarget().protocolData().path("conversation").asText());
        assertEquals("group-ctx", ingress.externalReplyTarget().protocolData().path("contextToken").asText());
    }

    @Test
    void sharedDestinationRepliesToTheRegisteredGroup() throws IOException {
        writeSecret("tok-1", "bot-a");
        WeixinAdapterInstance instance = instance(null);
        instance.activate().toCompletableFuture().join();
        host.destinations.put("dest-room", Json.obj().put("conversation", "room-1")
            .put("contextToken", "group-ctx"));

        var receipt = instance.deliver(Packets.delivery(Packets.messageReady("group reply"),
                "delivery-group", "entry-wx", "dest-room", 2, SOURCE_LABEL),
            DeliveryAttempt.withTimeout(5_000)).toCompletableFuture().join();

        assertInstanceOf(DeliveryReceipt.Accepted.class, receipt);
        assertEquals("room-1", sendBody.get().path("msg").path("to_user_id").asText());
        assertEquals("group-ctx", sendBody.get().path("msg").path("context_token").asText());
    }

    @Test
    void cursorIsNotAdvancedWhenIngressTemporarilyFails() throws IOException {
        writeSecret("tok-1", "bot-a");
        WeixinAdapterInstance instance = instance(null);
        instance.activate().toCompletableFuture().join();
        // 暂时失败（不可达/限流）不能确认游标：交给平台重投（INV-35）
        host.submitResponse = request -> new ChannelResponse.Rejected(request.requestId(),
            ChannelErrorCode.UNAVAILABLE.error("temporarily unavailable"));
        updates.set(json.readTree("""
            {"ret":0,"get_updates_buf":"next","msgs":[{"message_id":"m2","from_user_id":"alice",
             "item_list":[{"type":1,"text_item":{"text":"hello"}}]}]}
            """));

        assertThrows(RuntimeException.class, instance::pollOnce);

        assertNull(host.stateValue("cursor"));
    }

    @Test
    void permanentRejectionIsSkippedWithoutBlockingTheBatch() throws IOException {
        writeSecret("tok-1", "bot-a");
        WeixinAdapterInstance instance = instance(null);
        instance.activate().toCompletableFuture().join();
        // 确定性拒绝（无权限）重投也不会变好：跳过该消息并推进游标，避免整批永久阻塞
        host.submitResponse = request -> new ChannelResponse.Rejected(request.requestId(),
            ChannelErrorCode.PERMISSION_DENIED.error("not allowed"));
        updates.set(json.readTree("""
            {"ret":0,"get_updates_buf":"next","msgs":[{"message_id":"m3","from_user_id":"alice",
             "item_list":[{"type":1,"text_item":{"text":"hello"}}]}]}
            """));

        assertEquals(1_000L, instance.pollOnce());

        assertEquals("next", host.stateValue("cursor").path("cursor").asText());
        assertEquals(1, host.submitted.size(), "the rejected message must not be retried forever");
        assertNotNull(host.dispositions.records.get("m3"), "permanent rejection must be durably recorded");
        assertEquals("PERMISSION_DENIED", host.dispositions.records.get("m3").code());
    }

    @Test
    void rejectionRecordFailureKeepsTheCursor() throws IOException {
        writeSecret("tok-1", "bot-a");
        WeixinAdapterInstance instance = instance(null);
        instance.activate().toCompletableFuture().join();
        host.submitResponse = request -> new ChannelResponse.Rejected(request.requestId(),
            ChannelErrorCode.PERMISSION_DENIED.error("not allowed"));
        host.dispositions.failWrites = true;
        updates.set(json.readTree("""
            {"ret":0,"get_updates_buf":"next","msgs":[{"message_id":"m4","from_user_id":"alice",
             "item_list":[{"type":1,"text_item":{"text":"hello"}}]}]}
            """));

        // 落盘失败不得确认：抛暂时失败，游标不前进（S69）
        assertThrows(RuntimeException.class, instance::pollOnce);
        assertNull(host.stateValue("cursor"));
    }

    @Test
    void replayOfDisposedMessageDoesNotReachL2() throws IOException {
        writeSecret("tok-1", "bot-a");
        WeixinAdapterInstance instance = instance(null);
        instance.activate().toCompletableFuture().join();
        String digest = com.netlivecowork.channel.infra.Digests.sha256("m7" + "\u0000" + "hello");
        host.dispositions.seed("m7", digest, false, false);
        updates.set(json.readTree("""
            {"ret":0,"get_updates_buf":"next","msgs":[{"message_id":"m7","from_user_id":"alice",
             "item_list":[{"type":1,"text_item":{"text":"hello"}}]}]}
            """));

        assertEquals(1_000L, instance.pollOnce());

        assertEquals("next", host.stateValue("cursor").path("cursor").asText());
        assertTrue(host.submitted.isEmpty(), "a disposed replay must not execute again (S70)");
    }

    @Test
    void conflictingReplayIsQuarantinedWithoutExecution() throws IOException {
        writeSecret("tok-1", "bot-a");
        WeixinAdapterInstance instance = instance(null);
        instance.activate().toCompletableFuture().join();
        host.dispositions.seed("m8", "different-content-digest", false, false);
        updates.set(json.readTree("""
            {"ret":0,"get_updates_buf":"next","msgs":[{"message_id":"m8","from_user_id":"alice",
             "item_list":[{"type":1,"text_item":{"text":"hello"}}]}]}
            """));

        assertEquals(1_000L, instance.pollOnce());

        assertTrue(host.submitted.isEmpty(), "same key with different content must not execute (S71)");
        assertEquals("different-content-digest", host.dispositions.records.get("m8").contentDigest(),
            "the original disposition must not be overwritten");
    }

    @Test
    void expiredDispositionBecomesGapWithoutExecution() throws IOException {
        writeSecret("tok-1", "bot-a");
        WeixinAdapterInstance instance = instance(null);
        instance.activate().toCompletableFuture().join();
        host.dispositions.seed("m9", "old-digest", true, false);
        updates.set(json.readTree("""
            {"ret":0,"get_updates_buf":"next","msgs":[{"message_id":"m9","from_user_id":"alice",
             "item_list":[{"type":1,"text_item":{"text":"hello"}}]}]}
            """));

        assertEquals(1_000L, instance.pollOnce());

        assertTrue(host.submitted.isEmpty(), "a replay beyond retention must not execute (S72)");
        assertTrue(host.dispositions.records.get("m9").gap(), "it must be marked as a manual-review gap");
    }

    @Test
    void resolveRejectionIsClassifiedAndPersisted() throws IOException {
        writeSecret("tok-1", "bot-a");
        WeixinAdapterInstance instance = instance(null);
        instance.activate().toCompletableFuture().join();
        // resolve 阶段的确定性拒绝（身份未绑定）不能抛出去阻塞整批，必须分类并留下持久记录
        host.resolveFailure = new ChannelContractException(ChannelErrorCode.IDENTITY_UNBOUND,
            "external identity is not bound");
        updates.set(json.readTree("""
            {"ret":0,"get_updates_buf":"next","msgs":[{"message_id":"m5","from_user_id":"bob",
             "item_list":[{"type":1,"text_item":{"text":"hello"}}]}]}
            """));

        assertEquals(1_000L, instance.pollOnce());

        assertEquals("next", host.stateValue("cursor").path("cursor").asText());
        JsonNode rejected = host.dispositions.records.get("m5") == null ? null
            : json.valueToTree(host.dispositions.records.get("m5"));
        assertNotNull(rejected, "permanent rejections must leave a durable record");
        assertEquals("IDENTITY_UNBOUND", rejected.path("code").asText());
    }

    @Test
    void resolveTransientFailureDoesNotAdvanceCursor() throws IOException {
        writeSecret("tok-1", "bot-a");
        WeixinAdapterInstance instance = instance(null);
        instance.activate().toCompletableFuture().join();
        host.resolveFailure = new ChannelContractException(ChannelErrorCode.UNAVAILABLE,
            "temporarily unavailable");
        updates.set(json.readTree("""
            {"ret":0,"get_updates_buf":"next","msgs":[{"message_id":"m6","from_user_id":"bob",
             "item_list":[{"type":1,"text_item":{"text":"hello"}}]}]}
            """));

        assertThrows(RuntimeException.class, instance::pollOnce);

        assertNull(host.stateValue("cursor"));
    }

    @Test
    void connectionTestProbesWithCredentialWithoutSubmitting() throws IOException {
        writeSecret("tok-1", "bot-a");
        AdapterConfig config = config(Json.obj().put("secretFile", secretFile.toString()).put("baseurl", baseUrl));

        ProbeResult probe = factory.testConnection(config, host.host().secrets(), 5_000)
            .toCompletableFuture().join();

        assertEquals(ProbeResult.Status.HEALTHY, probe.status());
        assertTrue(host.submitted.isEmpty(), "connection test must not submit business requests");
    }

    @Test
    void connectHandsGenerationAndReportsFacts() throws Exception {
        writeSecret("tok-1", "bot-a");
        WeixinAdapterInstance instance = instance(null);
        instance.activate().toCompletableFuture().join();
        try {
            ConnectResult result = instance.connect(new ConnectionAttempt(3, 5_000))
                .toCompletableFuture().join();
            assertInstanceOf(ConnectResult.Connected.class, result);

            awaitEvents(1);
            assertEquals(3, host.events.get(0).generation());
            // connect 已确认账号，长轮询事实从心跳开始，不重复 Connected
            assertInstanceOf(ConnectionEvent.Heartbeat.class, host.events.get(0));
            assertEquals(ProbeResult.Status.HEALTHY,
                instance.probe(new ConnectionAttempt(3, 1_000)).toCompletableFuture().join().status());

            instance.disconnect(3, StopReason.DISABLED).toCompletableFuture().join();
            assertEquals(ProbeResult.Status.UNHEALTHY,
                instance.probe(new ConnectionAttempt(3, 1_000)).toCompletableFuture().join().status());
        } finally {
            instance.deactivate(StopReason.SHUTDOWN).toCompletableFuture().join();
        }
    }

    private void awaitEvents(int count) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (host.events.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(host.events.size() >= count, "expected " + count + " connection event(s)");
    }

    private AdapterConfig config(JsonNode settings) {
        return new AdapterConfig(1, settings, List.of());
    }

    private WeixinAdapterInstance instance(String expectedAccountKey) {
        AdapterInstanceConfig config = new AdapterInstanceConfig("tenant-1", "workspace-1", "entry-wx", 1, 1, 1,
            expectedAccountKey,
            Json.obj().put("secretFile", secretFile.toString()).put("baseurl", baseUrl), List.of());
        return factory.create(config, host.host());
    }

    private void writeSecret(String token, String botId) throws IOException {
        Files.createDirectories(secretFile.getParent());
        json.writeValue(secretFile.toFile(),
            Json.obj().put("bot_token", token).put("ilink_bot_id", botId).put("baseurl", baseUrl));
    }

    private DeliveryReceipt deliver(WeixinAdapterInstance instance, String deliveryId) {
        return instance.deliver(Packets.delivery(Packets.messageReady("月度分析已完成，报告已生成。"),
                deliveryId, "entry-wx", "dest-alice", 2, SOURCE_LABEL),
            DeliveryAttempt.withTimeout(5_000)).toCompletableFuture().join();
    }

    @Test void deliveredApprovalReplyTravelsThroughActualTextAdapterAndCommonIngress() throws IOException {
        writeSecret("tok-1","bot-a");var instance=instance(null);instance.activate().toCompletableFuture().join();
        var prompt=new com.netlivecowork.channel.contract.InteractionRequiredPayload("interaction-safe",10,null,
            new com.netlivecowork.channel.contract.InteractionPrompt.Approval(List.of(new ContentPart.Text("p","请确认")),
                new com.netlivecowork.channel.contract.InteractionPrompt.Approval.Operation("fs:shell",Json.obj().put("command","echo safe"),null),null));
        var packet=Packets.packet(com.netlivecowork.channel.contract.EventType.INTERACTION_REQUIRED,
            new com.netlivecowork.channel.contract.EventPayload.InteractionRequired(prompt),null);
        var receipt=instance.deliver(Packets.delivery(packet,"delivery-safe","entry-wx","dest-alice",2,SOURCE_LABEL,
            List.of(new com.netlivecowork.channel.contract.ChannelDelivery.Action("action-safe","同意"))),DeliveryAttempt.withTimeout(5000)).toCompletableFuture().join();
        assertInstanceOf(DeliveryReceipt.Accepted.class,receipt);
        String rendered=sendBody.get().path("msg").path("item_list").get(0).path("text_item").path("text").asText();
        assertTrue(rendered.contains("echo safe"));String code=rendered.substring(rendered.indexOf('#'),rendered.indexOf('#')+13);
        var message=Json.obj().put("message_id","approval-reply").put("from_user_id","alice").put("context_token","ctx");
        message.putArray("item_list").add(Json.obj().put("type",1).set("text_item",Json.obj().put("text",code+" 同意")));
        var update=Json.obj().put("ret",0).put("get_updates_buf","after-decision");update.putArray("msgs").add(message);updates.set(update);
        instance.pollOnce();
        assertEquals(com.netlivecowork.channel.l1.render.InteractionReplyMapper.reply(prompt,"同意"),host.operation(0));
        assertEquals("weixin:bot-a:user:alice",host.resolved.get(0).externalSubject().key());
        assertEquals("after-decision",host.stateValue("cursor").path("cursor").asText());
        instance.deactivate(StopReason.SHUTDOWN).toCompletableFuture().join();
    }
}
