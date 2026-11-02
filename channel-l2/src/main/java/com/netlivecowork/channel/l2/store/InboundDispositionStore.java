package com.netlivecowork.channel.l2.store;

import com.netlivecowork.channel.contract.DispositionResult;
import com.netlivecowork.channel.contract.InboundDisposition;
import com.netlivecowork.channel.contract.port.InboundDispositionPort;
import com.netlivecowork.channel.infra.Db;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 入站确定性拒绝的权威处置表（S69～S72）：按 entry + 账号代次 + 源事件 ID 稳定保存，
 * 不随连接代次或 Adapter 版本重置；保留窗口覆盖平台重投与未确认批次。
 */
public final class InboundDispositionStore implements InboundDispositionPort {

    /** 保留窗口：覆盖平台最大重投与未确认批次的部署选择；超出边界按缺口核对（S72）。 */
    static final Duration RETENTION = Duration.ofDays(30);

    private final Db db;
    private final Clock clock;
    private final String entryRef;
    private final long accountBindingEpoch;

    public InboundDispositionStore(Db db, Clock clock, String entryRef, long accountBindingEpoch) {
        this.db = db;
        this.clock = clock;
        this.entryRef = entryRef;
        this.accountBindingEpoch = accountBindingEpoch;
    }

    private record Row(String contentDigest, String code, Instant recordedAt, Instant retainUntil, String state) {
    }

    @Override
    public CompletionStage<InboundDisposition> find(String sourceEventId) {
        return CompletableFuture.supplyAsync(() -> {
            Row row = row(sourceEventId);
            if (row == null) {
                return null;
            }
            Instant now = clock.instant();
            return new InboundDisposition(entryRef, accountBindingEpoch, sourceEventId, row.contentDigest(),
                row.code(), row.recordedAt(), row.retainUntil(), now.isAfter(row.retainUntil()),
                "GAP".equals(row.state()));
        });
    }

    @Override
    public CompletionStage<DispositionResult> recordRejected(String sourceEventId, String contentDigest,
                                                              String code) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return record(sourceEventId, contentDigest, code);
            } catch (RuntimeException e) {
                // 落盘失败必须显式返回 FAILED，调用方据此不确认平台游标（S69）。
                return DispositionResult.FAILED;
            }
        });
    }

    private DispositionResult record(String sourceEventId, String contentDigest, String code) {
        return db.tx(connection -> {
            Row existing = row(sourceEventId);
            if (existing != null) {
                if (existing.contentDigest() != null && existing.contentDigest().equals(contentDigest)) {
                    return DispositionResult.DUPLICATE;
                }
                return DispositionResult.CONFLICT;
            }
            Instant now = clock.instant();
            db.update("""
                INSERT INTO l2_inbound_dispositions(entry_ref, account_binding_epoch, source_event_id,
                  content_digest, code, state, recorded_at, retain_until) VALUES(?,?,?,?,?,?,?,?)
                """, entryRef, accountBindingEpoch, sourceEventId, contentDigest, code, "REJECTED",
                now.toString(), now.plus(RETENTION).toString());
            return DispositionResult.RECORDED;
        });
    }

    @Override
    public CompletionStage<Void> markGap(String sourceEventId, String reason) {
        return CompletableFuture.runAsync(() -> {
            Instant now = clock.instant();
            String code = reason == null || reason.isBlank() ? "GAP" : "GAP:" + reason;
            db.update("""
                INSERT INTO l2_inbound_dispositions(entry_ref, account_binding_epoch, source_event_id,
                  content_digest, code, state, recorded_at, retain_until) VALUES(?,?,?,?,?,'GAP',?,?)
                ON CONFLICT(entry_ref, account_binding_epoch, source_event_id) DO UPDATE SET
                  state='GAP', code=excluded.code, recorded_at=excluded.recorded_at,
                  retain_until=excluded.retain_until
                """, entryRef, accountBindingEpoch, sourceEventId, null, code, now.toString(),
                now.plus(RETENTION).toString());
        });
    }

    private Row row(String sourceEventId) {
        return db.queryOne("""
            SELECT content_digest, code, recorded_at, retain_until, state
            FROM l2_inbound_dispositions
            WHERE entry_ref=? AND account_binding_epoch=? AND source_event_id=?
            """, rs -> new Row(Db.str(rs, "content_digest"), Db.str(rs, "code"),
            Instant.parse(Db.str(rs, "recorded_at")), Instant.parse(Db.str(rs, "retain_until")),
            Db.str(rs, "state")), entryRef, accountBindingEpoch, sourceEventId);
    }

    /** 管理展示投影：最近 N 条，不作为保留或去重依据（S70）。 */
    public static List<InboundDisposition> recent(Db db, String entryRef, long epoch, int limit) {
        return db.query("""
            SELECT * FROM l2_inbound_dispositions
            WHERE entry_ref=? AND account_binding_epoch=?
            ORDER BY recorded_at DESC LIMIT ?
            """, rs -> new InboundDisposition(entryRef, epoch, Db.str(rs, "source_event_id"),
            Db.str(rs, "content_digest"), Db.str(rs, "code"), Instant.parse(Db.str(rs, "recorded_at")),
            Instant.parse(Db.str(rs, "retain_until")), false, "GAP".equals(Db.str(rs, "state"))),
            entryRef, epoch, Math.clamp(limit, 1, 200));
    }
}
