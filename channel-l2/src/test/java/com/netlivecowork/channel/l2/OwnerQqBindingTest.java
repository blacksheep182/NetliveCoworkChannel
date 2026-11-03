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

class OwnerQqBindingTest {
    @Test void ownerBindingIsLimitedToOneVerifiedQqGroupAndRevocableIndependently() {
        var fixture=new TestModule();
        var entry=new EntryConfig(TestModule.TENANT,TestModule.WORKSPACE,"qq-main","QQ",
            "qq",1,1,1,true,Json.obj(),Map.of(),ConnectionPolicy.defaults(),"default");
        fixture.module.entries().save(entry,"app-1");
        fixture.module.entries().recordObservedAccount("qq-main","app-1");
        fixture.backend.creatable=List.of(new SessionWorkspace("ws-1","Workspace",true));
        var strict=new L2Settings(TestModule.TENANT,TestModule.WORKSPACE,"test-l2",300,10,1,60_000,
            900,5_000,5,1,300_000,60,20,20,false);
        var admission=new AdmissionService(fixture.clock,strict,fixture.module.entries(),
            fixture.module.directory(),fixture.module.requests(),fixture.module.submit());
        String scope1=admission.resolve(entry,inbound("g1","alice","event-1")).routing().scopeRef();
        String scope2=admission.resolve(entry,inbound("g2","alice-other-id","event-2")).routing().scopeRef();
        var owner=new SessionAccess.Manager("owner","verified-w3",fixture.clock.instant().plusSeconds(30),"w3","owner");
        assertEquals(2,fixture.module.observedWeixinGroups(owner).size());
        assertEquals("ws-1",fixture.module.openGroupWorkspace(owner,"qq-main",scope1,"ws-1").sessionWorkspaceRef());
        var service=new OwnerWeixinBindingService(fixture.db,fixture.clock,fixture.module.entries(),fixture.module.directory());
        var first=fixture.module.beginOwnerQqBinding(owner,"qq-main",scope1);
        assertEquals(ChannelErrorCode.PERMISSION_DENIED,assertThrows(ChannelContractException.class,
            ()->service.complete(entry,inbound("g2","alice-other-id","wrong"),first.token())).code());
        assertTrue(service.complete(entry,inbound("g1","alice","bind-1"),first.token()));
        assertTrue(fixture.module.ownerQqBindingStatus(owner,"qq-main",scope1).bound());
        assertFalse(fixture.module.ownerQqBindingStatus(owner,"qq-main",scope2).bound());
        assertTrue(service.matches(entry,"owner","app-1:g1:alice"));
        var second=fixture.module.beginOwnerQqBinding(owner,"qq-main",scope2);
        assertTrue(service.complete(entry,inbound("g2","alice-other-id","bind-2"),second.token()));
        fixture.module.revokeOwnerQqBinding(owner,"qq-main",scope1);
        assertFalse(service.matches(entry,"owner","app-1:g1:alice"));
        assertTrue(service.matches(entry,"owner","app-1:g2:alice-other-id"));
    }

    private static VerifiedIngress inbound(String group,String member,String event) {
        return new VerifiedIngress("qq-"+event,"app-1",
            new VerifiedIngress.Subject(VerifiedIngress.SubjectKind.USER,"app-1:"+group+":"+member),
            new VerifiedIngress.ExternalScope("qq-group",group),
            new VerifiedIngress.ExternalReplyTarget(VerifiedIngress.Audience.SHARED,group,
                Json.obj().put("conversation",group).put("group",true)),event,null);
    }
}
