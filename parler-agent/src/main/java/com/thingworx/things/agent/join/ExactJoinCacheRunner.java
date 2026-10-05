package com.thingworx.things.agent.join;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.analysis.AnalysisEnvelope;
import com.thingworx.things.agent.analysis.AnalysisEnvelopeBuilder;
import com.thingworx.things.agent.analysis.AnalysisMethodDescriptor;
import com.thingworx.things.agent.analysis.AnalysisOperation;
import com.thingworx.things.agent.analysis.DerivedArtifactLineage;
import com.thingworx.things.agent.analysis.DerivedTabularPublisher;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.cache.TypedRow;
import com.thingworx.things.agent.cache.TypedTabularStream;
import com.thingworx.things.agent.cache.TypedTabularStreamIterable;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;
import com.thingworx.types.InfoTable;

/**
 * TQJ-5 ArtifactCache-backed exact join. Materializes only the build side (bounded by
 * {@link ExactJoinConfig#maxBuildRows()}); streams the probe via {@link TypedTabularStreamIterable}
 * without a second row index. Failed joins publish no derived handle (B5).
 */
public final class ExactJoinCacheRunner {

    public static final String ROUTE_ID = "u4.exact_join";

    private ExactJoinCacheRunner() {}

    public static ExactJoinRunResult run(String leftCacheId, String rightCacheId, ExactJoinConfig config)
            throws Exception {
        Objects.requireNonNull(config, "config");
        if (leftCacheId == null || leftCacheId.isBlank() || rightCacheId == null || rightCacheId.isBlank()) {
            throw new IllegalArgumentException("leftCacheId and rightCacheId required");
        }
        if (!U4OperationAdmission.exactJoinEnabled()) {
            return ExactJoinRunResult.unavailable(U4OperationAdmission.EXACT_JOIN_UNAVAILABLE);
        }

        try (TypedTabularStream left = TypedTabularStream.open(leftCacheId, null, 0);
                TypedTabularStream right = TypedTabularStream.open(rightCacheId, null, 0)) {
            boolean buildLeft = config.buildSide() == BuildSide.LEFT;
            TypedTabularStream buildStream = buildLeft ? left : right;
            TypedTabularStream probeStream = buildLeft ? right : left;

            MaterializeResult build = materializeBounded(buildStream, config.maxBuildRows());
            if (build.budgetExceeded) {
                ExactJoinResult failed = ExactJoinResult.builder()
                        .reason(ExactJoinReason.BUDGET_EXCEEDED)
                        .leftRowsRead(buildLeft ? build.rows.size() : 0L)
                        .rightRowsRead(buildLeft ? 0L : build.rows.size())
                        .detail("build side exceeded maxBuildRows " + config.maxBuildRows())
                        .build();
                return ExactJoinRunResult.fromJoin(failed, null, envelopeFor(failed, leftCacheId, rightCacheId, config));
            }

            TypedTabularStreamIterable probe = new TypedTabularStreamIterable(probeStream);
            ExactJoinResult join = buildLeft
                    ? ExactJoin.join(config, left.schema(), build.rows, right.schema(), probe)
                    : ExactJoin.join(config, left.schema(), probe, right.schema(), build.rows);

            if (!join.mayPublish()) {
                return ExactJoinRunResult.fromJoin(join, null,
                        envelopeFor(join, leftCacheId, rightCacheId, config));
            }

            boolean inputsFullyScanned = left.inputsFullyScanned() && right.inputsFullyScanned();
            SourceDescriptor leftDesc = left.sourceDescriptor();
            SourceDescriptor rightDesc = right.sourceDescriptor();
            InfoTable table = DerivedTabularPublisher.toInfoTable(join.outputColumns(), join.outputRows());
            SourceDescriptor derived = DerivedArtifactLineage.forJoin(
                    leftDesc, leftCacheId, rightDesc, rightCacheId, table, ROUTE_ID, inputsFullyScanned);
            String findingId = DerivedTabularPublisher.publish(
                    join.outputColumns(), join.outputRows(), derived);
            AnalysisEnvelope envelope = envelopeSuccess(join, leftCacheId, rightCacheId, findingId, config,
                    derived.completenessStatus(), inputsFullyScanned);
            return ExactJoinRunResult.fromJoin(join, findingId, envelope);
        }
    }

    private static MaterializeResult materializeBounded(TypedTabularStream stream, long maxBuildRows)
            throws Exception {
        List<TypedRow> rows = new ArrayList<>();
        while (!stream.exhausted()) {
            var batch = stream.readBatch(64);
            for (TypedRow row : batch.rows()) {
                if (rows.size() + 1L > maxBuildRows) {
                    MaterializeResult r = new MaterializeResult();
                    r.budgetExceeded = true;
                    r.rows = rows;
                    return r;
                }
                rows.add(row);
            }
        }
        MaterializeResult r = new MaterializeResult();
        r.rows = rows;
        return r;
    }

    private static AnalysisEnvelope envelopeFor(
            ExactJoinResult join, String leftId, String rightId, ExactJoinConfig config) {
        EvidenceStatus status = join.success() ? EvidenceStatus.SUCCESS : EvidenceStatus.ERROR;
        AnalysisEnvelopeBuilder b = AnalysisEnvelopeBuilder.create()
                .status(status)
                .operation(AnalysisOperation.EXACT_JOIN)
                .addSourceCacheId(leftId)
                .addSourceCacheId(rightId)
                .method(descriptor(config))
                .completeness(CompletenessStatus.UNKNOWN)
                .n(join.outputRows().size())
                .addWarning(join.reason().name())
                .inputsFullyScanned(false)
                .rowsRead(join.leftRowsRead() + join.rightRowsRead())
                .rowsOutput(join.outputRows().size());
        if (join.detail() != null) {
            b.addWarning(join.detail());
        }
        for (String dup : join.representativeDuplicateKeys()) {
            b.addWarning("duplicateKey:" + dup);
        }
        return b.build();
    }

    private static AnalysisEnvelope envelopeSuccess(
            ExactJoinResult join,
            String leftId,
            String rightId,
            String findingId,
            ExactJoinConfig config,
            CompletenessStatus completeness,
            boolean inputsFullyScanned) {
        CompletenessStatus c = completeness == null ? CompletenessStatus.UNKNOWN : completeness;
        long n = join.outputRows().size();
        EvidenceStatus status;
        if (c != CompletenessStatus.COMPLETE || !inputsFullyScanned) {
            status = EvidenceStatus.INSUFFICIENT_EVIDENCE;
        } else if (n <= 0L) {
            status = EvidenceStatus.NO_FINDING;
        } else {
            status = EvidenceStatus.SUCCESS;
        }
        AnalysisEnvelopeBuilder b = AnalysisEnvelopeBuilder.create()
                .status(status)
                .operation(AnalysisOperation.EXACT_JOIN)
                .addSourceCacheId(leftId)
                .addSourceCacheId(rightId)
                .findingCacheId(findingId)
                .method(descriptor(config))
                .completeness(c)
                .n(n)
                .inputsFullyScanned(inputsFullyScanned)
                .rowsRead(join.leftRowsRead() + join.rightRowsRead())
                .rowsOutput(n);
        if (status == EvidenceStatus.NO_FINDING) {
            b.addWarning("no_join_matches");
        }
        return b.build();
    }

    private static AnalysisMethodDescriptor descriptor(ExactJoinConfig config) {
        return AnalysisMethodDescriptor.builder()
                .id(ROUTE_ID)
                .version("1")
                .profileDigest(config.profileDigest())
                .operation(AnalysisOperation.EXACT_JOIN)
                .build();
    }

    private static final class MaterializeResult {
        List<TypedRow> rows = List.of();
        boolean budgetExceeded;
    }

    /** Outcome of a cache-backed join attempt. */
    public static final class ExactJoinRunResult {
        private final ExactJoinResult join;
        private final String findingCacheId;
        private final AnalysisEnvelope envelope;
        private final String unavailableReason;

        private ExactJoinRunResult(
                ExactJoinResult join, String findingCacheId, AnalysisEnvelope envelope, String unavailableReason) {
            this.join = join;
            this.findingCacheId = findingCacheId;
            this.envelope = envelope;
            this.unavailableReason = unavailableReason;
        }

        static ExactJoinRunResult fromJoin(ExactJoinResult join, String findingCacheId, AnalysisEnvelope envelope) {
            return new ExactJoinRunResult(join, findingCacheId, envelope, null);
        }

        static ExactJoinRunResult unavailable(String reason) {
            return new ExactJoinRunResult(null, null, null, reason);
        }

        public boolean unavailable() {
            return unavailableReason != null;
        }

        public String unavailableReason() {
            return unavailableReason;
        }

        public ExactJoinResult join() {
            return join;
        }

        public String findingCacheId() {
            return findingCacheId;
        }

        public AnalysisEnvelope envelope() {
            return envelope;
        }

        public boolean mayPublish() {
            return findingCacheId != null && join != null && join.mayPublish();
        }
    }
}
