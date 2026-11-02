package com.netlivecowork.channel.l1.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netlivecowork.channel.contract.ChannelDelivery;
import com.netlivecowork.channel.contract.ChannelOperation;
import com.netlivecowork.channel.contract.ChannelResponse;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.DeliveryAttempt;
import com.netlivecowork.channel.contract.DeliveryReceipt;
import com.netlivecowork.channel.contract.VerifiedIngress;
import com.netlivecowork.channel.contract.adapter.AdapterConfig;
import com.netlivecowork.channel.contract.adapter.AdapterInstanceConfig;
import com.netlivecowork.channel.contract.adapter.ValidationResult;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.l1.support.Packets;
import com.netlivecowork.channel.l1.support.TestHost;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebAdapterTest {

    @Test
    void onlyWebFactoryOptsIntoFullHistoryAndInstanceRestrictionIsValidated() {
        var factory=new WebAdapterFactory();
        assertEquals(com.netlivecowork.channel.contract.adapter.AdapterCapabilities.HistoryAccess.FULL,
            factory.describe().capabilities().historyAccess());
        assertEquals(com.netlivecowork.channel.contract.adapter.AdapterCapabilities.HistoryAccess.SUMMARY,
            new com.netlivecowork.channel.l1.weixin.WeixinAdapterFactory().describe().capabilities().historyAccess());
        for(String value:List.of("none","summary","full")) {
            var config=(ObjectNode)settings();config.put("historyAccess",value);
            assertTrue(factory.validateConfig(new AdapterConfig(1,config,List.of())).valid());
        }
        var invalid=(ObjectNode)settings();invalid.put("historyAccess","elevated");
        assertFalse(factory.validateConfig(new AdapterConfig(1,invalid,List.of())).valid());
    }

    private static final String ALICE_TOKEN = "token-alice";

    private TestHost host;
    private WebAdapterInstance instance;

    @BeforeEach
    void setUp() {
        host = new TestHost("entry-web");
        host.destinations.put("dest-alice", Json.obj().put("conversation", "alice"));
        instance = new WebAdapterFactory().create(config(settings()), host.host());
        instance.activate().toCompletableFuture().join();
    }

    static JsonNode settings() {
        ObjectNode root = Json.obj();
        root.putObject("users").put("alice", ALICE_TOKEN).put("bob", "token-bob");
        return root;
    }

    static AdapterInstanceConfig config(JsonNode settings) {
        return new AdapterInstanceConfig("tenant-1", "workspace-1", "entry-web", 1, 1, 1,
            "web:entry-web", settings, List.of());
    }

    @Test
    void validateConfigRequiresUsers() {
        WebAdapterFactory factory = new WebAdapterFactory();
        assertTrue(factory.validateConfig(new AdapterConfig(1, settings(), List.of())).valid());
        assertTrue(factory.validateConfig(new AdapterConfig(1,
            Json.obj().put("usersFile", "users.json"), List.of())).valid());

        ValidationResult missing = factory.validateConfig(new AdapterConfig(1, Json.obj(), List.of()));
        assertFalse(missing.valid());
        assertEquals("settings.users", missing.issues().get(0).path());

        ValidationResult blankToken = factory.validateConfig(new AdapterConfig(1,
            inlineUsers("alice", " "), List.of()));
        assertFalse(blankToken.valid());
    }

    private static JsonNode inlineUsers(String name, String token) {
        ObjectNode root = Json.obj();
        root.putObject("users").put(name, token);
        return root;
    }

    @Test
    void unknownTokenIsRejected() {
        assertThrows(SecurityException.class, () -> instance.submitInbound("nope", "request-1", "你好"));
        assertThrows(SecurityException.class, () -> instance.poll("nope", 0));
        assertThrows(SecurityException.class, () -> instance.submitInbound("", "request-1", "你好"));
        assertThrows(IllegalArgumentException.class, () -> instance.submitInbound(ALICE_TOKEN, " ", "你好"));
    }

    @Test
    void submitInboundReachesIngressPort() {
        ChannelResponse response = instance.submitInbound(ALICE_TOKEN, "request-9", "分析这次发布");

        assertInstanceOf(ChannelResponse.Accepted.class, response);
        assertEquals("request-9", response.requestId());
        assertEquals(1, host.submitted.size());

        VerifiedIngress verified = host.resolved.get(0);
        assertEquals("request-9", verified.requestId());
        assertEquals("web:entry-web", verified.externalAccountKey());
        assertEquals("alice", verified.externalSubject().key());
        assertEquals(VerifiedIngress.SubjectKind.USER, verified.externalSubject().kind());
        assertEquals("web", verified.externalScope().namespace());
        assertEquals("alice", verified.externalScope().key());
        assertEquals(VerifiedIngress.Audience.PERSONAL, verified.externalReplyTarget().audience());
        assertEquals("alice", verified.externalReplyTarget().key());
        assertEquals("alice", verified.externalReplyTarget().protocolData().path("conversation").asText());
        assertEquals("request-9", verified.sourceEventKey());

        ChannelOperation.InputSubmit input = assertInstanceOf(ChannelOperation.InputSubmit.class,
            host.operation(0));
        assertEquals(ChannelOperation.Interpretation.COMMAND_AWARE, input.interpretation());
        assertEquals("分析这次发布",
            assertInstanceOf(ContentPart.Text.class, input.content().get(0)).text());
        assertEquals("alice", host.submitted.get(0).identity().callerRef());
    }

    @Test
    void chatUsesLiteralInputAndExplicitTargetWithoutTrustingClientIdentity() {
        instance.submitChat(ALICE_TOKEN, "chat-1", "/use this is ordinary text", "target-clicked");
        var input = assertInstanceOf(ChannelOperation.InputSubmit.class, host.operation(0));
        assertEquals(ChannelOperation.Interpretation.LITERAL, input.interpretation());
        assertEquals("target-clicked", input.targetRef());
        assertEquals("alice", host.submitted.get(0).identity().callerRef());
        assertEquals("/use this is ordinary text", ((ContentPart.Text) input.content().get(0)).text());
        assertThrows(IllegalArgumentException.class, () -> instance.submitChat(ALICE_TOKEN, "c2", "hello", null));
        assertThrows(SecurityException.class, () -> instance.submitChat("bad-token", "c3", "hello", "target-clicked"));
    }

    @Test
    void chatStateQueryPinsTheClickedSessionRatherThanAnotherTabsBinding() {
        instance.getSession(ALICE_TOKEN, "state-1", "target-clicked");
        assertEquals("target-clicked", assertInstanceOf(ChannelOperation.SessionGet.class, host.operation(0)).targetRef());
        assertEquals("alice", host.submitted.get(0).identity().callerRef());
        assertThrows(SecurityException.class, () -> instance.getSession("bad", "s2", "target-clicked"));
    }

    @Test
    void structuredNavigationUsesIngressAndScopesTabsByAuthenticatedCaller() {
        instance.listWorkspaces(ALICE_TOKEN,"list-a","tab-aaaaaaaaaaaaaaaa");
        instance.selectSession(ALICE_TOKEN,"select-a","tab-aaaaaaaaaaaaaaaa","target-1",4);
        instance.listWorkspaces(ALICE_TOKEN,"list-b","tab-bbbbbbbbbbbbbbbb");
        instance.listWorkspaces("token-bob","list-other","tab-aaaaaaaaaaaaaaaa");
        assertInstanceOf(ChannelOperation.WorkspaceList.class,host.operation(0));
        assertEquals(new ChannelOperation.SessionSelect("target-1",4),host.operation(1));
        assertEquals(host.resolved.get(0).externalScope(),host.resolved.get(1).externalScope());
        assertFalse(host.resolved.get(0).externalScope().equals(host.resolved.get(2).externalScope()));
        assertFalse(host.resolved.get(0).externalScope().equals(host.resolved.get(3).externalScope()));
        assertEquals("alice",host.resolved.get(1).externalSubject().key());
        instance.getSelection(ALICE_TOKEN,"current-a","tab-aaaaaaaaaaaaaaaa");
        assertInstanceOf(ChannelOperation.SelectionGet.class,host.operation(4));
        assertEquals(host.resolved.get(0).externalScope(),host.resolved.get(4).externalScope());
    }

    @Test
    void normalTypedSendRequiresConfirmedSelectionAndCannotInjectCommands() {
        assertThrows(IllegalArgumentException.class,()->instance.submitChat(ALICE_TOKEN,"bad","hello","target-1","bad-view",2L));
        assertThrows(com.netlivecowork.channel.contract.ChannelContractException.class,()->instance.submitChat(ALICE_TOKEN,"bad2","hello","target-1","tab-aaaaaaaaaaaaaaaa",-1L));
        instance.submitChat(ALICE_TOKEN,"good","/use literal text","target-1","tab-aaaaaaaaaaaaaaaa",2L);
        var input=assertInstanceOf(ChannelOperation.InputSubmit.class,host.operation(0));
        assertEquals(ChannelOperation.Interpretation.LITERAL,input.interpretation());
        assertEquals("/use literal text",((ContentPart.Text)input.content().get(0)).text());
        assertEquals(2L,input.expectedBindingRevision());

    }

    @Test
    void pollReturnsBufferedTextAfterSequence() {
        accept(deliver("delivery-1", "第一段"));
        accept(deliver("delivery-2", "第二段"));

        List<WebAdapterInstance.Delivered> all = instance.poll(ALICE_TOKEN, 0);
        assertEquals(2, all.size());
        assertEquals("message.ready", all.get(0).type());
        assertEquals("报表助手", all.get(0).sourceLabel());
        assertEquals("第一段", all.get(0).text());
        assertEquals("target-1", all.get(0).targetRef(), "reply routing must retain the packet target");
        assertTrue(all.get(0).sequence() < all.get(1).sequence());

        assertEquals(List.of(all.get(1)), instance.poll(ALICE_TOKEN, all.get(0).sequence()));
        assertTrue(instance.poll("token-bob", 0).isEmpty());
    }

    @Test
    void rejectsContentTheEntryCannotExpress() {
        DeliveryReceipt receipt = instance.deliver(Packets.delivery(Packets.messageReady(List.of(
                new ContentPart.Text("p1", "看图"), new ContentPart.Image("p2", "file-1", "截图"))),
            "delivery-3", "entry-web", "dest-alice", 1, "报表助手"),
            DeliveryAttempt.withTimeout(1_000)).toCompletableFuture().join();
        assertEquals("UNSUPPORTED_CONTENT",
            assertInstanceOf(DeliveryReceipt.Failed.class, receipt).error().code());
        assertTrue(instance.poll(ALICE_TOKEN, 0).isEmpty());
    }

    @Test
    void rejectsUnknownDestinationCaller() {
        host.destinations.put("dest-carol", Json.obj().put("conversation", "carol"));
        DeliveryReceipt receipt = instance.deliver(Packets.delivery(Packets.messageReady("你好"),
            "delivery-4", "entry-web", "dest-carol", 1, "报表助手"),
            DeliveryAttempt.withTimeout(1_000)).toCompletableFuture().join();
        assertEquals("IDENTITY_UNBOUND",
            assertInstanceOf(DeliveryReceipt.Failed.class, receipt).error().code());
    }

    @Test
    void lookupAuthenticatesAndResolvesOriginalCallerWithoutResubmitting() {
        assertThrows(SecurityException.class, () -> instance.lookupInbound("bad-token", "request-9"));
        assertTrue(host.resolved.isEmpty());
        var result = instance.lookupInbound(ALICE_TOKEN, "request-9");
        assertEquals("request-9", result.requestId());
        assertEquals("alice", host.resolved.get(0).externalSubject().key());
        assertEquals("alice", host.resolved.get(0).externalScope().key());
        assertEquals(List.of("request-9"), host.lookedUp);
        assertTrue(host.submitted.isEmpty());
    }

    @Test
    void explicitTargetIsCarriedToAuthorizationInsteadOfImplicitCurrentBinding() {
        instance.submitInbound(ALICE_TOKEN, "request-pinned", "你好", "target-confirmed");
        assertEquals("target-confirmed", assertInstanceOf(ChannelOperation.InputSubmit.class,
            host.operation(0)).targetRef());
    }

    @Test
    void restartHasNewStreamAndPollingDoesNotConsumeOtherClientsMessages() {
        accept(deliver("delivery-stream", "可重复查询"));
        assertEquals(instance.poll(ALICE_TOKEN, 0), instance.poll(ALICE_TOKEN, 0));
        var other = new WebAdapterFactory().create(config(settings()), host.host());
        assertFalse(instance.streamId().equals(other.streamId()));
        other.deactivate(com.netlivecowork.channel.contract.adapter.StopReason.SHUTDOWN);
    }

    @Test
    void sessionPagesUseAuthenticatedCallerAndExistingSessionListOperation() {
        instance.listSessions(ALICE_TOKEN, "page-two", "20", 20);
        var list = assertInstanceOf(ChannelOperation.SessionList.class, host.operation(0));
        assertEquals("20", list.page().cursor());
        assertEquals(20, list.page().effectiveLimit());
        assertEquals("alice", host.resolved.get(0).externalSubject().key());
        assertThrows(SecurityException.class, () -> instance.listSessions("bad-token", "p", null, 20));
    }

    private ChannelDelivery deliver(String deliveryId, String text) {
        return Packets.delivery(Packets.messageReady(text), deliveryId, "entry-web", "dest-alice", 1, "报表助手");
    }

    private void accept(ChannelDelivery delivery) {
        host.inboxDeliveries.put(delivery.deliveryId(),delivery);
        DeliveryReceipt receipt = instance.deliver(delivery, DeliveryAttempt.withTimeout(1_000))
            .toCompletableFuture().join();
        assertInstanceOf(DeliveryReceipt.Accepted.class, receipt);
    }
    @Test void sessionAccessAlwaysResolvesTheAuthenticatedUserAndRejectsBadTokens() {
        assertThrows(SecurityException.class,()->instance.sessionAccess("forged"));
        assertThrows(SecurityException.class,()->instance.saveSessionAccess("forged",new com.netlivecowork.channel.contract.SessionAccess.Save("r",0,List.of())));
        assertThrows(SecurityException.class,()->instance.lookupSessionAccess("forged","r"));
        assertEquals("verified",instance.sessionAccess(ALICE_TOKEN).principalRef());
        assertEquals("alice",host.resolved.get(host.resolved.size()-1).externalSubject().key());
        instance.saveSessionAccess(ALICE_TOKEN,new com.netlivecowork.channel.contract.SessionAccess.Save("save",0,List.of()));
        instance.lookupSessionAccess(ALICE_TOKEN,"save");assertTrue(host.lookedUp.contains("save"));
        assertTrue(host.submitted.isEmpty(),"configuration must not masquerade as model input");
        assertEquals(3,host.resolved.size(),"each management operation needs fresh admission");
    }
}
