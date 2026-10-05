package com.thingworx.things.agent.quality;

import java.time.Instant;
import java.util.Objects;

import com.thingworx.things.agent.analysis.AnalysisEnvelope;
import com.thingworx.things.agent.analysis.AnalysisEnvelopeBuilder;
import com.thingworx.things.agent.analysis.AnalysisMethodDescriptor;
import com.thingworx.things.agent.analysis.AnalysisOperation;
import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.cache.TypedTabularStream;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.join.U4OperationAdmission;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;
import com.thingworx.things.agent.transform.time.TypedTimeSeries;
import com.thingworx.things.agent.transform.time.TypedTimeSeries.SeriesExtract;

/**
 * TQJ-5 ArtifactCache-backed G6 quality assessment. Streams the source once into source-order
 * points; publishes no derived handle (assessment-only).
 */
public final class QualityCacheRunner {

    public static final String ROUTE_ID = "u4.quality";

    private QualityCacheRunner() {}

    public static QualityRunResult run(String sourceCacheId, String timeColumn, String valueColumn,
            HalfOpenWindow window, QualityProfile profile, Instant evaluationAnchor) throws Exception {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(evaluationAnchor, "evaluationAnchor");
        if (sourceCacheId == null || sourceCacheId.isBlank()) {
            throw new IllegalArgumentException("sourceCacheId required");
        }
        if (timeColumn == null || timeColumn.isBlank()) {
            throw new IllegalArgumentException("TIME_AXIS_MISSING");
        }
        if (!U4OperationAdmission.qualityEnabled()) {
            return QualityRunResult.unavailable(U4OperationAdmission.QUALITY_UNAVAILABLE);
        }

        try (TypedTabularStream stream = TypedTabularStream.open(sourceCacheId, null, 0)) {
            SeriesExtract extract = TypedTimeSeries.extractSourceOrder(stream, timeColumn, valueColumn);
            QualityAssessment assessment = TimeSeriesQualityEvaluator.evaluate(
                    extract.points(),
                    window,
                    profile,
                    evaluationAnchor,
                    extract.missingTimestampCount(),
                    0L);
            CompletenessStatus completeness = stream.sourceDescriptor() == null
                    || stream.sourceDescriptor().completenessStatus() == null
                            ? CompletenessStatus.UNKNOWN
                            : stream.sourceDescriptor().completenessStatus();
            if (!extract.inputsFullyScanned()) {
                completeness = CompletenessStatus.UNKNOWN;
            }
            AnalysisEnvelope envelope = envelope(sourceCacheId, assessment, completeness,
                    extract.inputsFullyScanned(), extract.points().size() + extract.missingTimestampCount());
            return QualityRunResult.ok(assessment, envelope);
        }
    }

    private static AnalysisEnvelope envelope(
            String sourceCacheId,
            QualityAssessment assessment,
            CompletenessStatus completeness,
            boolean inputsFullyScanned,
            long rowsRead) {
        CompletenessStatus c = completeness == null ? CompletenessStatus.UNKNOWN : completeness;
        EvidenceStatus status = (c != CompletenessStatus.COMPLETE || !inputsFullyScanned)
                ? EvidenceStatus.INSUFFICIENT_EVIDENCE
                : EvidenceStatus.SUCCESS;
        AnalysisEnvelopeBuilder b = AnalysisEnvelopeBuilder.create()
                .status(status)
                .operation(AnalysisOperation.QUALITY)
                .addSourceCacheId(sourceCacheId)
                .method(AnalysisMethodDescriptor.builder()
                        .id(ROUTE_ID)
                        .version("1")
                        .profileDigest(assessment.profileDigest())
                        .operation(AnalysisOperation.QUALITY)
                        .build())
                .completeness(c)
                .n(assessment.findings().size())
                .inputsFullyScanned(inputsFullyScanned)
                .rowsRead(rowsRead)
                .rowsOutput(0L);
        QualityEvidenceAttachment.attach(b, assessment);
        AnalysisEnvelope envelope = b.build();
        QualityEvidenceAttachment.requireBlockingPreserved(assessment, envelope.evidence().quality());
        return envelope;
    }

    /** Outcome of a cache-backed quality assessment. */
    public static final class QualityRunResult {
        private final QualityAssessment assessment;
        private final AnalysisEnvelope envelope;
        private final String unavailableReason;

        private QualityRunResult(QualityAssessment assessment, AnalysisEnvelope envelope,
                String unavailableReason) {
            this.assessment = assessment;
            this.envelope = envelope;
            this.unavailableReason = unavailableReason;
        }

        static QualityRunResult ok(QualityAssessment assessment, AnalysisEnvelope envelope) {
            return new QualityRunResult(assessment, envelope, null);
        }

        static QualityRunResult unavailable(String reason) {
            return new QualityRunResult(null, null, reason);
        }

        public boolean unavailable() {
            return unavailableReason != null;
        }

        public String unavailableReason() {
            return unavailableReason;
        }

        public QualityAssessment assessment() {
            return assessment;
        }

        public AnalysisEnvelope envelope() {
            return envelope;
        }

        public boolean mayPublish() {
            return false;
        }
    }
}
