package com.netlivecowork.channel.l2;

import static org.junit.jupiter.api.Assertions.*;
import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.SessionAccess;
import com.netlivecowork.channel.contract.SessionWorkspace;
import com.netlivecowork.channel.contract.VerifiedIngress;
import com.netlivecowork.channel.contract.adapter.ConnectionPolicy;
import com.netlivecowork.channel.contract.adapter.EntryConfig;
import com.netlivecowork.channel.contract.json.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GroupWorkspaceGrantTest {
    @Test void observedGroupCanOnlyBeOpenedByVerifiedWorkspaceOwnerAndRevoked() {
        TestModule fixture=new TestModule();
        String entryRef="group-weixin",account="bot-a";
        EntryConfig entry=new EntryConfig(TestModule.TENANT,TestModule.WORKSPACE,entryRef,"Weixin",
            "weixin",1,1,1,true,Json.obj(),Map.of(),ConnectionPolicy.defaults(),"default");
        fixture.module.entries().save(entry,account);
        fixture.module.entries().recordObservedAccount(entryRef,account);
        fixture.backend.creatable=List.of(new SessionWorkspace("ws-allowed","Project",true),
            new SessionWorkspace("ws-readonly","Read only",false));
        var admission=new AdmissionService(fixture.clock,TestModule.settings(),fixture.module.entries(),
            fixture.module.directory(),fixture.module.requests(),fixture.module.submit());
        var ingress=new VerifiedIngress("message-1",account,
            new VerifiedIngress.Subject(VerifiedIngress.SubjectKind.USER,"bot-a:user:alice"),
            new VerifiedIngress.ExternalScope("weixin-group","room-1"),
            new VerifiedIngress.ExternalReplyTarget(VerifiedIngress.Audience.SHARED,"room-1",Json.obj()),
            "event-1",null);
        var resolved=admission.resolve(entry,ingress);
        var owner=manager(fixture,"owner");
        assertEquals(1,fixture.module.observedWeixinGroups(owner).size());
        String scope=resolved.routing().scopeRef();
        assertEquals(ChannelErrorCode.PERMISSION_DENIED,assertThrows(ChannelContractException.class,
            ()->fixture.module.openGroupWorkspace(owner,entryRef,scope,"ws-readonly")).code());
        var grant=fixture.module.openGroupWorkspace(owner,entryRef,scope,"ws-allowed");
        assertEquals(grant.grantRef(),fixture.module.openGroupWorkspace(owner,entryRef,scope,"ws-allowed").grantRef());
        assertEquals(ChannelErrorCode.PERMISSION_DENIED,assertThrows(ChannelContractException.class,
            ()->fixture.module.openGroupWorkspace(manager(fixture,"other"),entryRef,scope,"ws-allowed")).code());
        assertTrue(grant.active());
        var revoked=fixture.module.revokeGroupWorkspace(owner,grant.grantRef(),grant.revision());
        assertFalse(revoked.active());
        assertEquals(ChannelErrorCode.GRANT_REVISION_CONFLICT,assertThrows(ChannelContractException.class,
            ()->fixture.module.revokeGroupWorkspace(owner,grant.grantRef(),grant.revision())).code());
        assertNotEquals(grant.grantRef(),fixture.module.openGroupWorkspace(owner,entryRef,scope,"ws-allowed").grantRef());
    }

    private static SessionAccess.Manager manager(TestModule fixture,String owner) {
        return new SessionAccess.Manager(owner,"verified-w3",fixture.clock.instant().plusSeconds(30),"w3",owner);
    }
}
