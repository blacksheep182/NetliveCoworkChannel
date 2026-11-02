package com.netlivecowork.channel.l2.store;

import com.netlivecowork.channel.infra.Db;
import java.time.Clock;
import java.util.List;

/** 已验证后端事件的持久接收与规范化状态（INV-28、INV-31）。 */
public final class EventRepository {

    private final Db db;
    private final Clock clock;

    public EventRepository(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public enum EventState {
        /** 已持久接收但业务关联不足，等待核对（不以“最近任务”猜测，INV-30）。 */
        PENDING_RELATION,
        /** 已被某个 worker 领取并正在生成规范事件包（防止并发重复生成，INV-31）。 */
        PACKETIZING,
        PACKETIZED,
        /** 晚到旧状态，已生成规范事件但按版本未采用。 */
        SKIPPED_STALE,
        QUARANTINED
    }

    public record EventRow(String resourceRef, String sourceEventId, String backendEventRef,
                           String payloadDigest, String eventJson, String packetId, String packetJson,
                           EventState state, String lastError) {
    }

    public EventRow find(String resourceRef, String sourceEventId) {
        return db.queryOne("""
            SELECT * FROM l2_backend_events WHERE resource_ref=? AND source_event_id=?
            """, this::map, resourceRef, sourceEventId);
    }

    /** 插入接收记录；已存在返回 false，由调用方比较摘要决定幂等或冲突。 */
    public boolean insert(EventRow row) {
        String now = clock.instant().toString();
        int rows = db.update("""
            INSERT OR IGNORE INTO l2_backend_events(resource_ref, source_event_id, backend_event_ref,
              payload_digest, event_json, packet_id, packet_json, state, last_error, created_at, updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """, row.resourceRef(), row.sourceEventId(), row.backendEventRef(), row.payloadDigest(),
            row.eventJson(), row.packetId(), row.packetJson(), row.state().name(), row.lastError(), now, now);
        return rows == 1;
    }

    public void markState(String resourceRef, String sourceEventId, EventState state, String packetId,
                          String packetJson, String lastError) {
        db.update("""
            UPDATE l2_backend_events SET state=?, packet_id=?, packet_json=?, last_error=?, updated_at=?
            WHERE resource_ref=? AND source_event_id=?
            """, state.name(), packetId, packetJson, lastError, clock.instant().toString(),
            resourceRef, sourceEventId);
    }

    public List<EventRow> pendingRelation(int limit) {
        // 领取过期未完成的 PACKETIZING：进程在生成规范事件包时退出后可恢复，且不会并发双生成。
        return db.query("""
            SELECT * FROM l2_backend_events
            WHERE state='PENDING_RELATION' OR (state='PACKETIZING' AND updated_at<?)
            ORDER BY created_at LIMIT ?
            """, this::map, clock.instant().minusSeconds(60).toString(), limit);
    }

    /** 单 worker 领取规范化：只有条件 UPDATE 成功才生成事件包与投递计划（INV-31）。 */
    public boolean claimPacketize(String resourceRef, String sourceEventId) {
        String now = clock.instant().toString();
        String stale = clock.instant().minusSeconds(60).toString();
        return db.update("""
            UPDATE l2_backend_events SET state='PACKETIZING', updated_at=?
            WHERE resource_ref=? AND source_event_id=?
              AND (state='PENDING_RELATION' OR (state='PACKETIZING' AND updated_at<?))
            """, now, resourceRef, sourceEventId, stale) == 1;
    }

    public EventRow eventByPacket(String packetId) {
        return db.queryOne("SELECT * FROM l2_backend_events WHERE packet_id=?", this::map, packetId);
    }

    private EventRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EventRow(Db.str(rs, "resource_ref"), Db.str(rs, "source_event_id"),
            Db.str(rs, "backend_event_ref"), Db.str(rs, "payload_digest"), Db.str(rs, "event_json"),
            Db.str(rs, "packet_id"), Db.str(rs, "packet_json"),
            EventState.valueOf(Db.str(rs, "state")), Db.str(rs, "last_error"));
    }
}
