package com.netlivecowork.channel.l2;

import com.fasterxml.jackson.databind.JsonNode;
import com.netlivecowork.channel.contract.ChannelDelivery;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.CommandOutcome;
import com.netlivecowork.channel.contract.CommandPacket;
import com.netlivecowork.channel.contract.CommandType;
import com.netlivecowork.channel.contract.Correlation;
import com.netlivecowork.channel.contract.DeliveryAttempt;
import com.netlivecowork.channel.contract.DeliveryReceipt;
import com.netlivecowork.channel.contract.EventType;
import com.netlivecowork.channel.contract.FileReadResult;
import com.netlivecowork.channel.contract.Layer;
import com.netlivecowork.channel.contract.OperationLookup;
import com.netlivecowork.channel.contract.OperationResult;
import com.netlivecowork.channel.contract.PageRequest;
import com.netlivecowork.channel.contract.Producer;
import com.netlivecowork.channel.contract.ReceiptPacket;
import com.netlivecowork.channel.contract.ReceiptPayload;
import com.netlivecowork.channel.contract.SessionWatch;
import com.netlivecowork.channel.contract.SessionWatchRequest;
import com.netlivecowork.channel.contract.TargetSummary;
import com.netlivecowork.channel.contract.TrustedCall;
import com.netlivecowork.channel.contract.adapter.ActivationResult;
import com.netlivecowork.channel.contract.adapter.AdapterCapabilities;
import com.netlivecowork.channel.contract.adapter.AdapterConfig;
import com.netlivecowork.channel.contract.adapter.AdapterDescriptor;
import com.netlivecowork.channel.contract.adapter.AdapterFactory;
import com.netlivecowork.channel.contract.adapter.AdapterInstance;
import com.netlivecowork.channel.contract.adapter.AdapterInstanceConfig;
import com.netlivecowork.channel.contract.adapter.ConnectionPolicy;
import com.netlivecowork.channel.contract.adapter.EntryConfig;
import com.netlivecowork.channel.contract.adapter.LifecycleMode;
import com.netlivecowork.channel.contract.adapter.StopReason;
import com.netlivecowork.channel.contract.adapter.ValidationResult;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.json.PacketJson;
import com.netlivecowork.channel.contract.port.AdapterCatalog;
import com.netlivecowork.channel.contract.port.AdapterHost;
import com.netlivecowork.channel.contract.port.BackendAccessPort;
import com.netlivecowork.channel.contract.port.DeliveryPort;
import com.netlivecowork.channel.infra.Db;
import com.netlivecowork.channel.infra.Ids;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** 测试装配：内存 SQLite、可推进时钟、记录型 Adapter 与后端端口。 */
final class TestModule {

    static final String TENANT = "tenant-1";
    static final String WORKSPACE = "workspace-1";
    static final String ENTRY = "entry-1";

    final MutableClock clock = new MutableClock(Instant.parse("2026-10-27T00:00:00Z"));
    final Db db = tempDb();
    final RecordingAdapter adapter = new RecordingAdapter();
    final RecordingBackend backend = new RecordingBackend(clock);
    final L2Module module;
    final AdapterCatalog catalog;

    /** 使用临时文件数据库：共享缓存的内存库在多连接并发下会触发表级锁。 */
    private static Db tempDb() {
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("channel-l2-test");
            dir.toFile().deleteOnExit();
            java.nio.file.Path file = dir.resolve("channel.db");
            file.toFile().deleteOnExit();
            return Db.file(file);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private final boolean authorizedFixture;

    TestModule() { this(true); }

    TestModule(boolean authorizedFixture) { this(authorizedFixture,false); }

    TestModule(boolean authorizedFixture, boolean weixinFixture) {
        this.authorizedFixture = authorizedFixture;
        AdapterDescriptor weixinDescriptor = weixinFixture ? new AdapterDescriptor("weixin", "Weixin fixture",
            "1.0.0", List.of("1"), List.of(1), Json.obj(), Json.obj(), adapter.describe().capabilities(),
            java.util.Set.of(LifecycleMode.PASSIVE)) : null;
        this.catalog = new AdapterCatalog() {
            @Override
            public List<AdapterDescriptor> descriptors() {
                return weixinDescriptor==null?List.of(adapter.describe()):List.of(adapter.describe(),weixinDescriptor);
            }

            @Override
            public AdapterFactory factory(String type) {
                return "fake".equals(type) ? adapter : null;
            }

            @Override
            public AdapterDescriptor descriptor(String type) {
                return "fake".equals(type) ? adapter.describe() : "weixin".equals(type)?weixinDescriptor:null;
            }
        };
        this.module = new L2Module(db, clock, settings(), catalog, backend, null);
    }

    static L2Settings settings() {
        return new L2Settings(TENANT, WORKSPACE, "test-l2", 300, 10, 1, 60_000, 900, 5_000, 5, 1, 300_000,
            60, 20, 20, true);
    }

    EntryConfig createEntry() {
        EntryConfig config = new EntryConfig(TENANT, WORKSPACE, ENTRY, "测试接入", "fake", 1, 1, 1, true,
            Json.obj(), Map.of(), ConnectionPolicy.defaults(), "default");
        module.management().createEntry(config, null);
        module.start();
        if (authorizedFixture) {
            module.management().syncTargets();
            verifiedOwner("fixture-owner");
            java.util.Set<String> callers = new java.util.HashSet<>(List.of("caller-a", "caller-b", "caller-bob"));
            for (String subject : List.of("alice", "bob", "x", "y"))
                for (int epoch = 1; epoch <= 2; epoch++)
                    callers.add("caller-" + com.netlivecowork.channel.infra.Digests.sha256(
                        String.join("\u0000", ENTRY, String.valueOf(epoch), "USER", subject)).substring(0,23));
            for (String caller : callers) allow(caller);
        }
        return config;
    }


    // Explicit trusted test facts and local delegation policy; production configuration is not evidence.
    void verifiedOwner(String owner) {
        module.directory().setTargetOwner("target-1", owner);
        db.update("UPDATE l2_targets SET resource_epoch=1, evidence_valid_until=?, delegable_terms=? WHERE target_ref='target-1'",
            clock.instant().plusSeconds(604800).toString(), Json.write(Json.obj()
                .put("validFrom", clock.instant().toString()).put("validUntil", clock.instant().plusSeconds(604800).toString())
                .set("permissions", Json.mapper().valueToTree(List.of(
                    java.util.Map.of("action", "target.read"), java.util.Map.of("action", "session.select"),
                    java.util.Map.of("action", "input.submit"), java.util.Map.of("action", "task.read"),
                    java.util.Map.of("action", "task.cancel"), java.util.Map.of("action", "interaction.respond",
                        "responses", List.of("approve","reject","answer"), "allowArgumentEdit", true),
                    java.util.Map.of("action", "events.observe", "eventTypes", java.util.Arrays.stream(EventType.values()).map(EventType::wire).toList()))))));
    }

    void allow(String caller) {
        var terms = new com.netlivecowork.channel.contract.grant.GrantManagement.GrantTerms(List.of(
            new com.netlivecowork.channel.contract.grant.GrantManagement.TargetRead(),
            new com.netlivecowork.channel.contract.grant.GrantManagement.SessionSelect(),
            new com.netlivecowork.channel.contract.grant.GrantManagement.InputSubmit(),
            new com.netlivecowork.channel.contract.grant.GrantManagement.TaskRead(),
            new com.netlivecowork.channel.contract.grant.GrantManagement.TaskCancel(),
            new com.netlivecowork.channel.contract.grant.GrantManagement.InteractionRespond(List.of("approve","reject","answer"),true)),
            clock.instant(), clock.instant().plusSeconds(604800));
        var reply = module.grants().createGrant(new com.netlivecowork.channel.contract.grant.GrantManagement.TrustedManagementContext(
            TENANT,WORKSPACE,module.directory().targetOwner("target-1"),"fixture-auth"),
            new com.netlivecowork.channel.contract.grant.GrantManagement.CreateGrantRequest("fixture-"+caller,
                new com.netlivecowork.channel.contract.grant.GrantManagement.GrantSpec(terms,caller,
                    new com.netlivecowork.channel.contract.grant.GrantManagement.GrantResource("target-1",1,null)),"fixture"))
            .toCompletableFuture().join();
        org.junit.jupiter.api.Assertions.assertInstanceOf(
            com.netlivecowork.channel.contract.grant.GrantManagement.GrantMutationReply.Committed.class, reply);
    }

    /** 推进周期任务直到条件满足或超时。 */
    boolean tickUntil(java.util.function.BooleanSupplier condition) {
        for (int i = 0; i < 200; i++) {
            module.tick();
            if (condition.getAsBoolean()) {
                return true;
            }
            clock.advanceMillis(1_000);
        }
        return condition.getAsBoolean();
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceMillis(long millis) {
            now = now.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** 记录投递并按文本接受；用于验证 L2 → L1 的固定目的地与内容。 */
    static final class RecordingAdapter implements AdapterFactory {
        AdapterHost host;
        final List<ChannelDelivery> deliveries = new CopyOnWriteArrayList<>();

        @Override
        public AdapterDescriptor describe() {
            AdapterCapabilities capabilities = new AdapterCapabilities(LifecycleMode.PASSIVE, true, true, false,
                false, AdapterCapabilities.Files.none(),
                new AdapterCapabilities.Delivery(java.util.Set.of(EventType.values()),
                    java.util.Set.of(com.netlivecowork.channel.contract.ContentPart.PartType.TEXT), false,
                    AdapterCapabilities.Idempotency.LOCAL_ONLY, false, 4_000L, null,AdapterCapabilities.Handover.PROVIDER,AdapterCapabilities.CompletionNotice.STRUCTURED));
            return new AdapterDescriptor("fake", "Fake", "1.0.0", List.of("1"), List.of(1), Json.obj(), Json.obj(),
                capabilities, java.util.Set.of(LifecycleMode.PASSIVE));
        }

        @Override
        public ValidationResult validateConfig(AdapterConfig config) {
            return ValidationResult.ok();
        }

        @Override
        public AdapterInstance create(AdapterInstanceConfig config, AdapterHost host) {
            this.host=host;
            return new AdapterInstance() {
                @Override
                public String entryRef() {
                    return config.entryRef();
                }

                @Override
                public CompletionStage<ActivationResult> activate() {
                    return CompletableFuture.completedFuture(new ActivationResult(describe().capabilities(), null));
                }

                @Override
                public CompletionStage<Void> deactivate(StopReason reason) {
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public DeliveryPort delivery() {
                    return (delivery, attempt) -> {
                        deliveries.add(delivery);
                        return CompletableFuture.completedFuture(new DeliveryReceipt.Accepted(
                            delivery.deliveryId(), "platform-1"));
                    };
                }
            };
        }
    }

    /** 记录指令并返回可配置结果的 L3 替身。 */
    static final class RecordingBackend implements BackendAccessPort {
        volatile java.util.List<com.netlivecowork.channel.contract.SessionWorkspace> creatable = java.util.List.of();
        final java.util.List<Boolean> creationLookups = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile java.util.function.Function<Boolean,CompletionStage<com.netlivecowork.channel.contract.SessionCreation.Created>> creator;
        public CompletionStage<java.util.List<com.netlivecowork.channel.contract.SessionWorkspace>> creationWorkspaces(TrustedCall call) {return CompletableFuture.completedFuture(creatable);}
        public CompletionStage<com.netlivecowork.channel.contract.SessionCreation.Created> createSession(TrustedCall call,String key,String workspace,String prompt,boolean lookupOnly) {
            creationLookups.add(lookupOnly);return creator.apply(lookupOnly);
        }

        final List<CommandPacket> commands = new CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger submitCalls =
            new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger queryCalls =
            new java.util.concurrent.atomic.AtomicInteger();
        final List<TargetSummary> targets = new CopyOnWriteArrayList<>();
        final List<com.netlivecowork.channel.contract.TargetOwnership> ownerships =
            new CopyOnWriteArrayList<>();
        volatile Function<CommandPacket, CommandOutcome> outcome = this::acceptCommand;
        volatile java.util.function.Function<TrustedCall,CompletionStage<com.netlivecowork.channel.contract.SessionAccess.Catalog>> catalogProvider = call -> CompletableFuture.failedFuture(new UnsupportedOperationException("catalog not configured"));
        @Override public CompletionStage<com.netlivecowork.channel.contract.SessionAccess.Catalog> discoverOwnedSessions(TrustedCall call) { return catalogProvider.apply(call); }
        @Override public CompletionStage<Void> registerCatalogTargets(TrustedCall call,String auth) { return CompletableFuture.completedFuture(null); }
        private final Clock clock;

        RecordingBackend(Clock clock) {
            this.clock = clock;
            targets.add(TargetSummary.of("target-1", "报表助手"));
        }

        private CommandOutcome acceptCommand(CommandPacket command) {
            ReceiptPacket receipt = new ReceiptPacket("1", Ids.next("receipt"), clock.instant(),
                new Correlation(command.correlation().flowId(), command.packetId()), command.context(),
                new Producer(Layer.L3, "l3-backend"),
                new ReceiptPayload.Accepted(ReceiptPayload.AcceptedBy.AGENT));
            return CommandOutcome.accepted("dispatch-1", receipt);
        }

        @Override
        public CompletionStage<CommandOutcome> submitCommand(CommandPacket command, TrustedCall call) {
            submitCalls.incrementAndGet();
            commands.add(command);
            return CompletableFuture.completedFuture(outcome.apply(command));
        }

        Function<String,com.netlivecowork.channel.contract.SessionSummary> sessionProjection = target ->
            new com.netlivecowork.channel.contract.SessionSummary(target,
                targets.stream().filter(t->t.targetRef().equals(target)).map(TargetSummary::label).findFirst().orElse(target),null,true,null,
                new com.netlivecowork.channel.contract.SessionWorkspace("sws-test","测试工作空间"));

        @Override
        public CompletionStage<ReceiptPacket> query(CommandPacket command, TrustedCall call) {
            queryCalls.incrementAndGet();
            commands.add(command);
            OperationResult result = switch (command.type()) {
                case SESSION_LIST -> new OperationResult.SessionList(targets.stream()
                    .map(summary -> new com.netlivecowork.channel.contract.SessionSummary(summary.targetRef(),
                        summary.label(), summary.agentRef(), true)).toList(), null);
                case SESSION_GET -> new OperationResult.SessionGet(
                    sessionProjection.apply(command.context().targetRef()));
                case TASK_GET -> new OperationResult.TaskGet(new com.netlivecowork.channel.contract.TaskSnapshot(
                    command.context().requireTaskRef("query"), command.context().targetRef(),
                    com.netlivecowork.channel.contract.TaskStatus.RUNNING, List.of()));
                default -> new OperationResult.InputSubmit(null);
            };
            return CompletableFuture.completedFuture(new ReceiptPacket("1", Ids.next("receipt"),
                clock.instant(), new Correlation(command.correlation().flowId(), command.packetId()),
                command.context(), new Producer(Layer.L3, "l3-backend"),
                new ReceiptPayload.Completed(result)));
        }

        @Override
        public CompletionStage<OperationLookup> lookupOperation(String operationRef, TrustedCall call) {
            return CompletableFuture.completedFuture(new OperationLookup(operationRef,
                com.netlivecowork.channel.contract.DispatchStatus.OUTCOME_UNKNOWN, null,
                com.netlivecowork.channel.contract.ChannelError.of(ChannelErrorCode.OUTCOME_UNKNOWN,
                    "not looked up in tests")));
        }

        @Override
        public CompletionStage<List<TargetSummary>> listTargets(TrustedCall call, String agentRef,
                                                                PageRequest page) {
            return CompletableFuture.completedFuture(List.copyOf(targets));
        }

        @Override
        public CompletionStage<List<com.netlivecowork.channel.contract.TargetOwnership>> targetOwnerships(
            TrustedCall call) {
            return CompletableFuture.completedFuture(List.copyOf(ownerships));
        }

        @Override
        public CompletionStage<SessionWatch> ensureSessionWatch(SessionWatchRequest request) {
            return CompletableFuture.completedFuture(SessionWatch.unsupported(
                com.netlivecowork.channel.contract.ChannelError.of(ChannelErrorCode.UNSUPPORTED_CAPABILITY,
                    "watches are not supported in tests")));
        }

        @Override
        public CompletionStage<Void> removeSessionWatch(String watchRef) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<FileReadResult> readFile(String fileRef, String deliveryId) {
            return CompletableFuture.failedFuture(new IllegalStateException("files are not supported"));
        }

        @Override
        public CompletionStage<Void> registerInteraction(String interactionRef, String targetRef,
                                                         String backendInteractionId, long revision) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> registerFile(String fileRef, String targetRef, String backendFileId,
                                                  com.netlivecowork.channel.contract.FileMetadata metadata) {
            return CompletableFuture.completedFuture(null);
        }
    }

    static JsonNode emptyObject() {
        return Json.obj();
    }
}
