package com.thingworx.things.agent.transform.time;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.analysis.AnalysisEnvelope;
import com.thingworx.things.agent.analysis.AnalysisEnvelopeBuilder;
import com.thingworx.things.agent.analysis.AnalysisMethodDescriptor;
import com.thingworx.things.agent.analysis.AnalysisOperation;
import com.thingworx.things.agent.analysis.DerivedArtifactLineage;
import com.thingworx.things.agent.analysis.DerivedTabularPublisher;
import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.cache.TypedCell;
import com.thingworx.things.agent.cache.TypedColumn;
import com.thingworx.things.agent.cache.TypedRow;
import com.thingworx.things.agent.cache.TypedTabularStream;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.join.U4OperationAdmission;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;

/**
 * TQJ-5 ArtifactCache-backed G3 resample. Streams source → TimePoints → buckets → derived table.
 */
public final class ResampleCacheRunner {

    public static final String ROUTE_ID = "u4.resample";

    private static final List<TypedColumn> OUT_COLUMNS = List.of(
            new TypedColumn("bucketStart", BaseTypes.DATETIME),
            new TypedColumn("bucketEnd", BaseTypes.DATETIME),
            new TypedColumn("value", BaseTypes.NUMBER),
            new TypedColumn("synthesized", BaseTypes.BOOLEAN),
            new TypedColumn("support", BaseTypes.INTEGER));

    private ResampleCacheRunner() {}

    public static ResampleRunResult run(
            String sourceCacheId,
            String timeColumn,
            String valueColumn,
            HalfOpenWindow window,
            Aggregation aggregation,
            MissingPolicy missingPolicy,
            String profileDigest) throws Exception {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(aggregation, "aggregation");
        if (sourceCacheId == null || sourceCacheId.isBlank()) {
            throw new IllegalArgumentException("sourceCacheId required");
        }
        if (timeColumn == null || timeColumn.isBlank()) {
            throw new IllegalArgumentException("TIME_AXIS_MISSING");
        }
        if (!U4OperationAdmission.resampleEnabled()) {
            return ResampleRunResult.unavailable(U4OperationAdmission.RESAMPLE_UNAVAILABLE);
        }

        MissingPolicy policy = missingPolicy == null ? DemoResampleAppProfile.missingPolicy() : missingPolicy;
        String digest = profileDigest == null || profileDigest.isBlank()
                ? DemoResampleAppProfile.PROFILE_DIGEST
                : profileDigest;

        try (TypedTabularStream stream = TypedTabularStream.open(sourceCacheId, null, 0)) {
            List<TimePoint> points = TypedTimeSeries.extract(stream, timeColumn, valueColumn);
            BucketAssigner assigner = DemoResampleAppProfile.assigner(window.startInclusive());
            List<BucketedValue> buckets = Resampler.resample(points, window, assigner, aggregation, policy);
            List<TypedRow> rows = toRows(buckets);
            boolean inputsFullyScanned = stream.inputsFullyScanned();
            SourceDescriptor parent = stream.sourceDescriptor();
            InfoTable table = DerivedTabularPublisher.toInfoTable(OUT_COLUMNS, rows);
            // window+policy proven for demo fixed-duration profile
            SourceDescriptor derived = DerivedArtifactLineage.forTransform(
                    parent, sourceCacheId, table, ROUTE_ID, inputsFullyScanned, true);
            String findingId = DerivedTabularPublisher.publish(OUT_COLUMNS, rows, derived);
            AnalysisEnvelope envelope = envelopeSuccess(
                    sourceCacheId, findingId, digest, derived.completenessStatus(),
                    inputsFullyScanned, points.size(), rows.size());
            return ResampleRunResult.ok(findingId, envelope, rows.size());
        }
    }

    private static List<TypedRow> toRows(List<BucketedValue> buckets) {
        List<TypedRow> rows = new ArrayList<>(buckets.size());
        long ord = 0L;
        for (BucketedValue b : buckets) {
            List<TypedCell> cells = List.of(
                    TypedCell.ofDatetime(b.bucketStartInclusive()),
                    TypedCell.ofDatetime(b.bucketEndExclusive()),
                    b.value() == null ? TypedCell.ofNull() : TypedCell.ofNumber(b.value()),
                    TypedCell.ofBoolean(b.synthesized()),
                    TypedCell.ofNumber(b.support()));
            rows.add(new TypedRow(ord++, cells));
        }
        return rows;
    }

    private static AnalysisEnvelope envelopeSuccess(
            String sourceCacheId,
            String findingId,
            String profileDigest,
            CompletenessStatus completeness,
            boolean inputsFullyScanned,
            long rowsRead,
            long rowsOut) {
        CompletenessStatus c = completeness == null ? CompletenessStatus.UNKNOWN : completeness;
        EvidenceStatus status;
        if (c != CompletenessStatus.COMPLETE || !inputsFullyScanned) {
            status = EvidenceStatus.INSUFFICIENT_EVIDENCE;
        } else if (rowsOut <= 0L) {
            status = EvidenceStatus.NO_FINDING;
        } else {
            status = EvidenceStatus.SUCCESS;
        }
        return AnalysisEnvelopeBuilder.create()
                .status(status)
                .operation(AnalysisOperation.RESAMPLE)
                .addSourceCacheId(sourceCacheId)
                .findingCacheId(findingId)
                .method(AnalysisMethodDescriptor.builder()
                        .id(ROUTE_ID)
                        .version("1")
                        .profileDigest(profileDigest)
                        .operation(AnalysisOperation.RESAMPLE)
                        .build())
                .completeness(c)
                .n(rowsOut)
                .inputsFullyScanned(inputsFullyScanned)
                .rowsRead(rowsRead)
                .rowsOutput(rowsOut)
                .build();
    }

    /** Outcome of a cache-backed resample. */
    public static final class ResampleRunResult {
        private final String findingCacheId;
        private final AnalysisEnvelope envelope;
        private final String unavailableReason;
        private final long outputRows;

        private ResampleRunResult(String findingCacheId, AnalysisEnvelope envelope,
                String unavailableReason, long outputRows) {
            this.findingCacheId = findingCacheId;
            this.envelope = envelope;
            this.unavailableReason = unavailableReason;
            this.outputRows = outputRows;
        }

        static ResampleRunResult ok(String findingCacheId, AnalysisEnvelope envelope, long outputRows) {
            return new ResampleRunResult(findingCacheId, envelope, null, outputRows);
        }

        static ResampleRunResult unavailable(String reason) {
            return new ResampleRunResult(null, null, reason, 0L);
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
}
