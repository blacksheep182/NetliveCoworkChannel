package com.netlivecowork.channel.l2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netlivecowork.channel.contract.AgentEventPayload;
import com.netlivecowork.channel.contract.ChannelOperation;
import com.netlivecowork.channel.contract.ChannelResponse;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.EventSource;
import com.netlivecowork.channel.contract.EventType;
import com.netlivecowork.channel.contract.IngressRequest;
import com.netlivecowork.channel.contract.IngressResolution;
import com.netlivecowork.channel.contract.NormalizedContentPart;
import com.netlivecowork.channel.contract.VerifiedBackendEvent;
import com.netlivecowork.channel.contract.VerifiedIngress;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.l2.store.DeliveryRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 投递恢复语义（INV-35、49、39）：
 * 领取排他、崩溃后不盲目重发、入口暂时不可用可自动重试、目的地版本变化保留可见暂停记录。
 */
class L2DeliveryRecoveryTest {

    /** 已受理的入站输入：解析结果 + 规范指令的 operationRef（事件据此关联回复关系）。 */
    private record Setup(IngressResolution resolution, String operationRef) {
    }

    private Setup acceptedInput(TestModule test) {
        VerifiedIngress input = new VerifiedIngress("req-setup", "acct-1",
            new VerifiedIngress.Subject(VerifiedIngress.SubjectKind.USER, "alice"),
            new VerifiedIngress.ExternalScope("weixin", "alice"),
            new VerifiedIngress.ExternalReplyTarget(VerifiedIngress.Audience.PERSONAL, "alice",
                Json.obj().put("conversation", "alice")),
            "req-setup", null);
        IngressResolution resolution = test.module.ingress(TestModule.ENTRY).resolve(input)
            .toCompletableFuture().join();
        ChannelResponse response = test.module.ingress(TestModule.ENTRY).submit(resolution.admissionRef(),
            new IngressRequest("1", "req-1", resolution.identity(), resolution.routing(),
                new ChannelOperation.InputSubmit("target-1", ChannelOperation.Interpretation.LITERAL,
                    List.of(new ContentPart.Text("p1", "你好")), null))).toCompletableFuture().join();
        ChannelResponse.Accepted accepted = assertInstanceOf(ChannelResponse.Accepted.class, response,
            response.toString());
        return new Setup(resolution, accepted.operationRef());
    }

    private VerifiedBackendEvent messageReady(Setup setup, String eventId) {
        return new VerifiedBackendEvent("be-" + eventId, new EventSource("resource-1", eventId),
            setup.operationRef(), "target-1", null, EventType.MESSAGE_READY,
            Instant.parse("2026-10-27T00:00:01Z"), null,
            new AgentEventPayload.MessageReady(List.of(new NormalizedContentPart.Text("p1", "完成",
                ContentPart.Text.Format.PLAIN)), "bm-" + eventId, null));
    }

    private TestModule setupWithDeliveryReady(String eventId) {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        Setup setup = acceptedInput(test);
        assertTrue(test.module.intake().receiveEvent(messageReady(setup, eventId))
            .toCompletableFuture().join().accepted());
        return test;
    }

    private DeliveryRepository.DeliveryRow firstDelivery(TestModule test) {
        List<DeliveryRepository.DeliveryRow> rows = test.module.deliveries().claimable(10);
        assertTrue(!rows.isEmpty(), "expected a pending delivery");
        return rows.get(0);
    }

    @Test
    void onlyOneWorkerCanClaimTheSameDelivery() {
        TestModule test = setupWithDeliveryReady("e1");
        DeliveryRepository.DeliveryRow row = firstDelivery(test);
        DeliveryRepository deliveries = test.module.deliveries();
        assertTrue(deliveries.markSending(row, "worker-1", test.clock.instant().plusSeconds(60)));
        // 第二个 worker 拿着同一快照再来一次：围栏（status + attempt）拒绝
        assertTrue(!deliveries.markSending(row, "worker-2", test.clock.instant().plusSeconds(60)));
    }

    @Test
    void expiredSendingBecomesUnknownInsteadOfDuplicateSend() {
        TestModule test = setupWithDeliveryReady("e2");
        DeliveryRepository.DeliveryRow row = firstDelivery(test);
        // 模拟上一次领取后崩溃：领取并越过租约，未写回执
        assertTrue(test.module.deliveries().markSending(row, "crashed-worker",
            test.clock.instant().plusSeconds(60)));
        test.clock.advanceMillis(61_000);

        test.module.tick();

        DeliveryRepository.DeliveryRow after = test.module.deliveries().delivery(row.deliveryId());
        assertEquals(DeliveryRepository.DeliveryStatus.UNKNOWN, after.status(),
            "a possibly delivered message must stay unknown, not be resent");
        assertTrue(test.adapter.deliveries.isEmpty(), "adapter must not receive a duplicate after a crash");
    }

    @Test
    void disabledEntryRetriesBeforePausing() {
        TestModule test = setupWithDeliveryReady("e3");
        var entry = test.module.entries().find(TestModule.ENTRY);
        test.module.management().setEntryEnabled(TestModule.ENTRY, false, entry.revision());

        test.module.tick();
        DeliveryRepository.DeliveryRow first = test.module.deliveries().list(null, 10).get(0);
        assertEquals(DeliveryRepository.DeliveryStatus.RETRY, first.status(),
            "temporary unavailability must retry, not pause immediately");

        for (int i = 0; i < 8; i++) {
            test.clock.advanceMillis(61_000);
            test.module.tick();
        }
        DeliveryRepository.DeliveryRow after = test.module.deliveries().list(null, 10).get(0);
        assertEquals(DeliveryRepository.DeliveryStatus.PAUSED, after.status(),
            "after exhausting attempts the delivery is paused for a human, not silently dropped");
    }

    @Test
    void destinationVersionChangeKeepsAVisiblePausedDelivery() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        Setup setup = acceptedInput(test);

        // 同一目的地登记新协议数据：版本递增，旧回复关系不再有效（INV-13）
        test.module.management().registerDestination(setup.resolution().routing().replyTargetRef(),
            TestModule.ENTRY, "PERSONAL", Json.obj().put("conversation", "alice-moved"), 1);

        assertTrue(test.module.intake().receiveEvent(messageReady(setup, "e4"))
            .toCompletableFuture().join().accepted());
        test.module.tick();

        DeliveryRepository.DeliveryRow row = test.module.deliveries().list(null, 10).get(0);
        assertEquals(DeliveryRepository.DeliveryStatus.PAUSED, row.status());
        assertTrue(row.lastError() != null && row.lastError().contains("destination"),
            "the skipped result must stay visible with a reason");
        assertTrue(test.adapter.deliveries.isEmpty());
    }
}
