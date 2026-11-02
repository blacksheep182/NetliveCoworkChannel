package com.netlivecowork.channel.l2.store;

import com.netlivecowork.channel.infra.Db;
import com.netlivecowork.channel.infra.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * L3 映射登记的持久待办：登记意图与规范化在同一事务落库，提交后才调用 L3；
 * 调用失败按退避重试，超限进入 FAILED（可管理端重试），不静默丢待办。
 */
public final class RegistrationRepository {

    public static final String KIND_INTERACTION = "INTERACTION";
    public static final String KIND_FILE = "FILE";

    public record Registration(String registrationRef, String kind, String targetRef, String localRef,
                               String backendRef, long revision, String payloadJson, String state,
                               int attempts, Instant nextAttemptAt, String lastError) {
    }

    private final Db db;
    private final Clock clock;

    public RegistrationRepository(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public void enqueue(String kind, String targetRef, String localRef, String backendRef, long revision,
                        String payloadJson) {
        String now = clock.instant().toString();
        db.update("""
            INSERT OR IGNORE INTO l2_backend_registrations(registration_ref, kind, target_ref, local_ref,
              backend_ref, revision, payload_json, state, attempts, next_attempt_at, created_at, updated_at)
            VALUES(?,?,?,?,?,?,?,'PENDING',0,?,?,?)
            """, Ids.next("reg"), kind, targetRef, localRef, backendRef, revision, payloadJson, now, now, now);
    }

    public List<Registration> pending(int limit) {
        return db.query("""
            SELECT * FROM l2_backend_registrations
            WHERE state='PENDING' AND (next_attempt_at IS NULL OR next_attempt_at<=?)
            ORDER BY created_at LIMIT ?
            """, this::map, clock.instant().toString(), limit);
    }

    /** 单 worker 领取，attempts 累加；只有条件 UPDATE 成功才执行外部调用。 */
    public Registration claim(String registrationRef) {
        boolean claimed = db.update("""
            UPDATE l2_backend_registrations SET state='SENDING', attempts=attempts+1, updated_at=?
            WHERE registration_ref=? AND state='PENDING'
            """, clock.instant().toString(), registrationRef) == 1;
        return claimed ? get(registrationRef) : null;
    }

    public void done(String registrationRef) {
        db.update("UPDATE l2_backend_registrations SET state='DONE', last_error=NULL, updated_at=? "
            + "WHERE registration_ref=?", clock.instant().toString(), registrationRef);
    }

    public void retry(String registrationRef, Instant nextAttemptAt, String error) {
        db.update("""
            UPDATE l2_backend_registrations SET state='PENDING', next_attempt_at=?, last_error=?, updated_at=?
            WHERE registration_ref=?
            """, nextAttemptAt.toString(), error, clock.instant().toString(), registrationRef);
    }

    public void fail(String registrationRef, String error) {
        db.update("UPDATE l2_backend_registrations SET state='FAILED', last_error=?, updated_at=? "
            + "WHERE registration_ref=?", error, clock.instant().toString(), registrationRef);
    }

    public Registration get(String registrationRef) {
        return db.queryOne("SELECT * FROM l2_backend_registrations WHERE registration_ref=?",
            this::map, registrationRef);
    }

    public List<Registration> list(String state, int limit) {
        if (state == null || state.isBlank()) {
            return db.query("SELECT * FROM l2_backend_registrations ORDER BY created_at DESC LIMIT ?",
                this::map, limit);
        }
        return db.query("SELECT * FROM l2_backend_registrations WHERE state=? ORDER BY created_at DESC LIMIT ?",
            this::map, state, limit);
    }

    private Registration map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String next = Db.str(rs, "next_attempt_at");
        return new Registration(Db.str(rs, "registration_ref"), Db.str(rs, "kind"), Db.str(rs, "target_ref"),
            Db.str(rs, "local_ref"), Db.str(rs, "backend_ref"), rs.getLong("revision"),
            Db.str(rs, "payload_json"), Db.str(rs, "state"), (int) rs.getLong("attempts"),
            next == null ? null : Instant.parse(next), Db.str(rs, "last_error"));
    }
}
