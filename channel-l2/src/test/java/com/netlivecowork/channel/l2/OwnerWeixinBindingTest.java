package com.netlivecowork.channel.l2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.ChannelOperation;
import com.netlivecowork.channel.contract.ChannelResponse;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.IngressRequest;
import com.netlivecowork.channel.contract.SessionAccess;
import com.netlivecowork.channel.contract.TrustedCall;
import com.netlivecowork.channel.contract.VerifiedIngress;
import com.netlivecowork.channel.contract.adapter.ConnectionPolicy;
import com.netlivecowork.channel.contract.adapter.EntryConfig;
import com.netlivecowork.channel.contract.json.Json;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;

class OwnerWeixinBindingTest {
    private static final String ENTRY = "owner-weixin";
    private static final String ACCOUNT = "weixin:bot-a";
    private static final String OWNER = "caller-owner";

    @Test
    void ownerChallengeRequiresW3ThenPrivateWeixinAndCanBeRevoked() {
        {
            TestModule fixture = new TestModule();
            EntryConfig entry = entry(fixture);
            var service = new OwnerWeixinBindingService(fixture.db, fixture.clock,
                fixture.module.entries(), fixture.module.directory());
            var manager = manager(fixture);
            var challenge = fixture.module.beginOwnerWeixinBinding(manager, ENTRY);
            assertFalse(fixture.module.ownerWeixinBindingStatus(manager, ENTRY).bound());

            assertTrue(service.complete(entry, inbound("event-1", "alice", null), challenge.token()));
            assertTrue(service.complete(entry, inbound("event-1", "alice", null), challenge.token()));
            assertTrue(fixture.module.ownerWeixinBindingStatus(manager, ENTRY).bound());
            assertTrue(service.matches(entry, OWNER, ACCOUNT + ":user:alice"));
            assertEquals(OWNER, fixture.module.directory().callerFor(ENTRY, 1, "USER", ACCOUNT + ":user:alice"));
            var replay = assertThrows(ChannelContractException.class,
                () -> service.complete(entry, inbound("event-2", "bob", null), challenge.token()));
            assertEquals(ChannelErrorCode.PERMISSION_DENIED, replay.code());

            fixture.module.revokeOwnerWeixinBinding(manager, ENTRY);
            assertFalse(service.matches(entry, OWNER, ACCOUNT + ":user:alice"));
            assertFalse(fixture.module.ownerWeixinBindingStatus(manager, ENTRY).bound());
            assertFalse(OWNER.equals(fixture.module.directory().callerFor(ENTRY, 1, "USER", ACCOUNT + ":user:alice")));
        }
    }

    @Test
    void groupMessageAndAccountChangeCannotCompleteOwnerBinding() {
        {
            TestModule fixture = new TestModule();
            EntryConfig entry = entry(fixture);
            var service = new OwnerWeixinBindingService(fixture.db, fixture.clock,
                fixture.module.entries(), fixture.module.directory());
            var challenge = fixture.module.beginOwnerWeixinBinding(manager(fixture), ENTRY);
            assertEquals(ChannelErrorCode.PERMISSION_DENIED, assertThrows(ChannelContractException.class,
                () -> service.complete(entry, inbound("event-1", "alice", "room-1"), challenge.token())).code());
            fixture.module.entries().bumpAccountBindingEpoch(ENTRY, 1);
            assertEquals(ChannelErrorCode.PERMISSION_DENIED, assertThrows(ChannelContractException.class,
                () -> service.complete(entry, inbound("event-2", "alice", null), challenge.token())).code());
        }
    }

    @Test
    void rebindingRestoresThePreviousWeixinCaller() {
        TestModule fixture = new TestModule();
        EntryConfig entry = entry(fixture);
        String alice = ACCOUNT + ":user:alice";
        fixture.module.directory().bindIdentity(ENTRY, 1, "USER", alice, "caller-alice");
        var service = new OwnerWeixinBindingService(fixture.db, fixture.clock,
            fixture.module.entries(), fixture.module.directory());
        var manager = manager(fixture);
        var first = fixture.module.beginOwnerWeixinBinding(manager, ENTRY);
        service.complete(entry, inbound("event-1", "alice", null), first.token());
        var second = fixture.module.beginOwnerWeixinBinding(manager, ENTRY);
        service.complete(entry, inbound("event-2", "bob", null), second.token());

        assertEquals("caller-alice", fixture.module.directory().callerFor(ENTRY, 1, "USER", alice));
        assertFalse(service.matches(entry, OWNER, alice));
        assertTrue(service.matches(entry, OWNER, ACCOUNT + ":user:bob"));
    }

    @Test
    void boundOwnerCannotUseSharedScopeToReachPersonalSessionsWithoutGroupGrant() {
        TestModule fixture = new TestModule();
        EntryConfig entry = entry(fixture);
        var bindings = new OwnerWeixinBindingService(fixture.db, fixture.clock,
            fixture.module.entries(), fixture.module.directory());
        var challenge = fixture.module.beginOwnerWeixinBinding(manager(fixture), ENTRY);
        bindings.complete(entry, inbound("bind-1", "alice", null), challenge.token());
        var admission = new AdmissionService(fixture.clock, TestModule.settings(), fixture.module.entries(),
            fixture.module.directory(), fixture.module.requests(), fixture.module.submit());
        var resolved = admission.resolve(entry, inbound("group-1", "alice", "room-1"));
        var request = new IngressRequest(IngressRequest.SUPPORTED_VERSION, "group-1", resolved.identity(),
            resolved.routing(), new ChannelOperation.InputSubmit("private-session",
                ChannelOperation.Interpretation.LITERAL, List.of(new ContentPart.Text("p1", "secret")), null));
        var result = admission.submit(entry, resolved.admissionRef(), request);
        assertEquals(ChannelErrorCode.PERMISSION_DENIED,
            ((ChannelResponse.Rejected) result).error().errorCode());
    }

    private static EntryConfig entry(TestModule fixture) {
        var entry = new EntryConfig(TestModule.TENANT, TestModule.WORKSPACE, ENTRY, "Owner Weixin",
            "weixin", 1, 1, 1, true, Json.obj(), Map.of(), ConnectionPolicy.defaults(), "default");
        fixture.module.entries().save(entry, ACCOUNT);
        fixture.module.entries().recordObservedAccount(ENTRY, ACCOUNT);
        return entry;
    }

    private static SessionAccess.Manager manager(TestModule fixture) {
        return new SessionAccess.Manager(OWNER, "verified-w3", fixture.clock.instant().plusSeconds(30),
            "w3", "w3-owner");
    }

    private static VerifiedIngress inbound(String event, String sender, String group) {
        String subject = ACCOUNT + ":user:" + sender;
        boolean shared = group != null;
        return new VerifiedIngress("weixin-" + event, ACCOUNT,
            new VerifiedIngress.Subject(VerifiedIngress.SubjectKind.USER, subject),
            new VerifiedIngress.ExternalScope(shared ? "weixin-group" : "weixin", shared ? group : sender),
            new VerifiedIngress.ExternalReplyTarget(shared ? VerifiedIngress.Audience.SHARED
                : VerifiedIngress.Audience.PERSONAL, shared ? group : sender, Json.obj()), event, null);
    }
}
