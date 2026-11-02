package com.netlivecowork.channel.l2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netlivecowork.channel.contract.AgentEventPayload;
import com.netlivecowork.channel.contract.BackendInteraction;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.ChannelOperation;
import com.netlivecowork.channel.contract.ChannelResponse;
import com.netlivecowork.channel.contract.CommandOutcome;
import com.netlivecowork.channel.contract.CommandPacket;
import com.netlivecowork.channel.contract.CommandType;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.Correlation;
import com.netlivecowork.channel.contract.EventSource;
import com.netlivecowork.channel.contract.EventType;
import com.netlivecowork.channel.contract.IngressRequest;
import com.netlivecowork.channel.contract.IngressResolution;
import com.netlivecowork.channel.contract.InteractionPrompt;
import com.netlivecowork.channel.contract.Layer;
import com.netlivecowork.channel.contract.OperationResult;
import com.netlivecowork.channel.contract.Producer;
import com.netlivecowork.channel.contract.ReceiptPacket;
import com.netlivecowork.channel.contract.ReceiptPayload;
import com.netlivecowork.channel.contract.VerifiedBackendEvent;
import com.netlivecowork.channel.contract.VerifiedIngress;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.infra.Ids;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 人工交互的完整生命周期与任务版本顺序（INV-27、INV-59～62）。 */
class L2InteractionTest {

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
        return test.module.ingress(TestModule.ENTRY)
            .submit(resolution.admissionRef(), new IngressRequest("1", requestId, resolution.identity(),
                resolution.routing(), operation))
            .toCompletableFuture().join();
    }

    private ChannelResponse command(TestModule test, String requestId, String text,
                                    IngressResolution resolution) {
        return submit(test, requestId, new ChannelOperation.InputSubmit(null,
            ChannelOperation.Interpretation.COMMAND_AWARE,
            List.of(new ContentPart.Text("p1", text)), null), resolution);
    }

    @Test
    void approvalIsDeliveredAndAppliedExactlyOnce() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();
        IngressResolution resolution = resolve(test, "req-input");
        ChannelResponse.Accepted accepted = assertInstanceOf(ChannelResponse.Accepted.class,
            submit(test, "req-input", new ChannelOperation.InputSubmit("target-1",
                ChannelOperation.Interpretation.LITERAL,
                List.of(new ContentPart.Text("p1", "帮我发布")), null), resolution));
        assertTrue(test.tickUntil(() -> test.module.requests().inboxByOperation(accepted.operationRef())
            .status() == com.netlivecowork.channel.l2.store.RequestRepository.OperationStatus.DISPATCHED));

        InteractionPrompt approval = new InteractionPrompt.Approval(
            List.of(new ContentPart.Text("p1", "是否继续发布?")),
            new InteractionPrompt.Approval.Operation("deploy", Json.obj(), null),
            Set.of(InteractionPrompt.AllowedResponse.APPROVE, InteractionPrompt.AllowedResponse.REJECT));
        VerifiedBackendEvent required = new VerifiedBackendEvent("be-1",
            new EventSource("resource-1", "be-il-1"), accepted.operationRef(), "target-1", null,
            EventType.INTERACTION_REQUIRED, Instant.parse("2026-10-27T00:00:02Z"), null,
            new AgentEventPayload.InteractionRequired(new BackendInteraction("bi-1", 1, null, approval)));
        assertTrue(test.module.intake().receiveEvent(required).toCompletableFuture().join().accepted());
        assertTrue(test.tickUntil(() -> test.adapter.deliveries.size() == 1));
        assertEquals(2, test.adapter.deliveries.get(0).actions().size());

        String interactionRef = test.module.requests().interactionByBackend("target-1", "bi-1").interactionRef();
        assertNotNull(interactionRef);

        // 后端确认本次响应生效后才表达 applied（INV-61）
        test.backend.outcome = command -> completedApplied(test, command);
        ChannelResponse approve = command(test, "req-approve", "/approve " + interactionRef, resolution);
        assertInstanceOf(ChannelResponse.Accepted.class, approve);
        assertTrue(test.tickUntil(() -> {
            var response = test.module.requests().responseByOperation(
                ((ChannelResponse.Accepted) approve).operationRef());
            return response != null && "APPLIED".equals(response.status());
        }));

        CommandPacket respond = test.backend.commands.stream()
            .filter(packet -> packet.type() == CommandType.INTERACTION_RESPOND)
            .findFirst().orElseThrow();
        assertEquals(interactionRef, ((com.netlivecowork.channel.contract.CommandPayload.InteractionRespond)
            respond.payload()).payload().interactionRef());

        ChannelResponse repeated = command(test, "req-approve-2", "/approve " + interactionRef, resolution);
        assertEquals(ChannelErrorCode.INTERACTION_STALE.name(),
            assertInstanceOf(ChannelResponse.Rejected.class, repeated).error().code());
    }

    private CommandOutcome completedApplied(TestModule test, CommandPacket command) {
        ReceiptPacket receipt = new ReceiptPacket("1", Ids.next("receipt"), test.clock.instant(),
            new Correlation(command.correlation().flowId(), command.packetId()), command.context(),
            new Producer(Layer.L3, "l3-backend"), new ReceiptPayload.Completed(
            new OperationResult.InteractionRespond(((com.netlivecowork.channel.contract.CommandPayload
                .InteractionRespond) command.payload()).payload().interactionRef())));
        return CommandOutcome.accepted("dispatch-1", receipt);
    }

    @Test
    void staleTaskRevisionIsRecordedButNotDelivered() {
        TestModule test = new TestModule();
        test.createEntry();
        test.module.management().syncTargets();

        VerifiedBackendEvent started = new VerifiedBackendEvent("be-s", new EventSource("resource-1", "be-s"),
            null, "target-1", null, EventType.TASK_STARTED, Instant.parse("2026-10-27T00:00:01Z"), null,
            new AgentEventPayload.TaskStarted("bt-1", 2));
        assertTrue(test.module.intake().receiveEvent(started).toCompletableFuture().join().accepted());

        VerifiedBackendEvent stale = new VerifiedBackendEvent("be-p", new EventSource("resource-1", "be-p"),
            null, "target-1", null, EventType.TASK_PROGRESS, Instant.parse("2026-10-27T00:00:02Z"), null,
            new AgentEventPayload.TaskProgress("bt-1", 1, "older", null));
        assertTrue(test.module.intake().receiveEvent(stale).toCompletableFuture().join().accepted());
        assertEquals(com.netlivecowork.channel.l2.store.EventRepository.EventState.SKIPPED_STALE,
            test.module.events().find("resource-1", "be-p").state());
        assertEquals(com.netlivecowork.channel.l2.store.EventRepository.EventState.PACKETIZED,
            test.module.events().find("resource-1", "be-s").state());
    }

    @Test void nativeInteractionRecoveryUsesActualTaskAndTerminalFactsRemovePendingWithoutRevival() {
        TestModule test=new TestModule();test.createEntry();test.module.management().syncTargets();
        var prompt=new InteractionPrompt.Approval(List.of(new ContentPart.Text("p","批准安全操作")),
            new InteractionPrompt.Approval.Operation("fs:shell",Json.obj().put("command","echo safe"),null),null);
        var required=new VerifiedBackendEvent("native-open",new EventSource("resource-1","native-open"),null,"target-1",null,
            EventType.INTERACTION_REQUIRED,test.clock.instant(),null,
            new AgentEventPayload.InteractionRequired(new BackendInteraction("native-hit",10,null,prompt,"actual-backend-task")));
        assertTrue(test.module.intake().receiveEvent(required).toCompletableFuture().join().accepted());
        var pending=test.module.intake().pendingInteractions("target-1");assertEquals(1,pending.size());
        assertNotNull(pending.get(0).context().taskRef());
        assertEquals(test.module.requests().taskByBackend("target-1","actual-backend-task").taskRef(),pending.get(0).context().taskRef());
        var resolved=new VerifiedBackendEvent("native-resolved",new EventSource("resource-1","native-resolved"),null,"target-1",null,
            EventType.INTERACTION_RESOLVED,test.clock.instant(),null,
            new AgentEventPayload.InteractionResolved(new com.netlivecowork.channel.contract.BackendInteractionRef("native-hit",11),com.netlivecowork.channel.contract.EventPayload.Outcome.APPROVED));
        assertTrue(test.module.intake().receiveEvent(resolved).toCompletableFuture().join().accepted());
        assertEquals(0,test.module.intake().pendingInteractions("target-1").size());
        test.module.intake().receiveEvent(required).toCompletableFuture().join();
        assertEquals(0,test.module.intake().pendingInteractions("target-1").size());
    }
}
