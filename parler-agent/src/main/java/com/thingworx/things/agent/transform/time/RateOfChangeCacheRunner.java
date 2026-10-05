package com.thingworx.things.agent.transform.time;

import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.analysis.AnalysisEnvelope;
import com.thingworx.things.agent.analysis.AnalysisOperation;
import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.cache.TypedRow;
import com.thingworx.things.agent.cache.TypedTabularStream;
import com.thingworx.things.agent.join.U4OperationAdmission;
import com.thingworx.things.agent.source.SourceDescriptor;

/** TQJ-5 ArtifactCache-backed G3 rate-of-change. */
public final class RateOfChangeCacheRunner {

    public static final String ROUTE_ID = "u4.rate_of_change";
    public static final String PROFILE_DIGEST = "demo-rate-of-change-v1";

    private RateOfChangeCacheRunner() {}

    public static SeriesRunResult run(
            String sourceCacheId,
            String timeColumn,
            String valueColumn,
            HalfOpenWindow window) throws Exception {
        Objects.requireNonNull(window, "window");
        if (sourceCacheId == null || sourceCacheId.isBlank()) {
            throw new IllegalArgumentException("sourceCacheId required");
        }
        if (timeColumn == null || timeColumn.isBlank()) {
            throw new IllegalArgumentException("TIME_AXIS_MISSING");
        }
        if (!U4OperationAdmission.rateOfChangeEnabled()) {
            return SeriesRunResult.unavailable(U4OperationAdmission.RATE_OF_CHANGE_UNAVAILABLE);
        }

        try (TypedTabularStream stream = TypedTabularStream.open(sourceCacheId, null, 0)) {
            List<TimePoint> points = G3SeriesSupport.inWindow(
                    TypedTimeSeries.extract(stream, timeColumn, valueColumn), window);
            List<RateOfChange.Sample> samples = RateOfChange.compute(points);
            List<TypedRow> rows = G3SeriesSupport.rateRows(samples);
            boolean scanned = stream.inputsFullyScanned();
            SourceDescriptor parent = stream.sourceDescriptor();
            String findingId = G3SeriesSupport.publish(
                    G3SeriesSupport.RATE_COLUMNS, rows, parent, sourceCacheId, ROUTE_ID, scanned);
            AnalysisEnvelope envelope = G3SeriesSupport.envelopeSuccess(
                    AnalysisOperation.RATE_OF_CHANGE,
                    ROUTE_ID,
                    sourceCacheId,
                    findingId,
                    PROFILE_DIGEST,
                    parent == null ? null : parent.completenessStatus(),
                    scanned,
                    points.size(),
                    rows.size());
            return SeriesRunResult.ok(findingId, envelope, rows.size());
        }
    }
}
