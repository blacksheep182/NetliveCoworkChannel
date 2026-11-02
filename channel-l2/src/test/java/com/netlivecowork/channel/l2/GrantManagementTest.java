package com.netlivecowork.channel.l2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.ChannelOperation;
import com.netlivecowork.channel.contract.ChannelResponse;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.IngressRequest;
import com.netlivecowork.channel.contract.IngressResolution;
import com.netlivecowork.channel.contract.VerifiedIngress;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.grant.GrantManagement;
import com.netlivecowork.channel.contract.grant.GrantManagement.CreateGrantRequest;
import com.netlivecowork.channel.contract.grant.GrantManagement.GrantMutationReply;
import com.netlivecowork.channel.contract.grant.GrantManagement.GrantResource;
import com.netlivecowork.channel.contract.grant.GrantManagement.GrantSpec;
import com.netlivecowork.channel.contract.grant.GrantManagement.GrantTerms;
import com.netlivecowork.channel.contract.grant.GrantManagement.GrantView;
import com.netlivecowork.channel.contract.grant.GrantManagement.ReplaceGrantRequest;
import com.netlivecowork.channel.contract.grant.GrantManagement.RevokeGrantRequest;
import com.netlivecowork.channel.contract.grant.GrantManagement.TrustedManagementContext;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** v2.11 个人 Grant 管理：所有者限定、幂等、CAS、unchanged 与撤销语义。 */
class GrantManagementTest {

    private static final TrustedManagementContext OWNER = new TrustedManagementContext(TestModule.TENANT,
        TestModule.WORKSPACE, "owner-1", "auth-1");

    private GrantSpec spec(String grantee) {
        Instant now = Instant.parse("2026-10-27T00:00:00Z");
        return new GrantSpec(new GrantTerms(List.of(new GrantManagement.TargetRead(),
            new GrantManagement.InputSubmit()), now, now.plusSeconds(86_400)),
            grantee, new GrantResource("target-1", 1L, null));
    }

    private TestModule moduleWithOwner() {
        TestModule test = new TestModule(false);
        test.createEntry();
        test.module.management().syncTargets();
        test.verifiedOwner("owner-1");
        return test;
    }

    @Test
    void activeGrantAllowsItsActionAndRevocationStopsIt() {
        TestModule test = moduleWithOwner();
        assertFalse(test.module.grants().canAct(TestModule.TENANT, TestModule.WORKSPACE, "bob",
            "target-1", "target.read"), "no grant yet");
        assertTrue(test.module.grants().canAct(TestModule.TENANT, TestModule.WORKSPACE, "owner-1",
            "target-1", "input.submit"), "owner acts without a grant");

        GrantView view = assertInstanceOf(GrantMutationReply.Committed.class, test.module.grants()
            .createGrant(OWNER, new CreateGrantRequest("req-1", spec("bob"), "share"))
            .toCompletableFuture().join()).result().grant();

        assertTrue(test.module.grants().canAct(TestModule.TENANT, TestModule.WORKSPACE, "bob",
            "target-1", "target.read"));
        assertTrue(test.module.grants().canAct(TestModule.TENANT, TestModule.WORKSPACE, "bob",
            "target-1", "input.submit"));
        assertFalse(test.module.grants().canAct(TestModule.TENANT, TestModule.WORKSPACE, "bob",
            "target-1", "task.cancel"), "actions outside the grant must stay denied");

        test.module.grants().revokeGrant(OWNER, new RevokeGrantRequest("req-2", view.grantRef(), 1L, "leave"))
            .toCompletableFuture().join();
        assertFalse(test.module.grants().canAct(TestModule.TENANT, TestModule.WORKSPACE, "bob",
            "target-1", "target.read"), "revoked grant must not authorize");
    }

    @Test
    void concurrentIdenticalCreateProducesOneGrant() throws Exception {
        TestModule test = moduleWithOwner();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);
        java.util.List<GrantMutationReply> replies = new java.util.concurrent.CopyOnWriteArrayList<>();
        Runnable create = () -> {
            try {
                start.await();
                replies.add(test.module.grants().createGrant(OWNER,
                    new CreateGrantRequest("req-1", spec("bob"), "share")).toCompletableFuture().join());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        };
        Thread first = new Thread(create, "grant-1");
        Thread second = new Thread(create, "grant-2");
        first.start();
        second.start();
        start.countDown();
        done.await();

        java.util.Set<String> refs = new java.util.HashSet<>();
        for (GrantMutationReply reply : replies) {
            refs.add(assertInstanceOf(GrantMutationReply.Committed.class, reply).result().grant().grantRef());
        }
        assertEquals(1, refs.size(), "concurrent identical requests must converge on one grant");
    }

    @Test
    void replaceAndRevokeOnTheSameRevisionHaveOneWinner() throws Exception {
        TestModule test = moduleWithOwner();
        GrantView view = assertInstanceOf(GrantMutationReply.Committed.class, test.module.grants()
            .createGrant(OWNER, new CreateGrantRequest("req-1", spec("bob"), "share"))
            .toCompletableFuture().join()).result().grant();
        Instant now = Instant.parse("2026-10-27T00:00:00Z");
        GrantTerms replacement = new GrantTerms(List.of(new GrantManagement.TargetRead()), now,
            now.plusSeconds(86_400));

        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);
        java.util.List<GrantMutationReply> replies = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread replace = new Thread(() -> {
            try {
                start.await();
                replies.add(test.module.grants().replaceGrant(OWNER, new ReplaceGrantRequest("req-r",
                    view.grantRef(), 1L, replacement, "narrow")).toCompletableFuture().join());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        Thread revoke = new Thread(() -> {
            try {
                start.await();
                replies.add(test.module.grants().revokeGrant(OWNER, new RevokeGrantRequest("req-v",
                    view.grantRef(), 1L, "leave")).toCompletableFuture().join());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        replace.start();
        revoke.start();
        start.countDown();
        done.await();

        long committed = replies.stream().filter(r -> r instanceof GrantMutationReply.Committed).count();
        long conflicts = replies.stream().filter(r -> r instanceof GrantMutationReply.Rejected rejected
            && (ChannelErrorCode.GRANT_REVISION_CONFLICT.name().equals(rejected.error().code())
                || ChannelErrorCode.GRANT_STATE_CONFLICT.name().equals(rejected.error().code()))).count();
        assertEquals(1, committed, "exactly one mutation may win the revision");
        assertEquals(1, conflicts, "the loser must see a revision/state conflict");
        assertEquals(2, test.module.grants().getGrant(OWNER, view.grantRef())
            .toCompletableFuture().join().revision());
    }

    @Test
    void expiredGrantDoesNotAuthorizeAndGrantViewIsOwnerOnly() {
        TestModule test = moduleWithOwner();
        Instant now = Instant.parse("2026-10-27T00:00:00Z");
        // 直接写入一条已过期规则，验证执行期不看 recordState，而看期限
        test.module.grants().createGrant(OWNER, new CreateGrantRequest("req-1",
            new GrantSpec(new GrantTerms(List.of(new GrantManagement.TargetRead()), now,
                now.plusSeconds(60)), "bob", new GrantResource("target-1", 1L, null)), "share"))
            .toCompletableFuture().join();
        test.clock.advanceMillis(120_000);
        assertFalse(test.module.grants().canAct(TestModule.TENANT, TestModule.WORKSPACE, "bob",
            "target-1", "target.read"), "an expired grant must not authorize");

        TrustedManagementContext stranger = new TrustedManagementContext(TestModule.TENANT,
            TestModule.WORKSPACE, "mallory", "auth-2");
        assertThrows(java.util.concurrent.CompletionException.class,
            () -> test.module.grants().getGrant(stranger, "grant-unknown")
                .toCompletableFuture().join());
    }

    @Test
    void replacementCannotBypassRequiredChecks() {
        TestModule test = moduleWithOwner();
        GrantView view = assertInstanceOf(GrantMutationReply.Committed.class, test.module.grants()
            .createGrant(OWNER, new CreateGrantRequest("req-1", spec("bob"), "share"))
            .toCompletableFuture().join()).result().grant();
        Instant now = Instant.parse("2026-10-27T00:00:00Z");
        // 截止时间已过期的新规则：拒绝，不产生变更
        GrantMutationReply expired = test.module.grants().replaceGrant(OWNER,
            new ReplaceGrantRequest("req-2", view.grantRef(), 1L,
                new GrantTerms(List.of(new GrantManagement.TargetRead()), now.minusSeconds(3600),
                    now.minusSeconds(60)), "extend")).toCompletableFuture().join();
        assertEquals(ChannelErrorCode.INVALID_ARGUMENT.name(),
            assertInstanceOf(GrantMutationReply.Rejected.class, expired).error().code());
        assertEquals(1, test.module.grants().getGrant(OWNER, view.grantRef())
            .toCompletableFuture().join().revision());
    }

    @Test
    void legacyOwnershipWithoutValidityDoesNotAuthorize() {
        TestModule test = new TestModule(false);
        test.createEntry();
        test.backend.ownerships.add(new com.netlivecowork.channel.contract.TargetOwnership("target-1",
            "owner-1", "l3-registration", 1L, 1L, null));

        test.module.management().syncTargets();

        org.junit.jupiter.api.Assertions.assertNull(test.module.directory().targetOwner("target-1"));
    }

    @Test
    void verifiedOwnershipFactGrantsOwnerAuthority() {
        TestModule test = new TestModule(false);
        test.createEntry();
        test.backend.ownerships.add(new com.netlivecowork.channel.contract.TargetOwnership("target-1",
            "owner-1", "l3/facts/1", 1L, 1L, test.clock.instant().plusSeconds(60)));

        test.module.management().syncTargets();

        assertEquals("owner-1", test.module.directory().targetOwner("target-1"));
        assertTrue(test.module.grants().canAct(TestModule.TENANT, TestModule.WORKSPACE, "owner-1",
            "target-1", "input.submit"), "verified owner acts on fresh facts");
    }

    @Test
    void expiredOwnershipFactDoesNotAuthorize() {
        TestModule test = new TestModule(false);
        test.createEntry();
        test.backend.ownerships.add(new com.netlivecowork.channel.contract.TargetOwnership("target-1",
            "owner-1", "l3/facts/1", 1L, 1L, test.clock.instant().minusSeconds(1)));

        test.module.management().syncTargets();

        org.junit.jupiter.api.Assertions.assertNull(test.module.directory().targetOwner("target-1"));
        assertFalse(test.module.grants().canAct(TestModule.TENANT, TestModule.WORKSPACE, "owner-1",
            "target-1", "input.submit"), "expired evidence never grants authority");
    }

    @Test
    void ownershipRefreshExtendsValidityWithoutInvalidatingGrants() {
        TestModule test = new TestModule(false);
        test.createEntry();
        test.backend.ownerships.add(new com.netlivecowork.channel.contract.TargetOwnership("target-1",
            "owner-1", "l3/facts/1", 1L, 1L, test.clock.instant().plusSeconds(60)));
        test.module.management().syncTargets();
        // 同一所有者先具备委派依据并授权，再验证纯刷新不误撤（S119）。
        test.verifiedOwner("owner-1");
        assertInstanceOf(GrantMutationReply.Committed.class, test.module.grants()
            .createGrant(OWNER, new CreateGrantRequest("create-1", spec("bob"), "share"))
                .toCompletableFuture().join());
        assertTrue(test.module.grants().canAct(TestModule.TENANT, TestModule.WORKSPACE, "bob",
            "target-1", "target.read"));

        test.backend.ownerships.clear();
        test.backend.ownerships.add(new com.netlivecowork.channel.contract.TargetOwnership("target-1",
            "owner-1", "l3/facts/1", 1L, 1L, test.clock.instant().plusSeconds(700_000)));
        test.module.management().syncTargets();

        assertTrue(test.module.grants().canAct(TestModule.TENANT, TestModule.WORKSPACE, "bob",
            "target-1", "target.read"), "pure validity refresh must not revoke grants");
        assertTrue(test.module.directory().ownershipEvidence("target-1").evidenceValidUntil()
            .isAfter(test.clock.instant().plusSeconds(600_000)), "refresh extends the evidence window");
    }

    @Test
    void ownershipRevisionAdvanceInvalidatesOldGrants() {
        TestModule test = new TestModule(false);
        test.createEntry();
        test.backend.ownerships.add(new com.netlivecowork.channel.contract.TargetOwnership("target-1",
            "owner-1", "l3/facts/1", 1L, 1L, test.clock.instant().plusSeconds(60)));
        test.module.management().syncTargets();
        test.verifiedOwner("owner-1");
        assertInstanceOf(GrantMutationReply.Committed.class, test.module.grants()
            .createGrant(OWNER, new CreateGrantRequest("create-1", spec("bob"), "share"))
                .toCompletableFuture().join());
        assertTrue(test.module.grants().canAct(TestModule.TENANT, TestModule.WORKSPACE, "bob",
            "target-1", "target.read"));

        // owner A→B→A 也会产生新版本：相同 owner 字符串不能复活旧依据（S119）。
        test.backend.ownerships.clear();
        test.backend.ownerships.add(new com.netlivecowork.channel.contract.TargetOwnership("target-1",
            "owner-1", "l3/facts/2", 1L, 2L, test.clock.instant().plusSeconds(60)));
        test.module.management().syncTargets();

        assertFalse(test.module.grants().canAct(TestModule.TENANT, TestModule.WORKSPACE, "bob",
            "target-1", "target.read"), "ownership revision advance must invalidate grants");
    }

    @Test
    void staleOwnershipFactMustNotRollBackCurrentEvidence() {
        TestModule test = new TestModule(false);
        test.createEntry();
        test.backend.ownerships.add(new com.netlivecowork.channel.contract.TargetOwnership("target-1",
            "owner-1", "l3/facts/2", 1L, 2L, test.clock.instant().plusSeconds(120)));
        test.module.management().syncTargets();

        test.backend.ownerships.clear();
        test.backend.ownerships.add(new com.netlivecowork.channel.contract.TargetOwnership("target-1",
            "owner-1", "l3/facts/1", 1L, 1L, test.clock.instant().plusSeconds(30)));
        test.module.management().syncTargets();

        var evidence = test.module.directory().ownershipEvidence("target-1");
        assertEquals(2L, evidence.evidenceRevision(), "older facts must not roll back revision");
        assertTrue(evidence.evidenceValidUntil().isAfter(test.clock.instant().plusSeconds(60)),
            "older facts must not shorten validity");
    }

    @Test
    void grantsGateSubmitForNonOwnerCallers() {
        TestModule test = moduleWithOwner();
        IngressResolution resolution = resolve(test);
        String caller = resolution.identity().callerRef();

        // 非所有者、无 Grant：拒绝
        ChannelResponse denied = submit(test, resolution, "req-deny");
        assertEquals(ChannelErrorCode.PERMISSION_DENIED.name(),
            assertInstanceOf(ChannelResponse.Rejected.class, denied).error().code());

        // 授予 input.submit 后：放行
        Instant now = Instant.parse("2026-10-27T00:00:00Z");
        test.module.grants().createGrant(OWNER, new CreateGrantRequest("req-grant",
            new GrantSpec(new GrantTerms(List.of(new GrantManagement.TargetRead(), new GrantManagement.InputSubmit()), now,
                now.plusSeconds(86_400)), caller, new GrantResource("target-1", 1L, null)), "allow"))
            .toCompletableFuture().join();
        assertInstanceOf(ChannelResponse.Accepted.class, submit(test, resolution, "req-allow"));
    }

    private IngressResolution resolve(TestModule test) {
        VerifiedIngress input = new VerifiedIngress("req-resolve", "acct-1",
            new VerifiedIngress.Subject(VerifiedIngress.SubjectKind.USER, "alice"),
            new VerifiedIngress.ExternalScope("weixin", "alice"),
            new VerifiedIngress.ExternalReplyTarget(VerifiedIngress.Audience.PERSONAL, "alice",
                Json.obj().put("conversation", "alice")),
            "req-resolve", null);
        return test.module.ingress(TestModule.ENTRY).resolve(input).toCompletableFuture().join();
    }

    private ChannelResponse submit(TestModule test, IngressResolution resolution, String requestId) {
        return test.module.ingress(TestModule.ENTRY).submit(resolution.admissionRef(),
            new IngressRequest("1", requestId, resolution.identity(), resolution.routing(),
                new ChannelOperation.InputSubmit("target-1", ChannelOperation.Interpretation.LITERAL,
                    List.of(new ContentPart.Text("p1", "你好")), null))).toCompletableFuture().join();
    }

    @Test
    void onlyVerifiedOwnerMayCreateAndRequestsAreIdempotent() {
        TestModule test = moduleWithOwner();
        GrantMutationReply created = test.module.grants().createGrant(OWNER,
            new CreateGrantRequest("req-1", spec("bob"), "share")).toCompletableFuture().join();
        GrantMutationReply.Committed committed = assertInstanceOf(GrantMutationReply.Committed.class, created);
        assertEquals("created", committed.result().outcome());
        assertEquals(1, committed.result().grant().revision());

        // 同键同内容：复用原结果，不产生第二个 Grant
        GrantMutationReply replay = test.module.grants().createGrant(OWNER,
            new CreateGrantRequest("req-1", spec("bob"), "share")).toCompletableFuture().join();
        assertEquals(committed.result().grant().grantRef(),
            assertInstanceOf(GrantMutationReply.Committed.class, replay).result().grant().grantRef());
        // 同键异内容：冲突
        GrantMutationReply conflict = test.module.grants().createGrant(OWNER,
            new CreateGrantRequest("req-1", spec("carol"), "share")).toCompletableFuture().join();
        assertEquals(ChannelErrorCode.REQUEST_CONFLICT.name(),
            assertInstanceOf(GrantMutationReply.Rejected.class, conflict).error().code());

        // 非所有者（同空间普通用户）不得创建
        TrustedManagementContext stranger = new TrustedManagementContext(TestModule.TENANT,
            TestModule.WORKSPACE, "mallory", "auth-2");
        GrantMutationReply denied = test.module.grants().createGrant(stranger,
            new CreateGrantRequest("req-2", spec("bob"), "share")).toCompletableFuture().join();
        assertEquals(ChannelErrorCode.PERMISSION_DENIED.name(),
            assertInstanceOf(GrantMutationReply.Rejected.class, denied).error().code());
    }

    @Test
    void replaceUsesCasAndSameContentIsUnchanged() {
        TestModule test = moduleWithOwner();
        GrantView view = assertInstanceOf(GrantMutationReply.Committed.class, test.module.grants()
            .createGrant(OWNER, new CreateGrantRequest("req-1", spec("bob"), "share"))
            .toCompletableFuture().join()).result().grant();
        Instant now = Instant.parse("2026-10-27T00:00:00Z");
        GrantTerms replacement = new GrantTerms(List.of(new GrantManagement.TargetRead()), now,
            now.plusSeconds(172_800));

        GrantView replaced = assertInstanceOf(GrantMutationReply.Committed.class, test.module.grants()
            .replaceGrant(OWNER, new ReplaceGrantRequest("req-2", view.grantRef(), view.revision(),
                replacement, "narrow"))
            .toCompletableFuture().join()).result().grant();
        assertEquals(2, replaced.revision());

        // 版本匹配且内容相同：unchanged，版本不变
        GrantView unchanged = assertInstanceOf(GrantMutationReply.Committed.class, test.module.grants()
            .replaceGrant(OWNER, new ReplaceGrantRequest("req-3", view.grantRef(), replaced.revision(),
                replacement, "narrow"))
            .toCompletableFuture().join()).result().grant();
        assertEquals(2, unchanged.revision());
        // 过期版本：冲突并带 currentRevision
        GrantMutationReply stale = test.module.grants().replaceGrant(OWNER,
            new ReplaceGrantRequest("req-4", view.grantRef(), 1L, replacement, "narrow"))
            .toCompletableFuture().join();
        assertEquals(ChannelErrorCode.GRANT_REVISION_CONFLICT.name(),
            assertInstanceOf(GrantMutationReply.Rejected.class, stale).error().code());
    }

    @Test
    void revokeIsIdempotentAndBlocksFurtherReplace() {
        TestModule test = moduleWithOwner();
        GrantView view = assertInstanceOf(GrantMutationReply.Committed.class, test.module.grants()
            .createGrant(OWNER, new CreateGrantRequest("req-1", spec("bob"), "share"))
            .toCompletableFuture().join()).result().grant();

        GrantView revoked = assertInstanceOf(GrantMutationReply.Committed.class, test.module.grants()
            .revokeGrant(OWNER, new RevokeGrantRequest("req-2", view.grantRef(), 1L, "leave"))
            .toCompletableFuture().join()).result().grant();
        assertEquals("revoked", revoked.recordState());
        assertEquals(2, revoked.revision());

        // 已撤销对象再次 revoke 且版本匹配：unchanged
        assertEquals("unchanged", assertInstanceOf(GrantMutationReply.Committed.class, test.module.grants()
            .revokeGrant(OWNER, new RevokeGrantRequest("req-3", view.grantRef(), 2L, "leave"))
            .toCompletableFuture().join()).result().outcome());
        // 已撤销不能借 replace 复活
        assertEquals(ChannelErrorCode.GRANT_STATE_CONFLICT.name(),
            assertInstanceOf(GrantMutationReply.Rejected.class, test.module.grants().replaceGrant(OWNER,
                new ReplaceGrantRequest("req-4", view.grantRef(), 2L,
                    new GrantTerms(List.of(new GrantManagement.TargetRead()),
                        Instant.parse("2026-10-27T00:00:00Z"), Instant.parse("2026-10-30T00:00:00Z")),
                    "revive")).toCompletableFuture().join())
                .error().code());
    }
}
