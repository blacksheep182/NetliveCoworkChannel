package com.netlivecowork.channel.l2;

import static org.junit.jupiter.api.Assertions.*;
import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.grant.GrantManagement.*;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.l2.store.RequestRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Review-only probes: this file exists in the isolated source copy only. */
class GrantReviewProbeTest {
    private static final TrustedManagementContext OWNER = new TrustedManagementContext(
        TestModule.TENANT, TestModule.WORKSPACE, "owner-1", "auth-1");

    private TestModule setup(boolean owner) {
        TestModule t = new TestModule(false);
        t.createEntry();
        t.module.management().syncTargets();
        if (owner) t.verifiedOwner("owner-1");
        return t;
    }
    private GrantTerms terms(Instant now) {
        return new GrantTerms(List.of(new TargetRead(), new InputSubmit()), now, now.plusSeconds(3600));
    }
    private CreateGrantRequest request(TestModule t, String grantee, long epoch) {
        return new CreateGrantRequest("create-1", new GrantSpec(terms(t.clock.instant()), grantee,
            new GrantResource("target-1", epoch, null)), "share");
    }
    private GrantView create(TestModule t, String grantee) {
        return assertInstanceOf(GrantMutationReply.Committed.class,
            t.module.grants().createGrant(OWNER, request(t, grantee, 1)).toCompletableFuture().join())
            .result().grant();
    }
    private IngressResolution resolve(TestModule t) {
        return t.module.ingress(TestModule.ENTRY).resolve(new VerifiedIngress("resolve-1", "acct-1",
            new VerifiedIngress.Subject(VerifiedIngress.SubjectKind.USER, "alice"),
            new VerifiedIngress.ExternalScope("weixin", "alice"),
            new VerifiedIngress.ExternalReplyTarget(VerifiedIngress.Audience.PERSONAL, "alice",
                Json.obj().put("conversation", "alice")), "resolve-1", null)).toCompletableFuture().join();
    }
    private ChannelResponse submit(TestModule t, IngressResolution r, String id, ChannelOperation op) {
        return t.module.ingress(TestModule.ENTRY).submit(r.admissionRef(),
            new IngressRequest("1", id, r.identity(), r.routing(), op)).toCompletableFuture().join();
    }
    private ChannelOperation input() {
        return new ChannelOperation.InputSubmit("target-1", ChannelOperation.Interpretation.LITERAL,
            List.of(new ContentPart.Text("p1", "review probe")), null);
    }

    @Test void missingOwnerMustNotAllowSubmit() {
        TestModule t = setup(false);
        assertInstanceOf(ChannelResponse.Rejected.class, submit(t, resolve(t), "input-1", input()));
    }

    @Test void noGrantMustNotAllowCancellingAnotherTask() {
        TestModule t = setup(true);
        var task = t.module.requests().ensureTask(TestModule.TENANT, TestModule.WORKSPACE,
            "target-1", "flow-victim", "op-victim", "backend-task-victim");
        assertInstanceOf(ChannelResponse.Rejected.class, submit(t, resolve(t), "cancel-1",
            new ChannelOperation.TaskCancel(task.taskRef(), "review probe")));
    }

    @Test void grantForDifferentResourceEpochMustNotAuthorizeCurrentTarget() {
        TestModule t = setup(true);
        t.backend.ownerships.add(new TargetOwnership("target-1", "owner-1", "proof-1", 1L, 1L,
            t.clock.instant().plusSeconds(60)));
        t.module.management().syncTargets();
        t.module.grants().createGrant(OWNER, request(t, "bob", 999)).toCompletableFuture().join();
        assertFalse(t.module.grants().canAct(TestModule.TENANT, TestModule.WORKSPACE,
            "bob", "target-1", "input.submit"));
    }

    @Test void ownershipTransferMustInvalidateOldGrant() {
        TestModule t = setup(true);
        create(t, "bob");
        t.module.directory().setTargetOwner("target-1", "new-owner");
        assertFalse(t.module.grants().canAct(TestModule.TENANT, TestModule.WORKSPACE,
            "bob", "target-1", "input.submit"));
    }

    @Test void createReplayMustReturnOriginalSnapshot() {
        TestModule t = setup(true);
        CreateGrantRequest req = request(t, "bob", 1);
        GrantView original = create(t, "bob");
        var narrowed = new GrantTerms(List.of(new TargetRead()), t.clock.instant(),
            t.clock.instant().plusSeconds(3600));
        assertInstanceOf(GrantMutationReply.Committed.class, t.module.grants().replaceGrant(OWNER,
            new ReplaceGrantRequest("replace-1", original.grantRef(), 1, narrowed, "narrow"))
            .toCompletableFuture().join());
        GrantView replay = assertInstanceOf(GrantMutationReply.Committed.class,
            t.module.grants().createGrant(OWNER, req).toCompletableFuture().join()).result().grant();
        assertEquals(original, replay);
    }

    @Test void formerOwnerMustNotReadGrantThroughReplay() {
        TestModule t = setup(true);
        CreateGrantRequest req = request(t, "bob", 1);
        create(t, "bob");
        t.module.directory().setTargetOwner("target-1", "new-owner");
        assertInstanceOf(GrantMutationReply.Rejected.class,
            t.module.grants().createGrant(OWNER, req).toCompletableFuture().join());
    }

    @Test void expiredGrantMustNotBeReactivatedByReplace() {
        TestModule t = setup(true);
        GrantView grant = create(t, "bob");
        t.clock.advanceMillis(3_601_000);
        assertInstanceOf(GrantMutationReply.Rejected.class, t.module.grants().replaceGrant(OWNER,
            new ReplaceGrantRequest("replace-1", grant.grantRef(), 1, terms(t.clock.instant()), "extend"))
            .toCompletableFuture().join());
    }

    @Test void managementContextMustMatchResourceDomain() {
        TestModule t = setup(true);
        var outsider = new TrustedManagementContext("other-tenant", "other-workspace", "owner-1", "auth-2");
        assertInstanceOf(GrantMutationReply.Rejected.class,
            t.module.grants().createGrant(outsider, request(t, "bob", 1)).toCompletableFuture().join());
    }

    @Test void revokeMustStopDefinitelyUnsentRetry() throws Exception {
        TestModule t = setup(true);
        IngressResolution r = resolve(t);
        GrantView grant = create(t, r.identity().callerRef());
        t.backend.outcome = packet -> CommandOutcome.notSent(null,
            ChannelError.of(ChannelErrorCode.UNAVAILABLE, "review: definitely unsent"));
        var accepted = assertInstanceOf(ChannelResponse.Accepted.class, submit(t, r, "input-1", input()));
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            var row = t.module.requests().inboxByOperation(accepted.operationRef());
            if (row.dispatchAttempts() > 0 && row.status() == RequestRepository.OperationStatus.PENDING_DISPATCH)
                break;
            Thread.sleep(5);
        }
        var pending = t.module.requests().inboxByOperation(accepted.operationRef());
        assertEquals(RequestRepository.OperationStatus.PENDING_DISPATCH, pending.status());
        assertEquals(1, t.backend.submitCalls.get());
        assertInstanceOf(GrantMutationReply.Committed.class, t.module.grants().revokeGrant(OWNER,
            new RevokeGrantRequest("revoke-1", grant.grantRef(), 1, "withdraw"))
            .toCompletableFuture().join());
        t.clock.advanceMillis(60_000);
        t.module.tick();
        assertEquals(1, t.backend.submitCalls.get(), "revocation must prevent the second backend attempt");
    }

    @Test void replaceAndRequestRecordMustCommitAtomically() {
        TestModule t = setup(true);
        GrantView grant = create(t, "bob");
        t.db.update("CREATE TRIGGER review_fail_request BEFORE INSERT ON l2_grant_requests "
            + "WHEN NEW.operation='replace' BEGIN SELECT RAISE(ABORT, 'injected storage failure'); END");
        var narrowed = new GrantTerms(List.of(new TargetRead()), t.clock.instant(),
            t.clock.instant().plusSeconds(3600));
        t.module.grants().replaceGrant(OWNER,
            new ReplaceGrantRequest("replace-1", grant.grantRef(), 1, narrowed, "narrow"))
            .toCompletableFuture().join();
        assertEquals(1, t.module.grants().getGrant(OWNER, grant.grantRef()).toCompletableFuture().join().revision(),
            "failed idempotency insert must roll back the grant mutation too");
    }

    private void captureInput(TestModule t) {
        t.module.grants().capture(new CommandPacket("1", "cmd-probe", CommandType.INPUT_SUBMIT,
            t.clock.instant(), Correlation.of("flow-probe"),
            new PacketContext(TestModule.TENANT, TestModule.WORKSPACE, "target-1", "op-probe", null),
            new Producer(Layer.L2, "review-test"), Actor.of("bob"),
            new CommandPayload.InputSubmit(new ChannelMessage("msg-probe",
                List.of(new ContentPart.Text("p1", "probe")), null), ChannelOperation.Interpretation.LITERAL)));
    }

    @Test void removedThenRestoredPermissionDoesNotReviveOriginalWork() {
        TestModule t = setup(true);
        GrantView grant = create(t, "bob");
        captureInput(t);
        assertTrue(t.module.grants().current("op-probe"));
        GrantTerms original = terms(t.clock.instant());
        var narrow = new GrantTerms(List.of(new TargetRead()), original.validFrom(), original.validUntil());
        assertInstanceOf(GrantMutationReply.Committed.class, t.module.grants().replaceGrant(OWNER,
            new ReplaceGrantRequest("narrow", grant.grantRef(), 1, narrow, "remove submit")).toCompletableFuture().join());
        assertInstanceOf(GrantMutationReply.Committed.class, t.module.grants().replaceGrant(OWNER,
            new ReplaceGrantRequest("restore", grant.grantRef(), 2, original, "new permission")).toCompletableFuture().join());
        assertTrue(t.module.grants().canAct(TestModule.TENANT, TestModule.WORKSPACE, "bob", "target-1", "input.submit"));
        assertFalse(t.module.grants().current("op-probe"));
    }

    @Test void extensionDoesNotExtendOriginalWorkDeadline() {
        TestModule t = setup(true);
        GrantView grant = create(t, "bob");
        captureInput(t);
        var longer = new GrantTerms(grant.spec().terms().permissions(), t.clock.instant(), t.clock.instant().plusSeconds(7200));
        assertInstanceOf(GrantMutationReply.Committed.class, t.module.grants().replaceGrant(OWNER,
            new ReplaceGrantRequest("extend", grant.grantRef(), 1, longer, "extend")).toCompletableFuture().join());
        assertTrue(t.module.grants().current("op-probe"));
        t.clock.advanceMillis(3601000);
        assertTrue(t.module.grants().canAct(TestModule.TENANT, TestModule.WORKSPACE, "bob", "target-1", "input.submit"));
        assertFalse(t.module.grants().current("op-probe"));
    }

    @Test void createCanReplayAfterItsTermsExpire() {
        TestModule t = setup(true);
        CreateGrantRequest request = request(t, "bob", 1);
        GrantMutationReply original = t.module.grants().createGrant(OWNER, request).toCompletableFuture().join();
        t.clock.advanceMillis(3601000);
        assertEquals(original, t.module.grants().createGrant(OWNER, request).toCompletableFuture().join());
    }

    @Test void movingValidityStartDoesNotTemporarilyPauseThenReviveOldWork() {
        TestModule t = setup(true);
        GrantView grant = create(t, "bob");
        captureInput(t);
        var later = new GrantTerms(grant.spec().terms().permissions(), t.clock.instant().plusSeconds(60),
            t.clock.instant().plusSeconds(3600));
        assertInstanceOf(GrantMutationReply.Committed.class, t.module.grants().replaceGrant(OWNER,
            new ReplaceGrantRequest("later", grant.grantRef(), 1, later, "narrow start")).toCompletableFuture().join());
        assertFalse(t.module.grants().current("op-probe"));
        t.clock.advanceMillis(120000);
        assertTrue(t.module.grants().canAct(TestModule.TENANT, TestModule.WORKSPACE, "bob", "target-1", "input.submit"));
        assertFalse(t.module.grants().current("op-probe"));
    }

    @Test void identicalConcurrentReplaceReturnsSameCommittedResult() {
        TestModule t = setup(true);
        GrantView grant = create(t, "bob");
        var request = new ReplaceGrantRequest("replace", grant.grantRef(), 1,
            new GrantTerms(List.of(new TargetRead()), t.clock.instant(), t.clock.instant().plusSeconds(3600)), "narrow");
        var first = t.module.grants().replaceGrant(OWNER, request).toCompletableFuture();
        var second = t.module.grants().replaceGrant(OWNER, request).toCompletableFuture();
        assertInstanceOf(GrantMutationReply.Committed.class, first.join());
        assertEquals(first.join(), second.join());
    }
}
