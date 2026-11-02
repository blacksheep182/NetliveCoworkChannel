package com.netlivecowork.channel.l3;

import static org.junit.jupiter.api.Assertions.*;
import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.infra.Db;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnedSessionCatalogTest {
    @TempDir Path temporary;
    private String catalog(String subject,String session,String next,long epoch) {
        return "{\"backend_id\":\"backend-1\",\"principal\":{\"subject_issuer\":\"w3\",\"subject\":\""+subject+"\"},\"items\":[{\"session_id\":\""+session+"\",\"resource_epoch\":"+epoch+",\"revision\":1,\"title\":\"真正的会话标题\",\"workspace_label\":\"真实工作空间\",\"status\":\"succeeded\"}]"+(next==null?"":",\"next_cursor\":\""+next+"\"")+"}";
    }
    private L3Module module(TestBackend backend,Db db) {
        var m=new L3Module(db,Clock.systemUTC(),TestFixtures.settings(),new TestFixtures.RecordingIntake(),ServiceCredentialResolver.NOOP,TestFixtures.identities());
        TestFixtures.seed(m,backend.baseUrl());return m;
    }
    private void fails(ChannelErrorCode code,Runnable action) {
        var e=assertThrows(java.util.concurrent.CompletionException.class,action::run);
        assertEquals(code,assertInstanceOf(ChannelContractException.class,e.getCause()).code());
    }
    @Test void authenticatesRealCatalogBeforeStagingLogicalTargetsAndRequiresSamePrincipalToRegister() throws Exception {
        try(var backend=new TestBackend(c->TestBackend.Response.json(200,catalog("user-1","new-real-session",null,1)));var db=TestFixtures.db(temporary);var m=module(backend,db)) {
            var view=m.access().discoverOwnedSessions(TestFixtures.call()).toCompletableFuture().join();
            assertEquals(1,view.candidates().size());var item=view.candidates().getFirst();
            assertEquals("真正的会话标题",item.summary().label());assertEquals("真实工作空间",item.workspaceLabel());
            assertNull(m.registry().target(item.summary().targetRef()),"discovery cannot auto-enroll before L2 freezes existing exposure");
            var foreign=new TrustedCall(TestFixtures.TENANT,TestFixtures.WORKSPACE,"entry-1","other",null,1,null);
            fails(ChannelErrorCode.AUTH_REQUIRED,()->m.access().registerCatalogTargets(foreign,view.authenticationRef()).toCompletableFuture().join());
            m.access().registerCatalogTargets(TestFixtures.call(),view.authenticationRef()).toCompletableFuture().join();
            assertEquals("new-real-session",m.registry().target(item.summary().targetRef()).sessionId());
            fails(ChannelErrorCode.AUTH_REQUIRED,()->m.access().registerCatalogTargets(TestFixtures.call(),view.authenticationRef()).toCompletableFuture().join());
        }
    }
    @Test void catalogCannotSubstituteAnotherAuthenticatedPrincipal() throws Exception {
        try(var backend=new TestBackend(c->TestBackend.Response.json(200,catalog("foreign","new",null,1)));var db=TestFixtures.db(temporary);var m=module(backend,db)) {
            fails(ChannelErrorCode.PERMISSION_DENIED,()->m.access().discoverOwnedSessions(TestFixtures.call()).toCompletableFuture().join());
            assertNull(m.registry().targetBySession(TestFixtures.SERVICE,"new"));
        }
    }
    @Test void catalogNeverSilentlyRebindsAnExistingSessionIncarnation() throws Exception {
        try(var backend=new TestBackend(c->TestBackend.Response.json(200,catalog("user-1",TestFixtures.SESSION,null,2)));var db=TestFixtures.db(temporary);var m=module(backend,db)) {
            fails(ChannelErrorCode.BINDING_CONFLICT,()->m.access().discoverOwnedSessions(TestFixtures.call()).toCompletableFuture().join());
            assertEquals(1,m.registry().target(TestFixtures.TARGET).resourceEpoch());
        }
    }
    @Test void allPagesAreVerifiedAndRepeatedCursorCannotReturnAPartialCatalog() throws Exception {
        try(var backend=new TestBackend(c->TestBackend.Response.json(200,catalog("user-1",c.query()==null?"first":"second","repeat",1)));var db=TestFixtures.db(temporary);var m=module(backend,db)) {
            fails(ChannelErrorCode.UNAVAILABLE,()->m.access().discoverOwnedSessions(TestFixtures.call()).toCompletableFuture().join());
            assertNull(m.registry().targetBySession(TestFixtures.SERVICE,"first"));
        }
    }
    @Test void backendW3LogoutIsNotTreatedAsAnEmptySuccessfulConfiguration() throws Exception {
        try(var backend=new TestBackend(c->TestBackend.Response.json(401,"{}"));var db=TestFixtures.db(temporary);var m=module(backend,db)) {
            fails(ChannelErrorCode.AUTH_REQUIRED,()->m.access().discoverOwnedSessions(TestFixtures.call()).toCompletableFuture().join());
        }
    }
    @Test void desktopManagerUsesVerifiedActualSubjectWithoutEnrollingAnyTargets() throws Exception {
        try(var backend=new TestBackend(c->TestBackend.Response.json(200,catalog("user-1","new",null,1)));var db=TestFixtures.db(temporary);var m=module(backend,db)) {
            var manager=m.access().verifySessionManager(TestFixtures.SERVICE,"actual-desktop-proof").toCompletableFuture().join();
            assertEquals(TestFixtures.call().principalRef(),manager.principalRef());
            assertEquals("user-1",manager.subject());assertEquals("w3",manager.subjectIssuer());
            assertTrue(manager.validUntil().isAfter(java.time.Instant.now()));
            assertNull(m.registry().targetBySession(TestFixtures.SERVICE,"new"));
        }
    }
    @Test void desktopManagerRejectsMissingProofBeforeBackendRequest() throws Exception {
        try(var backend=new TestBackend(c->TestBackend.Response.json(200,catalog("user-1","new",null,1)));var db=TestFixtures.db(temporary);var m=module(backend,db)) {
            for(String proof:new String[]{null,""," "})fails(ChannelErrorCode.AUTH_REQUIRED,()->m.access().verifySessionManager(TestFixtures.SERVICE,proof).toCompletableFuture().join());
            assertEquals(0,backend.callCount());
        }
    }
    @Test void desktopManagerCannotUseAnotherSubjectOrLoggedOutProof() throws Exception {
        try(var backend=new TestBackend(c->TestBackend.Response.json(200,catalog("foreign","new",null,1)));var db=TestFixtures.db(temporary);var m=module(backend,db)) {
            fails(ChannelErrorCode.PERMISSION_DENIED,()->m.access().verifySessionManager(TestFixtures.SERVICE,"actual-proof").toCompletableFuture().join());
            backend.handler(c->TestBackend.Response.json(401,"{}"));
            fails(ChannelErrorCode.AUTH_REQUIRED,()->m.access().verifySessionManager(TestFixtures.SERVICE,"expired-proof").toCompletableFuture().join());
        }
    }
}
