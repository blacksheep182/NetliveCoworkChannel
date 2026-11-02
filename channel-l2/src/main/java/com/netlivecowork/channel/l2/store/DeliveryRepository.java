package com.netlivecowork.channel.l2.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.infra.Db;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 订阅、outbox 与投递依据（l2-application.md §3、§4）。 */
public final class DeliveryRepository {

    private final Db db;
    private final Clock clock;

    public DeliveryRepository(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
        db.ddl("CREATE TABLE IF NOT EXISTS l2_delivery_modes(delivery_id TEXT PRIMARY KEY,handover TEXT NOT NULL)");
    }

    public boolean freezeHandover(String delivery,String mode) {
        return db.tx(c->{
            db.update("INSERT OR IGNORE INTO l2_delivery_modes VALUES(?,?)",delivery,mode);
            return mode.equals(db.queryOne("SELECT handover FROM l2_delivery_modes WHERE delivery_id=?",r->r.getString(1),delivery));
        });
    }

    /** Only the common inbox's durable same-key insertion permits this recovery. Provider UNKNOWN never uses it. */
    public boolean retryInboxUnknown(String id) {
        return db.update("""
            UPDATE l2_outbox SET status='RETRY',next_attempt_at=?,owner=NULL,lease_until=NULL
            WHERE delivery_id=? AND status='UNKNOWN'
              AND EXISTS(SELECT 1 FROM l2_delivery_modes WHERE delivery_id=? AND handover='DURABLE_INBOX')
            """,clock.instant().toString(),id,id)==1;
    }

    // ---- subscriptions ---------------------------------------------------------------

    public record SubscriptionRow(String subscriptionRef, String tenantRef, String workspaceRef,
                                  String subscriberRef, String resourceType, String resourceRef,
                                  List<String> eventTypes, String entryRef, String destinationRef,
                                  long destinationRevision, String status, long generation, long revision,
                                  String cursor, String startBoundary, String watchRef) {
    }

    public void saveSubscription(SubscriptionRow row) {
        String now = clock.instant().toString();
        db.update("""
            INSERT INTO l2_subscriptions(subscription_ref, tenant_ref, workspace_ref, subscriber_ref,
              resource_type, resource_ref, event_types, entry_ref, destination_ref, destination_revision,
              status, generation, revision, cursor, start_boundary, watch_ref, created_at, updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            row.subscriptionRef(), row.tenantRef(), row.workspaceRef(), row.subscriberRef(),
            row.resourceType(), row.resourceRef(), Json.write(Json.mapper().valueToTree(row.eventTypes())),
            row.entryRef(), row.destinationRef(), row.destinationRevision(), row.status(), row.generation(),
            row.revision(), row.cursor(), row.startBoundary(), row.watchRef(), now, now);
    }

    public SubscriptionRow subscription(String subscriptionRef) {
        return db.queryOne("SELECT * FROM l2_subscriptions WHERE subscription_ref=?", this::mapSubscription,
            subscriptionRef);
    }

    public List<SubscriptionRow> activeSubscriptions(String tenantRef, String workspaceRef) {
        return db.query("""
            SELECT * FROM l2_subscriptions WHERE tenant_ref=? AND workspace_ref=? AND status='ACTIVE'
            """, this::mapSubscription, tenantRef, workspaceRef);
    }

    public List<SubscriptionRow> subscriptionsForResource(String resourceType, String resourceRef) {
        return db.query("""
            SELECT * FROM l2_subscriptions WHERE resource_type=? AND resource_ref=? AND status='ACTIVE'
            """, this::mapSubscription, resourceType, resourceRef);
    }

    public void updateSubscriptionStatus(String subscriptionRef, String status, long revision, String watchRef,
                                         String startBoundary) {
        db.update("""
            UPDATE l2_subscriptions SET status=?, revision=?, watch_ref=COALESCE(?, watch_ref),
              start_boundary=COALESCE(?, start_boundary), updated_at=?
            WHERE subscription_ref=?
            """, status, revision, watchRef, startBoundary, clock.instant().toString(), subscriptionRef);
    }

    public void advanceSubscriptionCursor(String subscriptionRef, String cursor) {
        db.update("UPDATE l2_subscriptions SET cursor=?, updated_at=? WHERE subscription_ref=?",
            cursor, clock.instant().toString(), subscriptionRef);
    }

    private SubscriptionRow mapSubscription(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<String> eventTypes = new ArrayList<>();
        for (JsonNode item : Json.read(Db.str(rs, "event_types"))) {
            eventTypes.add(item.asText());
        }
        return new SubscriptionRow(Db.str(rs, "subscription_ref"), Db.str(rs, "tenant_ref"),
            Db.str(rs, "workspace_ref"), Db.str(rs, "subscriber_ref"), Db.str(rs, "resource_type"),
            Db.str(rs, "resource_ref"), eventTypes, Db.str(rs, "entry_ref"), Db.str(rs, "destination_ref"),
            rs.getLong("destination_revision"), Db.str(rs, "status"), rs.getLong("generation"),
            rs.getLong("revision"), Db.str(rs, "cursor"), Db.str(rs, "start_boundary"),
            Db.str(rs, "watch_ref"));
    }

    // ---- outbox ----------------------------------------------------------------------

    public enum DeliveryStatus {
        PENDING, SENDING, ACCEPTED, RETRY, UNKNOWN, PAUSED, FAILED, CANCELLED
    }

    public record DeliveryRow(String deliveryId, String tenantRef, String workspaceRef, String entryRef,
                              String destinationRef, long destinationRevision, String packetId,
                              String deliveryJson, DeliveryStatus status, int attemptCount,
                              Instant nextAttemptAt, Instant leaseUntil, String owner, String receiptRef,
                              String lastError) {
    }

    public enum RelationType {
        REPLY, SUBSCRIPTION
    }

    public record RelationRow(String deliveryId, RelationType type, String relationRef, long generation,
                              String authorizedBy, boolean active) {
    }

    /** 同一资源域 + 目的地版本 + packetId 只对应一个逻辑投递（INV-37）。 */
    public boolean insertDelivery(DeliveryRow row) {
        String now = clock.instant().toString();
        int rows = db.update("""
            INSERT OR IGNORE INTO l2_outbox(delivery_id, tenant_ref, workspace_ref, entry_ref, destination_ref,
              destination_revision, packet_id, delivery_json, status, attempt_count, next_attempt_at,
              lease_until, owner, receipt_ref, last_error, created_at, updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, row.deliveryId(), row.tenantRef(), row.workspaceRef(), row.entryRef(), row.destinationRef(),
            row.destinationRevision(), row.packetId(), row.deliveryJson(), row.status().name(),
            row.attemptCount(), instant(row.nextAttemptAt()), instant(row.leaseUntil()), row.owner(),
            row.receiptRef(), row.lastError(), now, now);
        return rows == 1;
    }

    public DeliveryRow delivery(String deliveryId) {
        return db.queryOne("SELECT * FROM l2_outbox WHERE delivery_id=?", this::mapDelivery, deliveryId);
    }

    public DeliveryRow deliveryFor(String tenantRef, String workspaceRef, String entryRef,
                                   String destinationRef, long destinationRevision, String packetId) {
        return db.queryOne("""
            SELECT * FROM l2_outbox WHERE tenant_ref=? AND workspace_ref=? AND entry_ref=?
              AND destination_ref=? AND destination_revision=? AND packet_id=?
            """, this::mapDelivery, tenantRef, workspaceRef, entryRef, destinationRef, destinationRevision,
            packetId);
    }

    public void addRelation(RelationRow relation) {
        db.update("""
            INSERT OR IGNORE INTO l2_outbox_relations(delivery_id, relation_type, relation_ref, generation,
              authorized_by, active) VALUES(?,?,?,?,?,?)
            """, relation.deliveryId(), relation.type().name(), relation.relationRef(), relation.generation(),
            relation.authorizedBy(), relation.active());
    }

    public List<RelationRow> relations(String deliveryId) {
        return db.query("SELECT * FROM l2_outbox_relations WHERE delivery_id=?", rs -> new RelationRow(
            Db.str(rs, "delivery_id"), RelationType.valueOf(Db.str(rs, "relation_type")),
            Db.str(rs, "relation_ref"), rs.getLong("generation"), Db.str(rs, "authorized_by"),
            Db.bool(rs, "active")), deliveryId);
    }

    public boolean hasActiveRelation(String deliveryId) {
        Integer count = db.queryOne("""
            SELECT COUNT(*) AS c FROM l2_outbox_relations WHERE delivery_id=? AND active=1
            """, rs -> rs.getInt("c"), deliveryId);
        return count != null && count > 0;
    }

    /** 取消关系只移除该依据；同时取消已无有效依据的未发送积压（l2-application.md §3.3）。 */
    public int deactivateRelations(RelationType type, String relationRef) {
        return db.update("""
            UPDATE l2_outbox_relations SET active=0 WHERE relation_type=? AND relation_ref=? AND active=1
            """, type.name(), relationRef);
    }

    public void cancelUnbackedDeliveries() {
        db.update("""
            UPDATE l2_outbox SET status='CANCELLED', updated_at=?
            WHERE status IN ('PENDING','RETRY')
              AND NOT EXISTS (SELECT 1 FROM l2_outbox_relations r
                              WHERE r.delivery_id=l2_outbox.delivery_id AND r.active=1)
            """, clock.instant().toString());
    }

    /** 领取：pending 到期或 sending 租约过期的记录。 */
    public List<DeliveryRow> claimable(int limit) {
        String now = clock.instant().toString();
        return db.query("""
            SELECT * FROM l2_outbox
            WHERE (status IN ('PENDING','RETRY') AND (next_attempt_at IS NULL OR next_attempt_at<=?))
               OR (status='SENDING' AND (lease_until IS NULL OR lease_until<=?))
            ORDER BY created_at LIMIT ?
            """, this::mapDelivery, now, now, limit);
    }

    /**
     * 领取：以 (status, attempt_count) 作为乐观栅栏——两个 worker 拿到同一快照时，
     * 只有一个能把 N 改成 N+1 并成功领取（INV-49 单发送者）。
     */
    public boolean markSending(DeliveryRow current, String owner, Instant leaseUntil) {
        int rows = db.update("""
            UPDATE l2_outbox SET status='SENDING', owner=?, lease_until=?, attempt_count=attempt_count+1,
              updated_at=?
            WHERE delivery_id=? AND status=? AND attempt_count=?
            """, owner, instant(leaseUntil), clock.instant().toString(), current.deliveryId(),
            current.status().name(), current.attemptCount());
        return rows == 1;
    }

    /** 回执写回必须带 owner + attempt 围栏：过期领取者不能覆盖新领取者的结论（INV-49）。 */
    public boolean markAccepted(String deliveryId, String receiptRef, String owner, int attemptNumber) {
        return db.update("""
            UPDATE l2_outbox SET status='ACCEPTED', receipt_ref=?, lease_until=NULL, owner=NULL,
              last_error=NULL, updated_at=?
            WHERE delivery_id=? AND owner=? AND attempt_count=? AND status='SENDING'
            """, receiptRef, clock.instant().toString(), deliveryId, owner, attemptNumber) == 1;
    }

    public boolean markRetry(String deliveryId, Instant nextAttemptAt, String lastError, String owner,
                             int attemptNumber) {
        return db.update("""
            UPDATE l2_outbox SET status='RETRY', next_attempt_at=?, lease_until=NULL, owner=NULL,
              last_error=?, updated_at=?
            WHERE delivery_id=? AND owner=? AND attempt_count=? AND status='SENDING'
            """, instant(nextAttemptAt), lastError, clock.instant().toString(), deliveryId, owner,
            attemptNumber) == 1;
    }

    /** 终态写回同样带 owner + attempt 围栏，过期领取者不能把 UNKNOWN 改成 ACCEPTED 或反过来。 */
    public boolean markTerminal(String deliveryId, DeliveryStatus status, String lastError, String owner,
                                int attemptNumber) {
        return db.update("""
            UPDATE l2_outbox SET status=?, lease_until=NULL, owner=NULL, last_error=?, updated_at=?
            WHERE delivery_id=? AND owner=? AND attempt_count=? AND status='SENDING'
            """, status.name(), lastError, clock.instant().toString(), deliveryId, owner, attemptNumber) == 1;
    }

    /** 待核对的未知投递：允许后续核对为 accepted/failed，UNKNOWN 不是不可更新的终态（S55～S57）。 */
    public List<DeliveryRow> unknown(int limit) {
        return db.query("""
            SELECT * FROM l2_outbox WHERE status='UNKNOWN' ORDER BY created_at LIMIT ?
            """, this::mapDelivery, limit);
    }

    /** 核对结论写回：只从 UNKNOWN 迁移，避免覆盖并发中的新结论。 */
    public boolean markReconciled(String deliveryId, DeliveryStatus status, String receiptRef,
                                  String lastError) {
        if (status != DeliveryStatus.ACCEPTED && status != DeliveryStatus.FAILED) {
            return false;
        }
        return db.update("""
            UPDATE l2_outbox SET status=?, receipt_ref=?, last_error=?, updated_at=?
            WHERE delivery_id=? AND status='UNKNOWN'
            """, status.name(), receiptRef, lastError, clock.instant().toString(), deliveryId) == 1;
    }

    public List<DeliveryRow> list(String statusFilter, int limit) {
        if (statusFilter == null) {
            return db.query("SELECT * FROM l2_outbox ORDER BY created_at DESC LIMIT ?", this::mapDelivery, limit);
        }
        return db.query("SELECT * FROM l2_outbox WHERE status=? ORDER BY created_at DESC LIMIT ?",
            this::mapDelivery, statusFilter, limit);
    }

    /** 发送前复验目的地版本仍有效（INV-36）。 */
    public boolean destinationRevisionCurrent(String destinationRef, long expectedRevision) {
        Long revision = db.queryOne("""
            SELECT destination_revision FROM l2_destinations WHERE destination_ref=?
            """, rs -> rs.getLong("destination_revision"), destinationRef);
        return revision != null && revision == expectedRevision;
    }

    private DeliveryRow mapDelivery(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DeliveryRow(Db.str(rs, "delivery_id"), Db.str(rs, "tenant_ref"), Db.str(rs, "workspace_ref"),
            Db.str(rs, "entry_ref"), Db.str(rs, "destination_ref"), rs.getLong("destination_revision"),
            Db.str(rs, "packet_id"), Db.str(rs, "delivery_json"),
            DeliveryStatus.valueOf(Db.str(rs, "status")), (int) rs.getLong("attempt_count"),
            Db.instant(rs, "next_attempt_at"), Db.instant(rs, "lease_until"), Db.str(rs, "owner"),
            Db.str(rs, "receipt_ref"), Db.str(rs, "last_error"));
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }
}
