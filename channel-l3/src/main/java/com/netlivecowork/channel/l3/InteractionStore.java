package com.netlivecowork.channel.l3;

import com.netlivecowork.channel.infra.Db;
import java.time.Clock;

/**
 * 内部 interactionRef → 后端事项身份映射（INV-59、61）。L2 签发内部引用，后端只认自己的
 * backendInteractionId，两侧由本表衔接；revision 用于拒绝过期动作。
 */
final class InteractionStore {

    private final Db db;
    private final Clock clock;
    private final BackendRegistry registry;

    InteractionStore(Db db, Clock clock, BackendRegistry registry) {
        this.db = db;
        this.clock = clock;
        this.registry = registry;
        db.ddl("""
            CREATE TABLE IF NOT EXISTS interactions(
              interaction_ref TEXT PRIMARY KEY,
              backend_interaction_id TEXT NOT NULL,
              revision INTEGER NOT NULL,
              target_ref TEXT NOT NULL,
              service_ref TEXT NOT NULL,
              session_id TEXT NOT NULL,
              updated_at TEXT NOT NULL)
            """);
    }

    record Mapping(String interactionRef, String backendInteractionId, long revision, String targetRef,
                   String serviceRef, String sessionId) {
    }

    /** 固定题目/事项映射；同一 interactionRef 只指向确定后端事项与 revision。 */
    void register(String interactionRef, String targetRef, String backendInteractionId, long revision) {
        if (interactionRef == null || interactionRef.isBlank() || backendInteractionId == null
            || backendInteractionId.isBlank() || revision < 1) {
            throw new IllegalArgumentException(
                "interaction registration requires interactionRef, backendInteractionId and revision >= 1");
        }
        TargetRegistration target = registry.target(targetRef);
        if (target == null) {
            throw new IllegalArgumentException("interaction registration requires a registered target: " + targetRef);
        }
        db.update("""
            INSERT INTO interactions(interaction_ref, backend_interaction_id, revision, target_ref, service_ref,
                                     session_id, updated_at)
            VALUES (?,?,?,?,?,?,?)
            ON CONFLICT(interaction_ref) DO UPDATE SET backend_interaction_id=excluded.backend_interaction_id,
              revision=excluded.revision, target_ref=excluded.target_ref, service_ref=excluded.service_ref,
              session_id=excluded.session_id, updated_at=excluded.updated_at
            """,
            interactionRef, backendInteractionId, revision, target.targetRef(), target.serviceRef(),
            target.sessionId(), clock.instant());
    }

    Mapping find(String interactionRef) {
        if (interactionRef == null || interactionRef.isBlank()) {
            return null;
        }
        return db.queryOne("SELECT * FROM interactions WHERE interaction_ref=?",
            rs -> new Mapping(Db.str(rs, "interaction_ref"), Db.str(rs, "backend_interaction_id"),
                rs.getLong("revision"), Db.str(rs, "target_ref"), Db.str(rs, "service_ref"),
                Db.str(rs, "session_id")), interactionRef);
    }
}
