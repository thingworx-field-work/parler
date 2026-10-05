package com.thingworx.things.agent.transform.time;

import com.thingworx.things.agent.analysis.AnalysisEnvelope;
import com.thingworx.things.agent.source.SourceDescriptor;

/** Outcome of one measurement run. {@link #descriptor()} describes the published table, else the source. */
public final class MeasurementRunResult {

    private final String findingCacheId;
    private final AnalysisEnvelope envelope;
    private final SourceDescriptor descriptor;
    private final String unavailableReason;

    MeasurementRunResult(String findingCacheId, AnalysisEnvelope envelope, SourceDescriptor descriptor,
            String unavailableReason) {
        this.findingCacheId = findingCacheId;
        this.envelope = envelope;
        this.descriptor = descriptor;
        this.unavailableReason = unavailableReason;
    }

    static MeasurementRunResult unavailable(String reason) {
        return new MeasurementRunResult(null, null, null, reason);
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

    public SourceDescriptor descriptor() {
        return descriptor;
    }

    public boolean mayPublish() {
        return findingCacheId != null;
    }
}
