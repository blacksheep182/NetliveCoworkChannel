package com.netlivecowork.channel.l1.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netlivecowork.channel.contract.ChannelDelivery;
import com.netlivecowork.channel.contract.ChannelOperation;
import com.netlivecowork.channel.contract.ChannelResponse;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.DeliveryAttempt;
import com.netlivecowork.channel.contract.DeliveryReceipt;
import com.netlivecowork.channel.contract.VerifiedIngress;
import com.netlivecowork.channel.contract.adapter.AdapterInstanceConfig;
import com.netlivecowork.channel.contract.adapter.ActivationResult;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.l1.support.Packets;
import com.netlivecowork.channel.l1.support.TestHost;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FakeAdapterTest {

    private TestHost host;
    private FakeAdapterInstance instance;

    @BeforeEach
    void setUp() {
        host = new TestHost("entry-fake");
        instance = new FakeAdapterFactory().create(config("entry-fake"), host.host());
        ActivationResult activation = instance.activate().toCompletableFuture().join();
        assertEquals("fake:entry-fake", activation.externalAccountKey());
    }

    private static AdapterInstanceConfig config(String entryRef) {
        return new AdapterInstanceConfig("tenant-1", "workspace-1", entryRef, 1, 1, 1,
            "fake:" + entryRef, Json.obj(), List.of());
    }

    @Test
    void recordsDeliveryAndAccepts() {
        ChannelDelivery delivery = Packets.delivery(Packets.messageReady("你好"), "delivery-1",
            "entry-fake", "destination-1", 1, "测试入口");
        DeliveryReceipt receipt = instance.deliver(delivery, DeliveryAttempt.withTimeout(1_000))
            .toCompletableFuture().join();

        DeliveryReceipt.Accepted accepted = assertInstanceOf(DeliveryReceipt.Accepted.class, receipt);
        assertEquals("delivery-1", accepted.deliveryId());
        assertEquals(List.of(delivery), instance.deliveries());

        instance.reset();
        assertTrue(instance.deliveries().isEmpty());
    }

    @Test
    void rejectsContentTheEntryCannotExpress() {
        ChannelDelivery image = Packets.delivery(Packets.messageReady(List.of(
            new ContentPart.Text("p1", "看图"),
            new ContentPart.Image("p2", "file-1", "截图"))), "delivery-2",
            "entry-fake", "destination-1", 1, "测试入口");
        DeliveryReceipt unsupported = instance.deliver(image, DeliveryAttempt.withTimeout(1_000))
            .toCompletableFuture().join();
        DeliveryReceipt.Failed failed = assertInstanceOf(DeliveryReceipt.Failed.class, unsupported);
        assertEquals("UNSUPPORTED_CONTENT", failed.error().code());

        ChannelDelivery withActions = Packets.delivery(Packets.messageReady("批准吗"), "delivery-3",
            "entry-fake", "destination-1", 1, "测试入口",
            List.of(new ChannelDelivery.Action("action-1", "批准")));
        DeliveryReceipt actionReceipt = instance.deliver(withActions, DeliveryAttempt.withTimeout(1_000))
            .toCompletableFuture().join();
        assertEquals("UNSUPPORTED_CONTENT",
            assertInstanceOf(DeliveryReceipt.Failed.class, actionReceipt).error().code());

        assertTrue(instance.deliveries().isEmpty());
    }

    @Test
    void submitReachesIngressPort() {
        ChannelResponse response = instance.submit("request-1", "alice", "你好");

        assertInstanceOf(ChannelResponse.Accepted.class, response);
        assertEquals("request-1", response.requestId());
        assertEquals(1, host.submitted.size());

        VerifiedIngress verified = host.resolved.get(0);
        assertEquals("request-1", verified.requestId());
        assertEquals("fake:entry-fake", verified.externalAccountKey());
        assertEquals(VerifiedIngress.SubjectKind.USER, verified.externalSubject().kind());
        assertEquals("alice", verified.externalSubject().key());
        assertEquals("fake", verified.externalScope().namespace());
        assertEquals("alice", verified.externalReplyTarget().key());
        assertEquals("request-1", verified.sourceEventKey());
        assertEquals("alice", verified.externalReplyTarget().protocolData().path("conversation").asText());

        ChannelOperation operation = host.operation(0);
        ChannelOperation.InputSubmit input = assertInstanceOf(ChannelOperation.InputSubmit.class, operation);
        assertEquals(ChannelOperation.Interpretation.COMMAND_AWARE, input.interpretation());
        ContentPart.Text text = assertInstanceOf(ContentPart.Text.class, input.content().get(0));
        assertEquals("你好", text.text());
        assertNotNull(host.submitted.get(0).identity().callerRef());
    }
}
