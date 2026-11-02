package com.netlivecowork.channel.l2;

import static org.junit.jupiter.api.Assertions.*;
import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.Json;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class UserSessionAccessTest {
    private final Runnable guard=()->{};
    private TrustedCall call(TestModule t,String owner) {return new TrustedCall(TestModule.TENANT,TestModule.WORKSPACE,TestModule.ENTRY,owner,null,1,t.clock.instant().plusSeconds(60));}
    private SessionAccess.Candidate candidate(TestModule t,String ref,String owner) {
        return new SessionAccess.Candidate(TargetSummary.of(ref,"真实会话 "+ref),new TargetOwnership(ref,owner,"facts-"+ref,1,1,t.clock.instant().plusSeconds(3600)),"用户工作空间","succeeded");
    }
    private TestModule setup() {
        TestModule t=new TestModule(false);t.createEntry();t.module.management().syncTargets();t.verifiedOwner("owner");
        t.backend.catalogProvider=c->CompletableFuture.completedFuture(new SessionAccess.Catalog(c.principalRef(),"verified-catalog",t.clock.instant(),List.of(candidate(t,"target-1","owner"),candidate(t,"target-2","owner"))));
        return t;
    }
    private UserSessionAccessService service(TestModule t) {return new UserSessionAccessService(t.db,t.clock,t.module.directory(),t.backend,t.module.grants());}
    private void error(ChannelErrorCode code,Runnable work) {assertEquals(code,assertThrows(ChannelContractException.class,work::run).code());}

    @Test void discoveryDoesNotAutoExposeNewSessionsAndExactSaveControlsNormalRights() {
        TestModule t=setup();var svc=service(t);var c=call(t,"owner");var view=svc.get(c,guard);
        assertEquals(2,view.items().size());assertEquals(List.of("target-1"),view.items().stream().filter(SessionAccess.Item::selected).map(SessionAccess.Item::targetRef).toList());
        assertTrue(t.module.grants().canAct(c.tenantRef(),c.workspaceRef(),c.principalRef(),"target-1","input.submit"));
        assertFalse(t.module.grants().canAct(c.tenantRef(),c.workspaceRef(),c.principalRef(),"target-2","input.submit"));
        var saved=svc.save(c,new SessionAccess.Save("select-two",0,List.of("target-2")),guard);assertEquals(1,saved.revision());
        assertFalse(t.module.grants().canAct(c.tenantRef(),c.workspaceRef(),c.principalRef(),"target-1","input.submit"));
        assertTrue(t.module.grants().canAct(c.tenantRef(),c.workspaceRef(),c.principalRef(),"target-2","input.submit"));
        svc.save(c,new SessionAccess.Save("none",1,List.of()),guard);
        assertTrue(svc.get(c,guard).items().stream().noneMatch(SessionAccess.Item::selected));
        assertFalse(t.module.grants().canAct(c.tenantRef(),c.workspaceRef(),c.principalRef(),"target-2","input.submit"));
        assertEquals(2,new PersonalSessionPolicy(t.db).state(c.tenantRef(),c.workspaceRef(),c.principalRef()).revision(),"durable state survives service reconstruction");
    }

    @Test void savesAreCasProtectedIdempotentAndQueryableByOriginalRequest() {
        var t=setup();var s=service(t);var c=call(t,"owner");s.get(c,guard);
        var req=new SessionAccess.Save("original",0,List.of("target-2"));var saved=s.save(c,req,guard);
        assertEquals(saved,s.save(c,req,guard));assertEquals(saved,s.lookup(c,"original",guard));
        error(ChannelErrorCode.REQUEST_CONFLICT,()->s.save(c,new SessionAccess.Save("original",0,List.of()),guard));
        error(ChannelErrorCode.GRANT_REVISION_CONFLICT,()->s.save(c,new SessionAccess.Save("stale",0,List.of()),guard));
        error(ChannelErrorCode.REQUEST_NOT_FOUND,()->s.lookup(c,"not-committed",guard));
        error(ChannelErrorCode.PERMISSION_DENIED,()->s.save(c,new SessionAccess.Save("forged",1,List.of("foreign")),guard));
        assertEquals(List.of("target-2"),s.get(c,guard).items().stream().filter(SessionAccess.Item::selected).map(SessionAccess.Item::targetRef).toList());
    }

    @Test void concurrentSavesOnOneRevisionHaveExactlyOneWinner() throws Exception {
        var t=setup();var s=service(t);var c=call(t,"owner");s.get(c,guard);
        var start=new CountDownLatch(1);var results=new CopyOnWriteArrayList<Object>();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            for(int i=0;i<2;i++){final int n=i;pool.submit(()->{try{start.await();results.add(s.save(c,new SessionAccess.Save("race-"+n,0,n==0?List.of():List.of("target-2")),guard));}catch(Exception e){results.add(e);}});}
            start.countDown();pool.shutdown();assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS));
        }
        assertEquals(1,results.stream().filter(SessionAccess.Saved.class::isInstance).count());
        assertEquals(1,results.stream().filter(x->x instanceof ChannelContractException e&&e.code()==ChannelErrorCode.GRANT_REVISION_CONFLICT).count());
    }

    @Test void realPrincipalAndFreshIdentityAreRequiredBeforeAnyConfigurationMutation() {
        var t=setup();var s=service(t);var c=call(t,"owner");
        t.backend.catalogProvider=ignored->CompletableFuture.completedFuture(new SessionAccess.Catalog("other","auth",t.clock.instant(),List.of()));
        error(ChannelErrorCode.AUTH_REQUIRED,()->s.get(c,guard));
        assertNull(new PersonalSessionPolicy(t.db).state(c.tenantRef(),c.workspaceRef(),c.principalRef()));
        t.backend.catalogProvider=ignored->CompletableFuture.failedFuture(new ChannelContractException(ChannelErrorCode.AUTH_REQUIRED,"Logged out"));
        error(ChannelErrorCode.AUTH_REQUIRED,()->s.get(c,guard));
        assertNull(new PersonalSessionPolicy(t.db).state(c.tenantRef(),c.workspaceRef(),c.principalRef()));
    }

    @Test void aForeignCandidateCannotCreatePositiveRightsAndReplayDoesNotDiscloseTransferredResource() {
        var t=setup();var s=service(t);var c=call(t,"owner");s.get(c,guard);
        s.save(c,new SessionAccess.Save("saved",0,List.of("target-2")),guard);
        t.backend.catalogProvider=ignored->CompletableFuture.completedFuture(new SessionAccess.Catalog("owner","auth",t.clock.instant(),List.of()));
        t.module.directory().setTargetOwner("target-2","other");
        error(ChannelErrorCode.PERMISSION_DENIED,()->s.lookup(c,"saved",guard));
        error(ChannelErrorCode.PERMISSION_DENIED,()->s.save(c,new SessionAccess.Save("cannot-grant",1,List.of("target-2")),guard));
    }

    @Test void changingIdentityWhileCatalogIsInFlightCannotWriteConfiguration() {
        var t=setup();var s=service(t);var c=call(t,"owner");var checks=new java.util.concurrent.atomic.AtomicInteger();
        error(ChannelErrorCode.IDENTITY_UNBOUND,()->s.get(c,()->{if(checks.incrementAndGet()>1)throw new ChannelContractException(ChannelErrorCode.IDENTITY_UNBOUND,"Identity changed");}));
        assertNull(new PersonalSessionPolicy(t.db).state(c.tenantRef(),c.workspaceRef(),c.principalRef()));
        assertNull(t.module.directory().target("target-2"));
    }

    @Test void deselectingAndReselectingCannotReviveAnAlreadyQueuedInput() {
        var t=new TestModule(false);t.createEntry();t.module.management().syncTargets();
        var input=new VerifiedIngress("resolve","account",new VerifiedIngress.Subject(VerifiedIngress.SubjectKind.USER,"alice"),new VerifiedIngress.ExternalScope("test","alice"),new VerifiedIngress.ExternalReplyTarget(VerifiedIngress.Audience.PERSONAL,"alice",Json.obj()),"resolve",null);
        var resolution=t.module.ingress(TestModule.ENTRY).resolve(input).toCompletableFuture().join();
        String owner=resolution.identity().callerRef();t.verifiedOwner(owner);
        t.backend.catalogProvider=c->CompletableFuture.completedFuture(new SessionAccess.Catalog(owner,"auth",t.clock.instant(),List.of(candidate(t,"target-1",owner))));
        var c=call(t,owner);var s=service(t);s.get(c,guard);
        var packet=new CommandPacket("1","queued-packet",CommandType.INPUT_SUBMIT,t.clock.instant(),new Correlation("flow",null),
            new PacketContext(c.tenantRef(),c.workspaceRef(),"target-1","queued",null),new Producer(Layer.L2,"test"),new Actor(owner,null),
            new CommandPayload.InputSubmit(ChannelMessage.of("message",new ContentPart.Text("p","only once")),ChannelOperation.Interpretation.LITERAL));
        t.module.grants().capture(packet);assertTrue(t.module.grants().current("queued"));
        s.save(c,new SessionAccess.Save("remove",0,List.of()),guard);s.save(c,new SessionAccess.Save("reenable",1,List.of("target-1")),guard);
        assertFalse(t.module.grants().current("queued"));
        assertTrue(t.module.grants().canAct(c.tenantRef(),c.workspaceRef(),owner,"target-1","input.submit"));
        var claims=new java.util.concurrent.atomic.AtomicInteger();
        assertFalse(t.module.grants().begin("queued",()->{claims.incrementAndGet();return true;}));
        assertEquals(0,claims.get(),"dispatch must not even claim an old queued attempt after reselect");
    }
}
