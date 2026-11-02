package com.netlivecowork.channel.l3;

import com.netlivecowork.channel.contract.Actor;
import com.netlivecowork.channel.contract.AgentEvent;
import com.netlivecowork.channel.contract.AgentEventPayload;
import com.netlivecowork.channel.contract.ChannelError;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.ChannelMessage;
import com.netlivecowork.channel.contract.ChannelOperation;
import com.netlivecowork.channel.contract.CommandPacket;
import com.netlivecowork.channel.contract.CommandPayload;
import com.netlivecowork.channel.contract.CommandType;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.Correlation;
import com.netlivecowork.channel.contract.IntakeAck;
import com.netlivecowork.channel.contract.InteractionRespondPayload;
import com.netlivecowork.channel.contract.InteractionResponse;
import com.netlivecowork.channel.contract.Layer;
import com.netlivecowork.channel.contract.NormalizedContentPart;
import com.netlivecowork.channel.contract.PacketContext;
import com.netlivecowork.channel.contract.Producer;
import com.netlivecowork.channel.contract.ReceiptPacket;
import com.netlivecowork.channel.contract.TargetSummary;
import com.netlivecowork.channel.contract.TrustedCall;
import com.netlivecowork.channel.contract.VerifiedBackendEvent;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.json.PacketJson;
import com.netlivecowork.channel.contract.port.ApplicationIntakePort;
import com.netlivecowork.channel.infra.Db;
import com.netlivecowork.channel.infra.Ids;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

/** 测试夹具：登记、指令构造、假 L2 接管端口。 */
final class TestFixtures {

    static final String TENANT = "tenant-1";
    static final String WORKSPACE = "ws-1";
    static final String SINK = "sink-1";
    static final String SERVICE = "service-1";
    static final String TARGET = "target-1";
    static final String SESSION = "session-1";
    static final String AGENT = "agent-1";
    static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    private TestFixtures() {
    }

    static L3Settings settings() {
        return settings(2_000L, 2, false, false);
    }

    static L3Settings settings(long httpTimeoutMs, int forwardMaxAttempts, boolean filesSupported,
                               boolean watchSupported) {
        return new L3Settings(filesSupported, watchSupported, httpTimeoutMs, forwardMaxAttempts, 0L, 0L, 5, SINK);
    }

    static Db db(Path directory) {
        return Db.file(directory.resolve("l3-" + Ids.random(6) + ".db"));
    }

    static void seed(L3Module module, String baseUrl) {
        module.registry().upsertService(new BackendService(SERVICE, TENANT, WORKSPACE, "Local backend", baseUrl,
            AccessType.LOCAL, "cred-1", true));
        module.registry().upsertTarget(new TargetRegistration(TARGET, TENANT, WORKSPACE, "user-1", SERVICE,
            SESSION, 1L, "Team session", AGENT, List.of("task.cancel"), TargetSummary.Availability.ONLINE, 1L,
            true));
    }

    /** 测试身份适配器：登记与 TestBackend 协议前奏一致，W3 subject 映射为 caller user-1。 */
    static BackendIdentityResolver identities() {
        return new BackendIdentityResolver() {
            @Override
            public Registration registration(String serviceRef) {
                return new Registration(TestBackend.BACKEND_ID, "client-1", TENANT, WORKSPACE,
                    TestBackend.ADAPTER_ID);
            }

            @Override
            public String principalRef(String serviceRef, String issuer, String subject) {
                return "w3".equals(issuer) && "user-1".equals(subject) ? "user-1" : null;
            }

            @Override
            public Delegation catalogIdentity(TrustedCall call,String serviceRef) {
                return new Delegation("test-catalog-proof","w3","user-1",Instant.now().plusSeconds(30));
            }
            @Override
            public Delegation obtain(TrustedCall call, BackendCallBinding binding) {
                Instant expires = Instant.now().plusSeconds(60);
                if (call.deadline() != null && call.deadline().isBefore(expires)) {
                    expires = call.deadline();
                }
                return expires.isAfter(Instant.now())
                    ? new Delegation("proof-test", "w3", "user-1", expires) : null;
            }
        };
    }

    static TrustedCall call() {
        return call(TENANT, WORKSPACE);
    }

    static TrustedCall call(String tenantRef, String workspaceRef) {
        return new TrustedCall(tenantRef, workspaceRef, "entry-1", "user-1", null, 1L, null);
    }

    static CommandPacket submit(String operationRef, String text) {
        return submit(operationRef, TARGET, text);
    }

    static CommandPacket submit(String operationRef, String targetRef, String text) {
        return command(operationRef, targetRef, CommandType.INPUT_SUBMIT, new CommandPayload.InputSubmit(
            ChannelMessage.of("msg-1", new ContentPart.Text("p1", text)),
            ChannelOperation.Interpretation.LITERAL));
    }

    static CommandPacket interactionRespond(String operationRef, String interactionRef, long revision,
                                            InteractionResponse response) {
        return command(operationRef, TARGET, CommandType.INTERACTION_RESPOND,
            new CommandPayload.InteractionRespond(new InteractionRespondPayload(interactionRef, revision, response)));
    }

    static CommandPacket command(String operationRef, String targetRef, CommandType type, CommandPayload payload) {
        return new CommandPacket("1", Ids.next("cmd"), type, NOW, new Correlation("flow-1", null),
            new PacketContext(TENANT, WORKSPACE, targetRef, operationRef, null),
            new Producer(Layer.L2, "l2-app"), new Actor("user-1", null), payload);
    }

    /** 后端原始事件 JSON（AgentEvent 规范 wire）。 */
    static String agentEvent(String eventId, String requestKey, String text) {
        AgentEvent event = new AgentEvent("1", eventId, "message.ready", NOW.plusSeconds(1),
            new AgentEvent.Resource(SESSION, null, null),
            requestKey == null ? null : new AgentEvent.AgentCorrelation(requestKey, null), null,
            new AgentEventPayload.MessageReady(
                List.of(new NormalizedContentPart.Text("p1", text, ContentPart.Text.Format.PLAIN)),
                "backend-message-1", null));
        return Json.write(PacketJson.enc(event));
    }

    static String scalar(Db db, String sql, Object... params) {
        return db.queryOne(sql, rs -> rs.getString(1), params);
    }

    /** L2 接管端口替身：可切换接受/拒绝，记录收到的事件。 */
    static final class RecordingIntake implements ApplicationIntakePort {

        private final List<VerifiedBackendEvent> events = new CopyOnWriteArrayList<>();
        private final List<ReceiptPacket> receipts = new CopyOnWriteArrayList<>();
        volatile boolean accepting = true;
        volatile int attempts;

        @Override
        public CompletionStage<IntakeAck> receiveEvent(VerifiedBackendEvent event) {
            attempts++;
            events.add(event);
            return CompletableFuture.completedFuture(accepting
                ? IntakeAck.ok()
                : IntakeAck.rejected(ChannelError.of(ChannelErrorCode.UNAVAILABLE, "intake unavailable")));
        }

        @Override
        public CompletionStage<IntakeAck> receiveReceipt(ReceiptPacket receipt) {
            receipts.add(receipt);
            return CompletableFuture.completedFuture(IntakeAck.ok());
        }

        List<VerifiedBackendEvent> received() {
            return events;
        }

        List<ReceiptPacket> receipts() {
            return receipts;
        }
    }
}
