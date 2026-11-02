package com.netlivecowork.channel.l2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * 身份重绑隔离（INV-54、INV-55）：换绑后旧的 scope / 目的地标识不再可达，
 * 旧待投递记录在发送前失效并留下可核对状态，不转发给新身份。
 */
class L2IdentityRebindTest {

    private static final String SUBJECT = "alice";

    private IngressResolution resolve(TestModule test) {
        VerifiedIngress input = new VerifiedIngress("req-resolve", "acct-1",
            new VerifiedIngress.Subject(VerifiedIngress.SubjectKind.USER, SUBJECT),
            new VerifiedIngress.ExternalScope("weixin", SUBJECT),
            new VerifiedIngress.ExternalReplyTarget(VerifiedIngress.Audience.PERSONAL, SUBJECT,
                Json.obj().put("conversation", SUBJECT)),
            "req-resolve", null);
        return test.module.ingress(TestModule.ENTRY).resolve(input).toCompletableFuture().join();
    }

    @Test
    void rebindingInvalidatesOldScopesDestinationsAndPendingDeliveries() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();

        IngressResolution before = resolve(test);
        String oldScope = before.routing().scopeRef();
        String oldDestination = before.routing().replyTargetRef();

        // 旧身份受理一次输入，并产生一条待投递结果
        ChannelResponse response = test.module.ingress(TestModule.ENTRY).submit(before.admissionRef(),
            new IngressRequest("1", "req-1", before.identity(), before.routing(),
                new ChannelOperation.InputSubmit("target-1", ChannelOperation.Interpretation.LITERAL,
                    List.of(new ContentPart.Text("p1", "你好")), null))).toCompletableFuture().join();
        ChannelResponse.Accepted accepted = assertInstanceOf(ChannelResponse.Accepted.class, response,
            response.toString());
        VerifiedBackendEvent event = new VerifiedBackendEvent("be-1", new EventSource("resource-1", "be-1"),
            accepted.operationRef(), "target-1", null, EventType.MESSAGE_READY,
            Instant.parse("2026-10-27T00:00:01Z"), null,
            new AgentEventPayload.MessageReady(List.of(new NormalizedContentPart.Text("p1", "完成",
                ContentPart.Text.Format.PLAIN)), "bm-1", null));
        assertTrue(test.module.intake().receiveEvent(event).toCompletableFuture().join().accepted());

        // 换绑：同一外部身份改绑到另一个内部主体
        test.module.management().bindIdentity(TestModule.ENTRY, "USER", SUBJECT, "caller-bob");

        // 旧范围绑定被移除，新解析得到不同的 scope/目的地标识
        assertNull(test.module.directory().binding(TestModule.TENANT, TestModule.WORKSPACE, oldScope));
        IngressResolution after = resolve(test);
        assertEquals("caller-bob", after.identity().callerRef());
        assertNotEquals(oldScope, after.routing().scopeRef());
        assertNotEquals(oldDestination, after.routing().replyTargetRef());
        assertEquals("caller-bob", test.module.directory().destination(after.routing().replyTargetRef())
            .callerRef());

        // 旧积压在发送前失效：保留可核对的暂停记录，不投给新身份
        test.module.tick();
        DeliveryRepository.DeliveryRow oldDelivery = test.module.deliveries().list(null, 10).stream()
            .filter(row -> row.destinationRef().equals(oldDestination))
            .findFirst().orElseThrow();
        assertEquals(DeliveryRepository.DeliveryStatus.PAUSED, oldDelivery.status());
        assertTrue(test.adapter.deliveries.isEmpty());
    }
}
