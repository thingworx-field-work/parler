package com.thingworx.things.agent.transform.time;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.analysis.AnalysisEnvelope;
import com.thingworx.things.agent.analysis.AnalysisOperation;
import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.cache.TypedRow;
import com.thingworx.things.agent.cache.TypedTabularStream;
import com.thingworx.things.agent.join.U4OperationAdmission;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.transform.time.RollingOperator.WindowKind;

/** TQJ-5 ArtifactCache-backed G3 rolling mean. */
public final class RollingCacheRunner {

    public static final String ROUTE_ID = "u4.rolling";

    private RollingCacheRunner() {}

    public static SeriesRunResult run(
            String sourceCacheId,
            String timeColumn,
            String valueColumn,
            HalfOpenWindow window,
            WindowKind kind,
            int observationWindow,
            Duration durationWindow,
            int minSupport) throws Exception {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(kind, "kind");
        if (sourceCacheId == null || sourceCacheId.isBlank()) {
            throw new IllegalArgumentException("sourceCacheId required");
        }
        if (timeColumn == null || timeColumn.isBlank()) {
            throw new IllegalArgumentException("TIME_AXIS_MISSING");
        }
        if (!U4OperationAdmission.rollingEnabled()) {
            return SeriesRunResult.unavailable(U4OperationAdmission.ROLLING_UNAVAILABLE);
        }

        try (TypedTabularStream stream = TypedTabularStream.open(sourceCacheId, null, 0)) {
            List<TimePoint> points = G3SeriesSupport.inWindow(
                    TypedTimeSeries.extract(stream, timeColumn, valueColumn), window);
            List<BucketedValue> buckets = RollingOperator.rollingMean(
                    points, kind, observationWindow, durationWindow, minSupport);
            List<TypedRow> rows = G3SeriesSupport.bucketRows(buckets);
            boolean scanned = stream.inputsFullyScanned();
            SourceDescriptor parent = stream.sourceDescriptor();
            String findingId = G3SeriesSupport.publish(
                    G3SeriesSupport.BUCKET_COLUMNS, rows, parent, sourceCacheId, ROUTE_ID, scanned);
            AnalysisEnvelope envelope = G3SeriesSupport.envelopeSuccess(
                    AnalysisOperation.ROLLING,
                    ROUTE_ID,
                    sourceCacheId,
                    findingId,
                    DemoRollingAppProfile.PROFILE_DIGEST,
                    stream.sourceDescriptor() == null ? null : stream.sourceDescriptor().completenessStatus(),
                    scanned,
                    points.size(),
                    rows.size());
            return SeriesRunResult.ok(findingId, envelope, rows.size());
        }
    }
}
