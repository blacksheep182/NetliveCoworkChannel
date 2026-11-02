package com.netlivecowork.channel.l2;

import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.adapter.*;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.port.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionHistoryTest {
    @Test void explicitHistoryGrantCanBeSerializedAndUsedWithoutExpandingOlderGrants() {
        var t=new TestModule();var e=t.createEntry();
        var delegated=Json.read(t.db.queryOne("SELECT delegable_terms FROM l2_targets WHERE target_ref='target-1'",r->r.getString(1)));
        ((com.fasterxml.jackson.databind.node.ArrayNode)delegated.path("permissions")).add(Json.obj().put("action","history.read"));
        t.db.update("UPDATE l2_targets SET delegable_terms=? WHERE target_ref='target-1'",Json.write(delegated));
        var context=new com.netlivecowork.channel.contract.grant.GrantManagement.TrustedManagementContext(
            TestModule.TENANT,TestModule.WORKSPACE,"fixture-owner","auth");
        var terms=new com.netlivecowork.channel.contract.grant.GrantManagement.GrantTerms(List.of(
            new com.netlivecowork.channel.contract.grant.GrantManagement.TargetRead(),
            new com.netlivecowork.channel.contract.grant.GrantManagement.HistoryRead()),t.clock.instant(),t.clock.instant().plusSeconds(600));
        var request=new com.netlivecowork.channel.contract.grant.GrantManagement.CreateGrantRequest("history-grant",
            new com.netlivecowork.channel.contract.grant.GrantManagement.GrantSpec(terms,"history-reader",
                new com.netlivecowork.channel.contract.grant.GrantManagement.GrantResource("target-1",1,null)),"explicit history permission");
        assertInstanceOf(com.netlivecowork.channel.contract.grant.GrantManagement.GrantMutationReply.Committed.class,
            t.module.grants().createGrant(context,request).toCompletableFuture().join());
        var s=service(t,AdapterCapabilities.HistoryAccess.FULL,p->page(t,null));
        assertEquals(1,s.query(e,call(t,"history-reader"),first(),()->{}).items().size());
        assertThrows(ChannelContractException.class,()->s.query(e,call(t,"caller-a"),first(),()->{}));
    }
    private AdapterCatalog catalog(TestModule t, AdapterCapabilities.HistoryAccess access) {
        return new AdapterCatalog() {
            public AdapterFactory factory(String type) {return t.adapter;}
            public List<AdapterDescriptor> descriptors() {return List.of(descriptor("fake"));}
            public AdapterDescriptor descriptor(String type) {
                var d=t.adapter.describe();var c=d.capabilities();
                return new AdapterDescriptor(d.type(),d.displayName(),d.implementationVersion(),d.contractVersions(),d.configVersions(),
                    d.configurationSchema(),d.configurationUi(),new AdapterCapabilities(c.lifecycle(),c.inbound(),c.textCommandInput(),
                    c.interactiveAuth(),c.connectionTest(),c.files(),c.delivery(),access),d.lifecycleModes());
            }
        };
    }
    private SessionHistoryService service(TestModule t,AdapterCapabilities.HistoryAccess access,
            Function<PageRequest,SessionHistory.BackendPage> read) {
        var backend=(BackendAccessPort)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{BackendAccessPort.class},(proxy,method,args)->{
                assertEquals("queryHistory",method.getName(),"History must never invoke model execution or delivery");
                return CompletableFuture.completedFuture(read.apply((PageRequest)args[2]));
            });
        return new SessionHistoryService(t.db,t.clock,catalog(t,access),backend,t.module.grants(),t.module.requests());
    }
    private TrustedCall call(TestModule t,String principal) {
        return new TrustedCall(TestModule.TENANT,TestModule.WORKSPACE,TestModule.ENTRY,principal,"auth",1,t.clock.instant().plusSeconds(60));
    }
    private SessionHistory.Request first() {return new SessionHistory.Request("target-1",PageRequest.of(null,1));}
    private SessionHistory.BackendPage page(TestModule t,String next) {
        return new SessionHistory.BackendPage(List.of(new SessionHistory.BackendMessage("physical-id","assistant","History",false,t.clock.instant())),next);
    }
    @Test void summaryAdapterCannotEscalateThroughSettingsOrOwnerPrivileges() {
        var t=new TestModule();var e=t.createEntry();t.verifiedOwner("caller-a");
        e.settings().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)e.settings()).put("historyAccess","full");
        var s=service(t,AdapterCapabilities.HistoryAccess.SUMMARY,p->{fail("Backend must not be called");return null;});
        var error=assertThrows(ChannelContractException.class,()->s.query(e,call(t,"caller-a"),first(),()->{}));
        assertEquals(ChannelErrorCode.UNSUPPORTED_CAPABILITY,error.code());
    }
    @Test void fullAdapterDoesNotUpgradeExistingSummaryOnlyUserGrant() {
        var t=new TestModule();var e=t.createEntry();
        var s=service(t,AdapterCapabilities.HistoryAccess.FULL,p->{fail("Backend must not be called");return null;});
        assertEquals(ChannelErrorCode.PERMISSION_DENIED,assertThrows(ChannelContractException.class,
            ()->s.query(e,call(t,"caller-a"),first(),()->{})).code());
    }
    @Test void fullAdapterCanBeRestrictedAndLegacyDescriptorDefaultsToSummary() {
        var t=new TestModule();var e=t.createEntry();t.verifiedOwner("caller-a");
        assertEquals(AdapterCapabilities.HistoryAccess.SUMMARY,t.adapter.describe().capabilities().historyAccess());
        ((com.fasterxml.jackson.databind.node.ObjectNode)e.settings()).put("historyAccess","summary");
        var s=service(t,AdapterCapabilities.HistoryAccess.FULL,p->{fail("Backend must not be called");return null;});
        assertEquals(ChannelErrorCode.UNSUPPORTED_CAPABILITY,assertThrows(ChannelContractException.class,
            ()->s.query(e,call(t,"caller-a"),first(),()->{})).code());
    }
    @Test void publicPageUsesStableL2MessageIdentityAndCursorCannotCrossUsersOrTargets() {
        var t=new TestModule();var e=t.createEntry();t.verifiedOwner("caller-a");
        var s=service(t,AdapterCapabilities.HistoryAccess.FULL,p->page(t,"backend-next"));
        var first=s.query(e,call(t,"caller-a"),first(),()->{});
        var again=s.query(e,call(t,"caller-a"),first(),()->{});
        assertEquals(first.items().getFirst().messageRef(),again.items().getFirst().messageRef());
        assertFalse(first.toString().contains("physical-id"));assertFalse(first.toString().contains("backend-next"));
        var next=new SessionHistory.Request("target-1",PageRequest.of(first.nextCursor(),1));
        assertThrows(ChannelContractException.class,()->s.query(e,call(t,"caller-b"),next,()->{}));
        assertThrows(ChannelContractException.class,()->s.query(e,call(t,"caller-a"),new SessionHistory.Request(
            "target-2",PageRequest.of(first.nextCursor(),1)),()->{}));
        assertNotNull(s.query(e,call(t,"caller-a"),next,()->{}));
    }
    @Test void revocationDuringReadDiscardsBodyAndRegrantDoesNotReviveOldCursor() {
        var t=new TestModule();var e=t.createEntry();t.verifiedOwner("caller-a");
        var policy=new PersonalSessionPolicy(t.db);
        policy.seed(TestModule.TENANT,TestModule.WORKSPACE,"caller-a",List.of("target-1"));
        var s=service(t,AdapterCapabilities.HistoryAccess.FULL,p->page(t,"next"));
        var first=s.query(e,call(t,"caller-a"),first(),()->{});
        var racing=service(t,AdapterCapabilities.HistoryAccess.FULL,p->{
            policy.replace(TestModule.TENANT,TestModule.WORKSPACE,"caller-a",policy.state(TestModule.TENANT,TestModule.WORKSPACE,"caller-a"),List.of());
            return page(t,null);
        });
        assertEquals(ChannelErrorCode.PERMISSION_DENIED,assertThrows(ChannelContractException.class,
            ()->racing.query(e,call(t,"caller-a"),first(),()->{})).code());
        policy.replace(TestModule.TENANT,TestModule.WORKSPACE,"caller-a",policy.state(TestModule.TENANT,TestModule.WORKSPACE,"caller-a"),List.of("target-1"));
        assertEquals(ChannelErrorCode.PERMISSION_DENIED,assertThrows(ChannelContractException.class,
            ()->s.query(e,call(t,"caller-a"),new SessionHistory.Request("target-1",PageRequest.of(first.nextCursor(),1)),()->{})).code());
    }
}
