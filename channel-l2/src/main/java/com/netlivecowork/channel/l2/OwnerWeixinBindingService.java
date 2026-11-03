package com.netlivecowork.channel.l2;

import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.TrustedCall;
import com.netlivecowork.channel.contract.VerifiedIngress;
import com.netlivecowork.channel.contract.adapter.EntryConfig;
import com.netlivecowork.channel.infra.Db;
import com.netlivecowork.channel.infra.Digests;
import com.netlivecowork.channel.infra.Ids;
import com.netlivecowork.channel.l2.store.DirectoryRepository;
import com.netlivecowork.channel.l2.store.EntryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Two-sided W3/Weixin owner binding. A management token alone cannot name a Weixin sender. */
final class OwnerWeixinBindingService {
    record Challenge(String token, Instant expiresAt) {}
    record Status(boolean bound, String weixinSubject) {}
    record Entry(String entryRef, String name, boolean accountVerified, boolean bound) {}
    private record Pending(String entry, long epoch, String owner, String account, Instant expires,
                           String completedEvent, String completedSubject) {}
    private record Bound(String subject, String priorCaller) {}

    private final Db db;
    private final Clock clock;
    private final EntryRepository entries;
    private final DirectoryRepository directory;

    OwnerWeixinBindingService(Db db, Clock clock, EntryRepository entries, DirectoryRepository directory) {
        this.db = db;
        this.clock = clock;
        this.entries = entries;
        this.directory = directory;
        db.ddl("""
            CREATE TABLE IF NOT EXISTS l2_owner_weixin_challenges(
              token_hash TEXT PRIMARY KEY, entry_ref TEXT NOT NULL, account_binding_epoch INTEGER NOT NULL,
              owner_ref TEXT NOT NULL, account_key TEXT NOT NULL, expires_at TEXT NOT NULL,
              completed_event TEXT, completed_subject TEXT)
            """, """
            CREATE TABLE IF NOT EXISTS l2_owner_weixin_bindings(
              entry_ref TEXT NOT NULL, account_binding_epoch INTEGER NOT NULL, owner_ref TEXT NOT NULL,
              subject_key TEXT NOT NULL, prior_caller TEXT, bound_at TEXT NOT NULL,
              PRIMARY KEY(entry_ref, account_binding_epoch, owner_ref),
              UNIQUE(entry_ref, account_binding_epoch, subject_key))
            """);
    }

    Challenge begin(TrustedCall owner, String entryRef) {
        EntryConfig entry = requireEntry(owner.tenantRef(), owner.workspaceRef(), entryRef);
        String account = registeredAccount(entry);
        String token = Ids.random(40);
        Instant expires = clock.instant().plusSeconds(300);
        db.tx(connection -> {
            db.update("DELETE FROM l2_owner_weixin_challenges WHERE entry_ref=? AND account_binding_epoch=? AND owner_ref=? AND completed_event IS NULL",
                entryRef, entry.accountBindingEpoch(), owner.principalRef());
            db.update("INSERT INTO l2_owner_weixin_challenges VALUES(?,?,?,?,?,?,NULL,NULL)",
                Digests.sha256(token), entryRef, entry.accountBindingEpoch(), owner.principalRef(), account,
                expires.toString());
            return null;
        });
        return new Challenge(token, expires);
    }

    Status status(TrustedCall owner, String entryRef) {
        EntryConfig entry = requireEntry(owner.tenantRef(), owner.workspaceRef(), entryRef);
        String subject = db.queryOne("SELECT subject_key FROM l2_owner_weixin_bindings WHERE entry_ref=? AND account_binding_epoch=? AND owner_ref=?",
            rs -> rs.getString(1), entryRef, entry.accountBindingEpoch(), owner.principalRef());
        if (subject == null) return new Status(false, null);
        DirectoryRepository.IdentityBinding current = directory.identity(entryRef, entry.accountBindingEpoch(),
            VerifiedIngress.SubjectKind.USER.name(), subject);
        return current != null && owner.principalRef().equals(current.callerRef())
            ? new Status(true, subject) : new Status(false, null);
    }

    List<Entry> list(TrustedCall owner) {
        return entries.all().stream()
            .filter(entry -> owner.tenantRef().equals(entry.tenantRef())
                && owner.workspaceRef().equals(entry.workspaceRef())
                && entry.enabled() && "weixin".equals(entry.adapterType()))
            .map(entry -> new Entry(entry.entryRef(), entry.name(),
                entries.observedAccount(entry.entryRef()) != null
                    && (entries.expectedExternalAccountKey(entry.entryRef()) == null
                        || entries.expectedExternalAccountKey(entry.entryRef()).equals(entries.observedAccount(entry.entryRef()))),
                status(owner, entry.entryRef()).bound()))
            .toList();
    }

    boolean complete(EntryConfig entry, VerifiedIngress inbound, String token) {
        if (token == null || !token.matches("[0-9a-z]{40}") || inbound.sourceEventKey() == null)
            throw denied();
        if (inbound.externalReplyTarget() == null
            || inbound.externalReplyTarget().audience() != VerifiedIngress.Audience.PERSONAL
            || inbound.externalSubject().kind() != VerifiedIngress.SubjectKind.USER
            || inbound.externalScope() == null || !"weixin".equals(inbound.externalScope().namespace()))
            throw denied();
        return db.tx(connection -> {
            EntryConfig current = requireEntry(entry.tenantRef(), entry.workspaceRef(), entry.entryRef());
            if (current.accountBindingEpoch() != entry.accountBindingEpoch()
                || !registeredAccount(current).equals(inbound.externalAccountKey())) throw denied();
            Pending pending = db.queryOne("SELECT entry_ref,account_binding_epoch,owner_ref,account_key,expires_at,completed_event,completed_subject FROM l2_owner_weixin_challenges WHERE token_hash=?",
                rs -> new Pending(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                    Instant.parse(rs.getString(5)), rs.getString(6), rs.getString(7)), Digests.sha256(token));
            if (pending == null || !entry.entryRef().equals(pending.entry())
                || entry.accountBindingEpoch() != pending.epoch()
                || !inbound.externalAccountKey().equals(pending.account())) throw denied();
            String subject = inbound.externalSubject().key();
            if (pending.completedEvent() != null) {
                if (!inbound.sourceEventKey().equals(pending.completedEvent())
                    || !subject.equals(pending.completedSubject())) throw denied();
                return true;
            }
            if (!pending.expires().isAfter(clock.instant())) throw denied();
            String occupied = db.queryOne("SELECT owner_ref FROM l2_owner_weixin_bindings WHERE entry_ref=? AND account_binding_epoch=? AND subject_key=?",
                rs -> rs.getString(1), entry.entryRef(), entry.accountBindingEpoch(), subject);
            if (occupied != null && !occupied.equals(pending.owner())) throw denied();
            Bound old = bound(entry, pending.owner());
            if (old != null && !old.subject().equals(subject)) restore(entry, pending.owner(), old);
            DirectoryRepository.IdentityBinding before = directory.identity(entry.entryRef(),
                entry.accountBindingEpoch(), VerifiedIngress.SubjectKind.USER.name(), subject);
            String priorCaller = before == null ? null : before.callerRef();
            if (old != null && old.subject().equals(subject)) priorCaller = old.priorCaller();
            db.update("DELETE FROM l2_owner_weixin_bindings WHERE entry_ref=? AND account_binding_epoch=? AND owner_ref=?",
                entry.entryRef(), entry.accountBindingEpoch(), pending.owner());
            directory.rebindIdentity(entry.entryRef(), entry.accountBindingEpoch(),
                VerifiedIngress.SubjectKind.USER.name(), subject, pending.owner());
            db.update("INSERT INTO l2_owner_weixin_bindings VALUES(?,?,?,?,?,?)", entry.entryRef(),
                entry.accountBindingEpoch(), pending.owner(), subject, priorCaller, clock.instant().toString());
            db.update("UPDATE l2_owner_weixin_challenges SET completed_event=?,completed_subject=? WHERE token_hash=?",
                inbound.sourceEventKey(), subject, Digests.sha256(token));
            return true;
        });
    }

    boolean matches(EntryConfig entry, String ownerRef, String subjectKey) {
        if (ownerRef == null || subjectKey == null) return false;
        String bound = db.queryOne("SELECT subject_key FROM l2_owner_weixin_bindings WHERE entry_ref=? AND account_binding_epoch=? AND owner_ref=?",
            rs -> rs.getString(1), entry.entryRef(), entry.accountBindingEpoch(), ownerRef);
        if (!subjectKey.equals(bound)) return false;
        DirectoryRepository.IdentityBinding current = directory.identity(entry.entryRef(),
            entry.accountBindingEpoch(), VerifiedIngress.SubjectKind.USER.name(), subjectKey);
        return current != null && ownerRef.equals(current.callerRef());
    }

    void revoke(TrustedCall owner, String entryRef) {
        EntryConfig entry = requireEntry(owner.tenantRef(), owner.workspaceRef(), entryRef);
        db.tx(connection -> {
            Bound old = bound(entry, owner.principalRef());
            if (old != null) restore(entry, owner.principalRef(), old);
            db.update("DELETE FROM l2_owner_weixin_challenges WHERE entry_ref=? AND account_binding_epoch=? AND owner_ref=?",
                entryRef, entry.accountBindingEpoch(), owner.principalRef());
            db.update("DELETE FROM l2_owner_weixin_bindings WHERE entry_ref=? AND account_binding_epoch=? AND owner_ref=?",
                entryRef, entry.accountBindingEpoch(), owner.principalRef());
            return null;
        });
    }

    private Bound bound(EntryConfig entry, String owner) {
        return db.queryOne("SELECT subject_key,prior_caller FROM l2_owner_weixin_bindings WHERE entry_ref=? AND account_binding_epoch=? AND owner_ref=?",
            rs -> new Bound(rs.getString(1), rs.getString(2)), entry.entryRef(),
            entry.accountBindingEpoch(), owner);
    }

    private void restore(EntryConfig entry, String owner, Bound old) {
        DirectoryRepository.IdentityBinding current = directory.identity(entry.entryRef(),
            entry.accountBindingEpoch(), VerifiedIngress.SubjectKind.USER.name(), old.subject());
        if (current == null || !owner.equals(current.callerRef())) return;
        String fallback = old.priorCaller() == null
            ? "caller-unbound-" + Digests.sha256(entry.entryRef() + "\u0000" + entry.accountBindingEpoch()
                + "\u0000" + old.subject()).substring(0, 23) : old.priorCaller();
        directory.rebindIdentity(entry.entryRef(), entry.accountBindingEpoch(),
            VerifiedIngress.SubjectKind.USER.name(), old.subject(), fallback);
    }

    private EntryConfig requireEntry(String tenant, String workspace, String entryRef) {
        EntryConfig entry = entries.find(tenant, workspace, entryRef);
        if (entry == null) throw new ChannelContractException(ChannelErrorCode.REQUEST_NOT_FOUND, "Unknown Weixin entry");
        if (!entry.enabled() || !"weixin".equals(entry.adapterType())) throw denied();
        return entry;
    }

    private String registeredAccount(EntryConfig entry) {
        String observed = entries.observedAccount(entry.entryRef());
        String expected = entries.expectedExternalAccountKey(entry.entryRef());
        if (observed == null || observed.isBlank() || expected != null && !expected.equals(observed))
            throw new ChannelContractException(ChannelErrorCode.AUTH_REQUIRED, "Weixin bot account is not verified");
        return observed;
    }

    private static ChannelContractException denied() {
        return new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED, "Weixin owner binding is unavailable");
    }
}
