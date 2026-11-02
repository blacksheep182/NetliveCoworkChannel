package com.netlivecowork.channel.l2;

import static org.junit.jupiter.api.Assertions.*;

import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.json.PacketJson;
import com.netlivecowork.channel.contract.port.BackendAccessPort;
import com.netlivecowork.channel.l2.store.*;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class UnknownOperationRecoveryTest {
    @Test
    void legacyReplyRecoveryPreservesOriginalRecordAndOnlyLooksUp() {
        try(var fixture=new Fixture(true,true)) {
            String original=fixture.test.module.requests().inboxByOperation("op-original").commandJson();
            assertThrows(ChannelContractException.class,()->PacketJson.decCommand(original));
            fixture.result.set(new OperationLookup("op-original",DispatchStatus.AGENT_ACCEPTED,
                fixture.receipt(new ReceiptPayload.Completed(new OperationResult.InteractionRespond("interaction-original"))),null));
            assertEquals(1,fixture.dispatcher.reconcileUnknown(10));
            assertEquals(1,fixture.lookups.get());
            assertEquals(RequestRepository.OperationStatus.COMPLETED,fixture.status());
            assertEquals(original,fixture.test.module.requests().inboxByOperation("op-original").commandJson());
            assertEquals(fixture.command,StoredCommandCodec.decode(original));
        }
    }
    @Test
    void storageCompatibilityRejectsMixedAndRecursivePayloads() {
        try(var fixture=new Fixture(true,true)) {
            var original=fixture.test.module.requests().inboxByOperation("op-original").commandJson();
            var mixed=(com.fasterxml.jackson.databind.node.ObjectNode)Json.read(original);
            ((com.fasterxml.jackson.databind.node.ObjectNode)mixed.get("payload")).put("expectedRevision",99);
            assertThrows(ChannelContractException.class,()->StoredCommandCodec.decode(Json.write(mixed)));
            var recursive=(com.fasterxml.jackson.databind.node.ObjectNode)Json.read(original);
            var wrapper=Json.obj().set("payload",recursive.get("payload"));recursive.set("payload",wrapper);
            assertThrows(ChannelContractException.class,()->StoredCommandCodec.decode(Json.write(recursive)));
        }
    }
    @Test
    void deferredInteractionIsQueriedAndCompletedThroughTheSameReceiptIntakeWithoutResubmission() {
        try(var fixture=new Fixture(true)) {
            assertEquals(0,fixture.dispatcher.reconcileUnknown(10));
            assertEquals(1,fixture.lookups.get());
            fixture.test.clock.advanceMillis(5000);
            fixture.result.set(new OperationLookup("op-original",DispatchStatus.AGENT_ACCEPTED,
                fixture.receipt(new ReceiptPayload.Completed(new OperationResult.InteractionRespond("interaction-original"))),null));
            assertEquals(1,fixture.dispatcher.reconcileUnknown(10));
            assertEquals(RequestRepository.OperationStatus.COMPLETED,fixture.status());
            var row=fixture.test.module.requests().inboxByOperation("op-original");
            assertInstanceOf(ChannelResponse.Completed.class,PacketJson.decChannelResponse(row.responseJson()));
            assertTrue(fixture.test.module.requests().pendingDispatch(10).isEmpty());
        }
    }
    @Test
    void unknownIsThrottledAndNeverSubmittedAgain() {
        try (var fixture = new Fixture()) {
            assertEquals(0, fixture.dispatcher.reconcileUnknown(10));
            assertEquals(0, fixture.dispatcher.reconcileUnknown(10));
            assertEquals(1, fixture.lookups.get());
            fixture.test.clock.advanceMillis(5_000);
            assertEquals(0, fixture.dispatcher.reconcileUnknown(10));
            assertEquals(2, fixture.lookups.get());
            assertEquals(RequestRepository.OperationStatus.UNKNOWN, fixture.status());
            assertTrue(fixture.test.module.requests().pendingDispatch(10).isEmpty());
        }
    }

    @Test
    void originalAcceptedReceiptResolvesWithoutSubmitting() {
        try (var fixture = new Fixture()) {
            fixture.result.set(new OperationLookup("op-original", DispatchStatus.AGENT_ACCEPTED,
                fixture.receipt(new ReceiptPayload.Accepted(ReceiptPayload.AcceptedBy.AGENT)), null));
            assertEquals(1, fixture.dispatcher.reconcileUnknown(10));
            assertEquals(RequestRepository.OperationStatus.DISPATCHED, fixture.status());
            assertEquals("original-actor", fixture.principal.get());
            assertEquals(0, fixture.dispatcher.reconcileUnknown(10));
            assertEquals(1, fixture.lookups.get());
        }
    }

    @Test
    void originalRejectedReceiptTerminatesWithoutSubmitting() {
        try (var fixture = new Fixture()) {
            fixture.result.set(new OperationLookup("op-original", DispatchStatus.NOT_SENT,
                fixture.receipt(new ReceiptPayload.Rejected(ChannelErrorCode.UNAVAILABLE.error("busy"))), null));
            assertEquals(1, fixture.dispatcher.reconcileUnknown(10));
            assertEquals(RequestRepository.OperationStatus.REJECTED, fixture.status());
            assertTrue(fixture.test.module.requests().pendingDispatch(10).isEmpty());
        }
    }

    @Test
    void missingEvidenceOrDifferentOperationCannotAuthorizeResubmission() {
        try (var fixture = new Fixture()) {
            for (var result : List.of(
                new OperationLookup("op-original", DispatchStatus.NOT_SENT, null, null),
                new OperationLookup("op-original", DispatchStatus.AGENT_ACCEPTED, null, null),
                new OperationLookup("another-operation", DispatchStatus.AGENT_ACCEPTED,
                    fixture.receipt(new ReceiptPayload.Accepted(ReceiptPayload.AcceptedBy.AGENT)), null))) {
                fixture.result.set(result);
                assertEquals(0, fixture.dispatcher.reconcileUnknown(10));
                fixture.test.clock.advanceMillis(5_000);
                assertEquals(RequestRepository.OperationStatus.UNKNOWN, fixture.status());
                assertTrue(fixture.test.module.requests().pendingDispatch(10).isEmpty());
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final TestModule test = new TestModule(false);
        final AtomicInteger lookups = new AtomicInteger();
        final AtomicReference<String> principal = new AtomicReference<>();
        final AtomicReference<OperationLookup> result = new AtomicReference<>(
            new OperationLookup("op-original", DispatchStatus.OUTCOME_UNKNOWN, null, null));
        final CommandPacket command;
        final BackendDispatcher dispatcher;

        Fixture() {this(false);}
        Fixture(boolean interaction) {this(interaction,false);}
        Fixture(boolean interaction,boolean legacy) {
            test.createEntry();
            command = new CommandPacket("1", "cmd-original", interaction?CommandType.INTERACTION_RESPOND:CommandType.INPUT_SUBMIT, test.clock.instant(),
                Correlation.of("flow-original"),
                new PacketContext(TestModule.TENANT, TestModule.WORKSPACE, "target-1", "op-original", null),
                new Producer(Layer.L2, "test"), Actor.of("original-actor"),
                interaction?new CommandPayload.InteractionRespond(new InteractionRespondPayload("interaction-original",10,InteractionResponse.Approve.plain())):new CommandPayload.InputSubmit(new ChannelMessage("message-original",
                    List.of(new ContentPart.Text("p1", "original input")), null),
                    ChannelOperation.Interpretation.LITERAL));
            var stored=PacketJson.enc(command);
            if(legacy)stored.set("payload",Json.obj().set("payload",stored.get("payload")));
            test.module.requests().saveInbox(new RequestRepository.InboxRow("dedupe-original", TestModule.TENANT,
                TestModule.WORKSPACE, TestModule.ENTRY, 1, "original-actor", "request-original", "digest-original",
                command.packetId(), "op-original", "flow-original", command.type().name(),
                Json.write(stored), RequestRepository.OperationStatus.UNKNOWN, null, 1, null,
                test.clock.instant(), "USER", "alice", 1));
            var backend = (BackendAccessPort) Proxy.newProxyInstance(BackendAccessPort.class.getClassLoader(),
                new Class<?>[]{BackendAccessPort.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("lookupOperation"))
                        throw new AssertionError("Recovery must only query, called " + method.getName());
                    assertEquals("op-original", args[0]);
                    principal.set(((TrustedCall) args[1]).principalRef());
                    lookups.incrementAndGet();
                    return CompletableFuture.completedFuture(result.get());
                });
            dispatcher = new BackendDispatcher(TestModule.settings(), test.clock,
                new EntryRepository(test.db, test.clock), new DirectoryRepository(test.db, test.clock),
                test.module.requests(), backend, test.module.grants());
            dispatcher.bindInteractionIntake(test.module.intake());
        }

        ReceiptPacket receipt(ReceiptPayload payload) {
            return new ReceiptPacket("1", "receipt-original", test.clock.instant(),
                new Correlation("flow-original", command.packetId()), command.context(),
                new Producer(Layer.L3, "test"), payload);
        }

        RequestRepository.OperationStatus status() {
            return test.module.requests().inboxByOperation("op-original").status();
        }

        @Override public void close() { dispatcher.shutdown(); test.module.close(); test.db.close(); }
    }
}
