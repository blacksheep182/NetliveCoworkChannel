package com.netlivecowork.channel.l2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netlivecowork.channel.contract.AgentEventPayload;
import com.netlivecowork.channel.contract.ChannelDelivery;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.ChannelMessage;
import com.netlivecowork.channel.contract.ChannelOperation;
import com.netlivecowork.channel.contract.ChannelResponse;
import com.netlivecowork.channel.contract.CommandType;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.EventSource;
import com.netlivecowork.channel.contract.EventType;
import com.netlivecowork.channel.contract.IngressRequest;
import com.netlivecowork.channel.contract.IngressResolution;
import com.netlivecowork.channel.contract.NormalizedContentPart;
import com.netlivecowork.channel.contract.TargetSummary;
import com.netlivecowork.channel.contract.VerifiedBackendEvent;
import com.netlivecowork.channel.contract.VerifiedIngress;
import com.netlivecowork.channel.contract.json.Json;
import org.junit.jupiter.api.Test;

/** 受理、去重、事件接管与固定目的地投递（INV-14、INV-28～31、INV-34）。 */
class L2FlowTest {

    private IngressResolution resolve(TestModule test, String requestId) {
        VerifiedIngress input = new VerifiedIngress(requestId, "acct-1",
            new VerifiedIngress.Subject(VerifiedIngress.SubjectKind.USER, "alice"),
            new VerifiedIngress.ExternalScope("weixin", "alice"),
            new VerifiedIngress.ExternalReplyTarget(VerifiedIngress.Audience.PERSONAL, "alice",
                Json.obj().put("conversation", "alice")),
            requestId, null);
        return test.module.ingress(TestModule.ENTRY).resolve(input).toCompletableFuture().join();
    }

    private ChannelResponse submit(TestModule test, String requestId, ChannelOperation operation,
                                   IngressResolution resolution) {
        IngressRequest request = new IngressRequest("1", requestId, resolution.identity(),
            resolution.routing(), operation);
        return test.module.ingress(TestModule.ENTRY).submit(resolution.admissionRef(), request)
            .toCompletableFuture().join();
    }

    private ChannelResponse input(TestModule test, String requestId, String text, IngressResolution resolution) {
        return submit(test, requestId, new ChannelOperation.InputSubmit("target-1",
            ChannelOperation.Interpretation.LITERAL,
            java.util.List.of(new ContentPart.Text("p1", text)), null), resolution);
    }

    private VerifiedBackendEvent messageReady(String eventId, String operationRef, String text) {
        return new VerifiedBackendEvent("be-" + eventId, new EventSource("resource-1", eventId), operationRef,
            "target-1", null, EventType.MESSAGE_READY, java.time.Instant.parse("2026-10-27T00:00:01Z"), null,
            new AgentEventPayload.MessageReady(java.util.List.of(new NormalizedContentPart.Text("p1", text,
                ContentPart.Text.Format.PLAIN)), "bm-" + eventId, null));
    }

    @Test
    void inputSubmitIsAcceptedOnceAndDeliveredToTheFixedDestination() {
        TestModule test = new TestModule();
        test.createEntry();
        assertEquals(1, test.module.management().syncTargets());
        IngressResolution resolution = resolve(test, "req-1");

        ChannelResponse first = input(test, "req-1", "你好", resolution);
        ChannelResponse.Accepted accepted = assertInstanceOf(ChannelResponse.Accepted.class, first);
        assertEquals(com.netlivecowork.channel.contract.ReceiptPayload.AcceptedBy.CHANNEL, accepted.acceptedBy());
        assertNotNull(accepted.operationRef());

        // 等待派发完成（DISPATCHING 只是领取中，不代表后端受理）
        assertTrue(test.tickUntil(() -> test.backend.commands.size() == 1));
        assertEquals(CommandType.INPUT_SUBMIT, test.backend.commands.get(0).type());
        assertTrue(test.tickUntil(() -> test.module.requests().inboxByOperation(accepted.operationRef()).status()
            == com.netlivecowork.channel.l2.store.RequestRepository.OperationStatus.DISPATCHED));
        var confirmed = assertInstanceOf(ChannelResponse.Accepted.class,
            test.module.ingress(TestModule.ENTRY).lookup(resolution.admissionRef(), "req-1").toCompletableFuture().join());
        assertEquals(com.netlivecowork.channel.contract.ReceiptPayload.AcceptedBy.AGENT, confirmed.acceptedBy());
        var legacy = com.netlivecowork.channel.contract.json.PacketJson.enc(confirmed);legacy.remove("acceptedBy");
        test.module.requests().updateInbox(com.netlivecowork.channel.l2.store.RequestRepository.OperationStatus.DISPATCHED,
            Json.write(legacy), accepted.operationRef());
        var migrated = assertInstanceOf(ChannelResponse.Accepted.class,
            test.module.ingress(TestModule.ENTRY).lookup(resolution.admissionRef(), "req-1").toCompletableFuture().join());
        assertEquals(com.netlivecowork.channel.contract.ReceiptPayload.AcceptedBy.AGENT, migrated.acceptedBy());

        // 同请求同参数幂等返回既有结果（INV-14）
        ChannelResponse replay = input(test, "req-1", "你好", resolution);
        assertEquals(accepted.operationRef(), assertInstanceOf(ChannelResponse.Accepted.class, replay)
            .operationRef());

        // 同请求不同内容冲突
        ChannelResponse conflict = input(test, "req-1", "换一个内容", resolution);
        assertEquals(ChannelErrorCode.REQUEST_CONFLICT.name(),
            assertInstanceOf(ChannelResponse.Rejected.class, conflict).error().code());

        // 完整回复事件到达后按固定回复关系投递
        var event = messageReady("e1", accepted.operationRef(), "月度分析已完成");
        assertTrue(test.module.intake().receiveEvent(event).toCompletableFuture().join().accepted());
        assertTrue(test.tickUntil(() -> test.adapter.deliveries.size() == 1));
        ChannelDelivery delivery = test.adapter.deliveries.get(0);
        assertEquals(EventType.MESSAGE_READY, delivery.packet().type());
        ChannelMessage message = ((com.netlivecowork.channel.contract.EventPayload.MessageReady)
            delivery.packet().payload()).message();
        assertEquals("月度分析已完成", ((ContentPart.Text) message.content().get(0)).text());
        assertEquals(TestModule.ENTRY, delivery.routing().entryRef());
        assertEquals(resolution.routing().replyTargetRef(), delivery.routing().destinationRef());
        assertTrue(delivery.presentation().sourceLabel().contains("报表助手"));

        // 同一来源事件重发保持幂等，不产生第二次投递
        assertTrue(test.module.intake().receiveEvent(event).toCompletableFuture().join().accepted());
        test.module.tick();
        assertEquals(1, test.adapter.deliveries.size());
    }

    @Test
    void listAndUseShareTheSameOrdering() {
        TestModule test = new TestModule();
        test.createEntry();
        test.backend.targets.add(TargetSummary.of("target-2", "另一个助手"));
        test.module.management().syncTargets();
        IngressResolution resolution = resolve(test, "req-list");

        ChannelResponse response = submit(test, "req-list",
            new ChannelOperation.SessionList(null, null), resolution);
        ChannelResponse.Completed completed = assertInstanceOf(ChannelResponse.Completed.class, response);
        assertEquals(CommandType.SESSION_LIST, completed.result().type());
        var items = ((com.netlivecowork.channel.contract.OperationResult.SessionList) completed.result())
            .items();
        assertEquals(1, items.size(), "unverified targets must be omitted");
        // L2 先授权再经 L3 查询真实元数据；未授权目标不访问后端
        assertEquals(1, test.backend.queryCalls.get());

        // 同一来源的顺序：/use 2 必须解析到列表里的第 2 项（不假设排序规则）
        ChannelResponse select = submit(test, "req-use-1", new ChannelOperation.InputSubmit(null,
            ChannelOperation.Interpretation.COMMAND_AWARE,
            java.util.List.of(new ContentPart.Text("p1", "/use 1")), null), resolution);
        assertInstanceOf(ChannelResponse.Completed.class, select);
        assertEquals(items.get(0).targetRef(), test.module.directory().binding(TestModule.TENANT,
            TestModule.WORKSPACE, resolution.routing().scopeRef()).targetRef());
    }

    @Test
    void scopeBindingUsesCompareAndSet() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        IngressResolution resolution = resolve(test, "req-select");

        ChannelResponse first = submit(test, "req-select",
            new ChannelOperation.SessionSelect("target-1", 0), resolution);
        assertInstanceOf(ChannelResponse.Completed.class, first);

        ChannelResponse second = submit(test, "req-select-2",
            new ChannelOperation.SessionSelect("target-1", 0), resolution);
        assertEquals(ChannelErrorCode.BINDING_CONFLICT.name(),
            assertInstanceOf(ChannelResponse.Rejected.class, second).error().code());
    }

    @Test
    void textCommandAwareInputSelectsBoundTarget() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        IngressResolution resolution = resolve(test, "req-use");
        submit(test,"req-list-before-use",new ChannelOperation.SessionList(null,null),resolution);

        ChannelResponse response = submit(test, "req-use", new ChannelOperation.InputSubmit(null,
            ChannelOperation.Interpretation.COMMAND_AWARE,
            java.util.List.of(new ContentPart.Text("p1", "/use 1")), null), resolution);
        assertInstanceOf(ChannelResponse.Completed.class, response);
        assertEquals("target-1", test.module.directory().binding(TestModule.TENANT,
            TestModule.WORKSPACE, resolution.routing().scopeRef()).targetRef());
    }

    @Test
    void eventWithoutKnownTargetStaysPendingUntilRegistryIsReady() {
        TestModule test = new TestModule(false);
        test.createEntry();

        VerifiedBackendEvent event = messageReady("e-pending", null, "后台任务结果");
        assertTrue(test.module.intake().receiveEvent(event).toCompletableFuture().join().accepted());
        assertEquals(com.netlivecowork.channel.l2.store.EventRepository.EventState.PENDING_RELATION,
            test.module.events().find("resource-1", "e-pending").state());

        test.module.management().syncTargets();
        assertEquals(1, test.module.intake().reprocessPending(10));
        assertEquals(com.netlivecowork.channel.l2.store.EventRepository.EventState.PACKETIZED,
            test.module.events().find("resource-1", "e-pending").state());
    }

    @Test
    void duplicateEventContentConflictIsQuarantined() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        VerifiedBackendEvent first = messageReady("e2", null, "结果 A");
        assertTrue(test.module.intake().receiveEvent(first).toCompletableFuture().join().accepted());
        VerifiedBackendEvent second = messageReady("e2", null, "结果 B");
        assertTrue(!test.module.intake().receiveEvent(second).toCompletableFuture().join().accepted());
        assertEquals(com.netlivecowork.channel.l2.store.EventRepository.EventState.QUARANTINED,
            test.module.events().find("resource-1", "e2").state());
    }
}
