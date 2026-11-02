package com.netlivecowork.channel.l3;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.netlivecowork.channel.contract.EventType;
import com.netlivecowork.channel.contract.SessionWatchRequest;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.infra.Db;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 会话级回传登记（INV-65）。按登记请求键幂等：同键同参数返回原登记，同键不同参数拒绝，
 * 不重复建立后端观察。起点/恢复边界以后端返回为准，不用本地时间替代。
 */
final class SessionWatchStore {

    private final Db db;
    private final Clock clock;

    SessionWatchStore(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
        db.ddl("""
            CREATE TABLE IF NOT EXISTS session_watches(
              request_key TEXT PRIMARY KEY,
              target_ref TEXT NOT NULL,
              service_ref TEXT NOT NULL,
              session_id TEXT NOT NULL,
              sink_ref TEXT NOT NULL,
              event_types TEXT NOT NULL,
              resume_cursor TEXT,
              watch_ref TEXT,
              start_boundary TEXT,
              status TEXT NOT NULL,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL)
            """);
    }

    record Row(String requestKey, String targetRef, String serviceRef, String sessionId, String sinkRef,
               List<String> eventTypes, String resumeCursor, String watchRef, String startBoundary,
               String status) {

        boolean sameParameters(SessionWatchRequest request, String sessionId) {
            return targetRef.equals(request.targetRef())
                && sessionId.equals(this.sessionId)
                && sinkRef.equals(request.sinkRef())
                && eventTypes.equals(wireTypes(request.eventTypes()))
                && (resumeCursor == null ? request.resumeCursor() == null : resumeCursor.equals(request.resumeCursor()));
        }

        static List<String> wireTypes(Set<EventType> types) {
            List<String> wires = new ArrayList<>();
            for (EventType type : types) {
                wires.add(type.wire());
            }
            wires.sort(String::compareTo);
            return wires;
        }
    }

    Row find(String requestKey) {
        return db.queryOne("SELECT * FROM session_watches WHERE request_key=?", SessionWatchStore::map, requestKey);
    }

    Row findByWatchRef(String watchRef) {
        if (watchRef == null || watchRef.isBlank()) {
            return null;
        }
        return db.queryOne("SELECT * FROM session_watches WHERE watch_ref=?", SessionWatchStore::map, watchRef);
    }

    void savePending(SessionWatchRequest request, String targetRef, String serviceRef, String sessionId) {
        db.update("""
            INSERT OR IGNORE INTO session_watches(request_key, target_ref, service_ref, session_id, sink_ref,
              event_types, resume_cursor, watch_ref, start_boundary, status, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,NULL,NULL,'pending',?,?)
            """,
            request.requestKey(), targetRef, serviceRef, sessionId, request.sinkRef(),
            Json.write(array(request.eventTypes())), request.resumeCursor(), clock.instant(), clock.instant());
    }

    void recordActive(String requestKey, String watchRef, String startBoundary) {
        db.update("""
            UPDATE session_watches SET watch_ref=?, start_boundary=?, status='active', updated_at=?
            WHERE request_key=?
            """, watchRef, startBoundary, clock.instant(), requestKey);
    }

    void recordUnsupported(String requestKey) {
        db.update("UPDATE session_watches SET status='unsupported', updated_at=? WHERE request_key=?",
            clock.instant(), requestKey);
    }

    void remove(String requestKey) {
        db.update("DELETE FROM session_watches WHERE request_key=?", requestKey);
    }

    private static ArrayNode array(Set<EventType> types) {
        Set<String> wires = new LinkedHashSet<>(Row.wireTypes(types));
        ArrayNode array = Json.arr();
        wires.forEach(array::add);
        return array;
    }

    private static Row map(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<String> types = new ArrayList<>();
        String raw = Db.str(rs, "event_types");
        if (raw != null && !raw.isBlank()) {
            for (var item : Json.read(raw)) {
                types.add(item.asText());
            }
        }
        return new Row(Db.str(rs, "request_key"), Db.str(rs, "target_ref"), Db.str(rs, "service_ref"),
            Db.str(rs, "session_id"), Db.str(rs, "sink_ref"), List.copyOf(types), Db.str(rs, "resume_cursor"),
            Db.str(rs, "watch_ref"), Db.str(rs, "start_boundary"), Db.str(rs, "status"));
    }
}
