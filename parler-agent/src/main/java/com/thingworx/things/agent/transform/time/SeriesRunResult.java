package com.thingworx.things.agent.transform.time;

import com.thingworx.things.agent.analysis.AnalysisEnvelope;

/** Shared outcome for G3 cache runners that may publish a derived table. */
public final class SeriesRunResult {

    private final String findingCacheId;
    private final AnalysisEnvelope envelope;
    private final String unavailableReason;
    private final long outputRows;

    private SeriesRunResult(String findingCacheId, AnalysisEnvelope envelope,
            String unavailableReason, long outputRows) {
        this.findingCacheId = findingCacheId;
        this.envelope = envelope;
        this.unavailableReason = unavailableReason;
        this.outputRows = outputRows;
    }

    public static SeriesRunResult ok(String findingCacheId, AnalysisEnvelope envelope, long outputRows) {
        return new SeriesRunResult(findingCacheId, envelope, null, outputRows);
    }

    public static SeriesRunResult unavailable(String reason) {
        return new SeriesRunResult(null, null, reason, 0L);
    }

    public boolean unavailable() {
        return unavailableReason != null;
    }

    public String unavailableReason() {
        return unavailableReason;
    }

    public String findingCacheId() {
        return findingCacheId;
    }

    public AnalysisEnvelope envelope() {
        return envelope;
    }

    public boolean mayPublish() {
        return findingCacheId != null;
    }

    public long outputRows() {
        return outputRows;
    }
}
