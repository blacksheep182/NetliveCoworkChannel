package com.netlivecowork.channel.l2;

import static org.junit.jupiter.api.Assertions.*;
import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.grant.GrantManagement.*;
import com.netlivecowork.channel.contract.grant.GrantManagement;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.l2.store.DirectoryRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The configured user's permissions, not profile contents or number of backend sessions, define the list. */
class ConfiguredSessionVisibilityTest {
    private TestModule fixture(int count) {
        var t = new TestModule(false); t.createEntry(); t.module.management().syncTargets(); t.verifiedOwner("owner");
        String terms = t.db.queryOne("SELECT delegable_terms FROM l2_targets WHERE target_ref='target-1'", r -> r.getString(1));
        for (int i = 2; i <= count; i++) {
            String target = "target-" + i;
            t.module.directory().upsertTarget(new DirectoryRepository.TargetRow(target, TestModule.TENANT,
                TestModule.WORKSPACE, null, String.format("Session %03d", i), List.of(), TargetSummary.Availability.ONLINE, null, 1));
            t.module.directory().setTargetOwner(target, "owner");
            t.db.update("UPDATE l2_targets SET resource_epoch=1,evidence_valid_until=?,delegable_terms=? WHERE target_ref=?",
                t.clock.instant().plusSeconds(604800).toString(), terms, target);
        }
        for (String user : List.of("alice", "bob", "stranger"))
            t.module.directory().bindIdentity(TestModule.ENTRY, 1, "USER", user, user);
        return t;
    }
    private GrantView allow(TestModule t, String user, String target) {
        var terms = new GrantTerms(List.of(new GrantManagement.TargetRead(), new GrantManagement.SessionSelect()),
            t.clock.instant(), t.clock.instant().plusSeconds(3600));
        return assertInstanceOf(GrantMutationReply.Committed.class, t.module.grants().createGrant(
            new TrustedManagementContext(TestModule.TENANT, TestModule.WORKSPACE, "owner", "verified-test-owner"),
            new CreateGrantRequest("grant-" + user + target,
                new GrantSpec(terms,user,new GrantResource(target,1,null)),"configured access"))
            .toCompletableFuture().join()).result().grant();
    }
    private ChannelResponse submit(TestModule t, String user, ChannelOperation operation) {
        String id = java.util.UUID.randomUUID().toString();
        var ingress = t.module.ingress(TestModule.ENTRY);
        var verified = new VerifiedIngress(id,"acct-1",new VerifiedIngress.Subject(VerifiedIngress.SubjectKind.USER,user),
            new VerifiedIngress.ExternalScope("web",user),new VerifiedIngress.ExternalReplyTarget(VerifiedIngress.Audience.PERSONAL,user,Json.obj().put("conversation",user)),id,null);
        var resolved = ingress.resolve(verified).toCompletableFuture().join();
        return ingress.submit(resolved.admissionRef(),new IngressRequest("1",id,resolved.identity(),resolved.routing(),operation)).toCompletableFuture().join();
    }
    private OperationResult.SessionList list(TestModule t, String user, String cursor, int limit) {
        return assertInstanceOf(OperationResult.SessionList.class, assertInstanceOf(ChannelResponse.Completed.class,
            submit(t,user,new ChannelOperation.SessionList(null,new PageRequest(cursor,limit)))).result());
    }
    @Test void differentUsersSeeExactlyTheirConfiguredSessionsAndCannotSelectHiddenOnes() {
        var t = fixture(3); allow(t,"alice","target-1"); allow(t,"alice","target-2"); allow(t,"bob","target-3");
        assertEquals(2,list(t,"alice",null,20).items().size());
        assertEquals(List.of("target-3"),list(t,"bob",null,20).items().stream().map(SessionSummary::targetRef).toList());
        assertTrue(list(t,"stranger",null,20).items().isEmpty());
        assertInstanceOf(ChannelResponse.Rejected.class,submit(t,"alice",new ChannelOperation.SessionSelect("target-3",0)));
    }
    @Test void revocationRemovesTheSessionOnTheNextListRequest() {
        var t = fixture(2); var grant=allow(t,"alice","target-1"); allow(t,"alice","target-2");
        assertEquals(2,list(t,"alice",null,20).items().size());
        t.module.grants().revokeGrant(new TrustedManagementContext(TestModule.TENANT,TestModule.WORKSPACE,"owner","verified-test-owner"),
            new RevokeGrantRequest("revoke-one",grant.grantRef(),grant.revision(),"remove configured access")).toCompletableFuture().join();
        assertEquals(List.of("target-2"),list(t,"alice",null,20).items().stream().map(SessionSummary::targetRef).toList());
    }
    @Test void pagesContainEveryAuthorizedSessionAndExcludeUnconfiguredOnes() {
        var t = fixture(25); for(int i=1;i<=24;i++)allow(t,"alice","target-"+i);
        var first=list(t,"alice",null,20); var second=list(t,"alice",first.nextCursor(),20);
        assertEquals(20,first.items().size()); assertEquals(4,second.items().size()); assertNull(second.nextCursor());
        var ids=new java.util.HashSet<>(first.items().stream().map(SessionSummary::targetRef).toList());
        second.items().forEach(s->assertTrue(ids.add(s.targetRef()))); assertEquals(24,ids.size()); assertFalse(ids.contains("target-25"));
    }
}
