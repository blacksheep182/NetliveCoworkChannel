package com.netlivecowork.channel.l3;

import com.netlivecowork.channel.infra.Db;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 后端事件的持久接收与可靠转交队列（INV-31、64）。
 *
 * <p>去重键 = 完整来源命名空间（服务/会话/资源代次）+ 原始 eventId；同键同内容幂等，同键异内容隔离
 * 不覆盖。转交状态与任务执行事实分开：未确认事件持久保留，暂停也不删除。
 */
final class EventStore {

    private final Db db;
    private final Clock clock;
    private final L3Settings settings;

    EventStore(Db db, Clock clock, L3Settings settings) {
        this.db = db;
        this.clock = clock;
        this.settings = settings;
        db.ddl("""
            CREATE TABLE IF NOT EXISTS backend_events(
              backend_event_ref TEXT PRIMARY KEY,
              resource_ref TEXT NOT NULL,
              source_event_id TEXT NOT NULL,
              target_ref TEXT NOT NULL,
              operation_ref TEXT,
              request_key TEXT,
              event_type TEXT NOT NULL,
              occurred_at TEXT NOT NULL,
              content_digest TEXT NOT NULL,
              verified_json TEXT NOT NULL,
              forward_status TEXT NOT NULL,
              attempts INTEGER NOT NULL,
              next_attempt_at INTEGER,
              lease_until INTEGER,
              owner TEXT,
              last_error TEXT,
              received_at TEXT NOT NULL,
              acked_at TEXT,
              updated_at INTEGER NOT NULL,
              UNIQUE(resource_ref, source_event_id))
            """,
            "CREATE INDEX IF NOT EXISTS backend_events_queue ON backend_events(forward_status, next_attempt_at)",
            """
            CREATE TABLE IF NOT EXISTS backend_event_conflicts(
              conflict_ref TEXT PRIMARY KEY,
              backend_event_ref TEXT NOT NULL,
              resource_ref TEXT NOT NULL,
              source_event_id TEXT NOT NULL,
              content_digest TEXT NOT NULL,
              body TEXT NOT NULL,
              created_at TEXT NOT NULL)
            """);
    }

    /** 待接收事件草稿：verifiedJson 是 L3 已验证事件的规范序列化。 */
    record EventDraft(String backendEventRef, String resourceRef, String sourceEventId, String targetRef,
                      String operationRef, String requestKey, String eventType, Instant occurredAt,
                      String contentDigest, String verifiedJson) {
    }

    enum InsertResult {
        ACCEPTED, DUPLICATE, CONFLICT
    }

    /** 去重与登记在同一事务内完成：提交成功后才可确认 Agent（INV-31）。 */
    InsertResult insert(EventDraft draft) {
        return db.tx(connection -> {
            Existing existing = db.queryOne("""
                SELECT backend_event_ref, content_digest FROM backend_events
                WHERE resource_ref=? AND source_event_id=?
                """, rs -> new Existing(Db.str(rs, "backend_event_ref"), Db.str(rs, "content_digest")),
                draft.resourceRef(), draft.sourceEventId());
            if (existing != null) {
                if (existing.contentDigest().equals(draft.contentDigest())) {
                    return InsertResult.DUPLICATE;
                }
                // 同键异内容：隔离冲突变体，不覆盖已固定的业务事实，也不阻断原事件的转交。
                db.update("""
                    INSERT INTO backend_event_conflicts(conflict_ref, backend_event_ref, resource_ref,
                      source_event_id, content_digest, body, created_at)
                    VALUES (?,?,?,?,?,?,?)
                    """,
                    com.netlivecowork.channel.infra.Ids.next("event-conflict"), existing.backendEventRef(),
                    draft.resourceRef(), draft.sourceEventId(), draft.contentDigest(), draft.verifiedJson(),
                    clock.instant());
                return InsertResult.CONFLICT;
            }
            db.update("""
                INSERT INTO backend_events(backend_event_ref, resource_ref, source_event_id, target_ref,
                  operation_ref, request_key, event_type, occurred_at, content_digest, verified_json,
                  forward_status, attempts, next_attempt_at, received_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,'pending',0,?,?,?)
                """,
                draft.backendEventRef(), draft.resourceRef(), draft.sourceEventId(), draft.targetRef(),
                draft.operationRef(), draft.requestKey(), draft.eventType(), draft.occurredAt(),
                draft.contentDigest(), draft.verifiedJson(), clock.millis(), clock.instant(), clock.millis());
            return InsertResult.ACCEPTED;
        });
    }

    record Claimed(String backendEventRef, String verifiedJson, int attempts) {
    }

    /**
     * 短事务领取并设置租约；网络转交在事务之外执行（INV-20）。
     * 领取范围包含“租约已过期的 sending”：进程在领取后、确认前退出的事件能被重新领取，
     * 不会永久卡死。只有条件 UPDATE 成功（rows==1）才视为领取成功，保证单事件单持有者。
     */
    List<Claimed> claim(int limit, String owner) {
        long now = clock.millis();
        long leaseUntil = now + settings.forwardLeaseSeconds() * 1000L;
        return db.tx(connection -> {
            List<Claimed> candidates = db.query("""
                SELECT backend_event_ref, verified_json, attempts FROM backend_events
                WHERE (forward_status='pending'
                       AND (next_attempt_at IS NULL OR next_attempt_at<=?)
                       AND (lease_until IS NULL OR lease_until<=?))
                   OR (forward_status='sending' AND (lease_until IS NULL OR lease_until<=?))
                ORDER BY received_at, backend_event_ref LIMIT ?
                """,
                rs -> new Claimed(Db.str(rs, "backend_event_ref"), Db.str(rs, "verified_json"),
                    rs.getInt("attempts")), now, now, now, limit);
            List<Claimed> claimed = new java.util.ArrayList<>();
            for (Claimed row : candidates) {
                int rows = db.update("""
                    UPDATE backend_events SET forward_status='sending', owner=?, lease_until=?,
                      attempts=attempts+1, updated_at=?
                    WHERE backend_event_ref=?
                      AND ((forward_status='pending'
                            AND (next_attempt_at IS NULL OR next_attempt_at<=?)
                            AND (lease_until IS NULL OR lease_until<=?))
                        OR (forward_status='sending' AND (lease_until IS NULL OR lease_until<=?)))
                    """, owner, leaseUntil, now, row.backendEventRef(), now, now, now);
                if (rows == 1) {
                    claimed.add(row);
                }
            }
            return claimed;
        });
    }

    /** 确认送达：必须仍是本次持有者，过期领取者的确认不得覆盖新状态（INV-49、INV-64）。 */
    void ack(String backendEventRef, String owner) {
        db.update("""
            UPDATE backend_events SET forward_status='acked', acked_at=?, owner=NULL, lease_until=NULL,
              last_error=NULL, updated_at=?
            WHERE backend_event_ref=? AND forward_status='sending' AND owner=?
            """, clock.instant(), clock.millis(), backendEventRef, owner);
    }

    /**
     * 转交失败：按指数退避排下次尝试，达到上限转 paused 并保留事件与原因；
     * 不静默删除未确认事件（INV-64）。写回同样带 owner 围栏。
     */
    void fail(Claimed row, String owner, String message) {
        int attempts = row.attempts() + 1;
        String error = message == null || message.isBlank() ? "event intake rejected the event" : message;
        if (attempts >= settings.forwardMaxAttempts()) {
            db.update("""
                UPDATE backend_events SET forward_status='paused', owner=NULL, lease_until=NULL, last_error=?,
                  updated_at=? WHERE backend_event_ref=? AND forward_status='sending' AND owner=?
                """, error, clock.millis(), row.backendEventRef(), owner);
            return;
        }
        db.update("""
            UPDATE backend_events SET forward_status='pending', owner=NULL, lease_until=NULL, last_error=?,
              next_attempt_at=?, updated_at=? WHERE backend_event_ref=? AND forward_status='sending' AND owner=?
            """, error, clock.millis() + backoffMs(attempts), clock.millis(), row.backendEventRef(), owner);
    }

    private long backoffMs(int attempts) {
        long initial = settings.forwardInitialBackoffMs();
        long max = settings.forwardMaxBackoffMs();
        if (initial <= 0) {
            return 0;
        }
        long shifted = initial * (1L << Math.min(Math.max(attempts - 1, 0), 20));
        long base = Math.min(max, shifted);
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, base / 4) + 1);
        return Math.min(max, base + jitter);
    }

    private record Existing(String backendEventRef, String contentDigest) {
    }
}
