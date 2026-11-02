package com.netlivecowork.channel.l1.support;

import com.netlivecowork.channel.contract.DispositionResult;
import com.netlivecowork.channel.contract.InboundDisposition;
import com.netlivecowork.channel.contract.port.InboundDispositionPort;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/** 入站处置设施桩：可注入写失败、过期记录与既有处置，验证 S69～S72 的分类与去重。 */
public final class TestDispositions implements InboundDispositionPort {

    public final Map<String, InboundDisposition> records = new ConcurrentHashMap<>();
    public volatile boolean failWrites;

    private final String entryRef;
    private final long epoch;

    public TestDispositions(String entryRef, long epoch) {
        this.entryRef = entryRef;
        this.epoch = epoch;
    }

    public void seed(String sourceEventId, String contentDigest, boolean expired, boolean gap) {
        Instant now = Instant.parse("2026-10-27T00:00:00Z");
        records.put(sourceEventId, new InboundDisposition(entryRef, epoch, sourceEventId, contentDigest,
            gap ? "GAP:retention" : "PERMISSION_DENIED", now,
            expired ? now.minusSeconds(60) : now.plusSeconds(86_400), expired, gap));
    }

    @Override
    public CompletionStage<InboundDisposition> find(String sourceEventId) {
        return CompletableFuture.completedFuture(records.get(sourceEventId));
    }

    @Override
    public CompletionStage<DispositionResult> recordRejected(String sourceEventId, String contentDigest,
                                                              String code) {
        if (failWrites) {
            return CompletableFuture.completedFuture(DispositionResult.FAILED);
        }
        InboundDisposition existing = records.get(sourceEventId);
        if (existing != null) {
            return CompletableFuture.completedFuture(existing.sameContent(contentDigest)
                ? DispositionResult.DUPLICATE : DispositionResult.CONFLICT);
        }
        Instant now = Instant.parse("2026-10-27T00:00:00Z");
        records.put(sourceEventId, new InboundDisposition(entryRef, epoch, sourceEventId, contentDigest, code,
            now, now.plusSeconds(86_400), false, false));
        return CompletableFuture.completedFuture(DispositionResult.RECORDED);
    }

    @Override
    public CompletionStage<Void> markGap(String sourceEventId, String reason) {
        Instant now = Instant.parse("2026-10-27T00:00:00Z");
        InboundDisposition existing = records.get(sourceEventId);
        records.put(sourceEventId, new InboundDisposition(entryRef, epoch, sourceEventId,
            existing == null ? null : existing.contentDigest(), "GAP:" + reason, now,
            now.plusSeconds(86_400), false, true));
        return CompletableFuture.completedFuture(null);
    }
}
