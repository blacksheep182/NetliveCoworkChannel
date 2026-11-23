package com.netlivecowork.channel.l2;

import com.fasterxml.jackson.databind.JsonNode;
import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.VerifiedBackendEvent;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.port.BackendAccessPort;
import com.netlivecowork.channel.infra.Db;
import com.netlivecowork.channel.l2.store.DirectoryRepository;
import java.util.List;
import java.util.Objects;

/** Keeps the original delivery authorization without managing subscriptions on Channel. */
final class HostSubscriptionDeliveries {
    record Basis(String serviceRef, String sinkRef, String subscriptionRef, long revision, String eventId,
                 String entryRef, String destinationRef, long destinationRevision, String callerRef,
                 String targetRef, String permissionRef, long generation, long targetGeneration) {}

    private final Db db;
    private final DirectoryRepository directory;
    private final BackendAccessPort backend;
    private final GrantService grants;

    HostSubscriptionDeliveries(Db db, DirectoryRepository directory, BackendAccessPort backend, GrantService grants) {
        this.db = db;
        this.directory = directory;
        this.backend = backend;
        this.grants = grants;
        db.ddl("""
            CREATE TABLE IF NOT EXISTS l2_host_subscription_bases(
              delivery_id TEXT NOT NULL, service_ref TEXT NOT NULL, sink_ref TEXT NOT NULL,
              subscription_ref TEXT NOT NULL, revision INTEGER NOT NULL, event_id TEXT NOT NULL,
              entry_ref TEXT NOT NULL, destination_ref TEXT NOT NULL, destination_revision INTEGER NOT NULL,
              caller_ref TEXT NOT NULL, target_ref TEXT NOT NULL, permission_ref TEXT NOT NULL,
              generation INTEGER NOT NULL, target_generation INTEGER NOT NULL,
              PRIMARY KEY(delivery_id, subscription_ref, revision))
            """);
    }

    Basis authorize(VerifiedBackendEvent event) {
        var observation = event.observation();
        var basis = lookup(observation.serviceRef(), observation.sinkRef(), observation.ref(),
            observation.revision(), event.source().sourceEventId(), observation.eventDigest());
        if (basis == null || !Objects.equals(event.targetRef(), basis.targetRef()) || !current(basis))
            return null;
        return basis;
    }

    private Basis lookup(String service, String sink, String subscription, long revision, String event, String expectedDigest) {
        JsonNode answer;
        try {
            answer = backend.subscriptionEvent(service, subscription, revision, event);
        } catch (ChannelContractException error) {
            if (error.code() == ChannelErrorCode.PERMISSION_DENIED) return null;
            throw error;
        }
        if (!sink.equals(Json.text(answer, "sinkRef"))
                || (expectedDigest != null && !expectedDigest.equals(Json.text(answer, "eventDigest")))) return null;
        return new Basis(service, sink, subscription, revision, event, Json.text(answer, "entryRef"),
            Json.text(answer, "destinationRef"), Json.reqLong(answer, "destinationRevision"),
            Json.text(answer, "callerRef"), Json.text(answer, "targetRef"), Json.text(answer, "permissionRef"),
            Json.reqLong(answer, "generation"), Json.reqLong(answer, "targetGeneration"));
    }

    private boolean destinationCurrent(Basis basis) {
        var destination = directory.destination(basis.destinationRef());
        var target = directory.target(basis.targetRef());
        return destination != null && target != null && "PERSONAL".equals(destination.audience())
            && basis.entryRef().equals(destination.entryRef()) && basis.callerRef().equals(destination.callerRef())
            && basis.destinationRevision() == destination.destinationRevision()
            && target.tenantRef().equals(destination.tenantRef()) && target.workspaceRef().equals(destination.workspaceRef());
    }

    void remember(String delivery, Basis basis) {
        db.update("""
            INSERT OR IGNORE INTO l2_host_subscription_bases VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, delivery, basis.serviceRef(), basis.sinkRef(), basis.subscriptionRef(), basis.revision(),
            basis.eventId(), basis.entryRef(), basis.destinationRef(), basis.destinationRevision(), basis.callerRef(),
            basis.targetRef(), basis.permissionRef(), basis.generation(), basis.targetGeneration());
    }

    private List<Basis> bases(String delivery, String recipient) {
        return db.query("""
            SELECT * FROM l2_host_subscription_bases WHERE delivery_id=? AND caller_ref=?
            """, r -> new Basis(r.getString("service_ref"), r.getString("sink_ref"), r.getString("subscription_ref"),
            r.getLong("revision"), r.getString("event_id"), r.getString("entry_ref"), r.getString("destination_ref"),
            r.getLong("destination_revision"), r.getString("caller_ref"), r.getString("target_ref"),
            r.getString("permission_ref"), r.getLong("generation"), r.getLong("target_generation")), delivery, recipient);
    }

    boolean hasBasis(String delivery, String recipient) {
        return !bases(delivery, recipient).isEmpty();
    }

    boolean mayBegin(String delivery, String recipient) {
        for (var basis : bases(delivery, recipient)) {
            if (current(basis) && basis.equals(lookup(basis.serviceRef(), basis.sinkRef(),
                    basis.subscriptionRef(), basis.revision(), basis.eventId(), null))) return true;
        }
        return false;
    }

    boolean mayRead(String delivery, String recipient) {
        return bases(delivery, recipient).stream().anyMatch(this::current);
    }

    private boolean current(Basis basis) {
        if (!destinationCurrent(basis)) return false;
        var destination=directory.destination(basis.destinationRef());
        try {
            return grants.withEntryPermissions(basis.entryRef(), false, () -> {
                var grant = grants.currentHostEntry(basis.entryRef(), basis.targetRef(),
                    basis.generation(), basis.targetGeneration());
                return basis.permissionRef().equals(Json.text(grant, "permissionRef"))
                    && grants.currentPersonalSession(destination.tenantRef(), destination.workspaceRef(),
                        basis.callerRef(), basis.targetRef(), grant);
            });
        } catch (ChannelContractException error) {
            if (error.code() != ChannelErrorCode.PERMISSION_DENIED) throw error;
            return false;
        }
    }
}
