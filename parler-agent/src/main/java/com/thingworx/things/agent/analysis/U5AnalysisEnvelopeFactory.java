package com.thingworx.things.agent.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.thingworx.things.agent.analysis.relationship.G2RelationshipResult;
import com.thingworx.things.agent.analysis.trend.G4CrossingResult;
import com.thingworx.things.agent.analysis.trend.G4TrendResult;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

/**
 * Maps G1/G2/G4 algorithm shells onto the U4-owned {@link AnalysisEnvelope} (DIK-5 packaging).
 */
public final class U5AnalysisEnvelopeFactory {

    private U5AnalysisEnvelopeFactory() {}

    public static AnalysisEnvelope fromG1(AnalysisOperation operation, G1DetectionResult result,
            String sourceCacheId, CompletenessStatus completeness) {
        Objects.requireNonNull(result, "result");
        Map<String, String> metrics = new LinkedHashMap<>(result.metrics());
        metrics.putIfAbsent(AnalysisOutcomeCodes.METRIC_KEY, result.outcomeCode());
        return base(operation, result.methodId(), result.status(), result.supportN(), metrics,
                sourceCacheId, completeness, chartIntent(operation),
                List.of("method=" + result.methodId(), "outcome=" + result.outcomeCode(),
                        "supportN=" + result.supportN()));
    }

    public static AnalysisEnvelope fromG2(G2RelationshipResult result, String sourceCacheId,
            CompletenessStatus completeness) {
        return fromG2(result, sourceCacheId, null, completeness);
    }

    /**
     * Relationship lineage records <em>both</em> operands (B1): left then right, in that order, each
     * once. When both sides read the same cache (different columns), the lineage holds one unique id;
     * callers must not infer the operand count from the array length.
     */
    public static AnalysisEnvelope fromG2(G2RelationshipResult result, String leftCacheId, String rightCacheId,
            CompletenessStatus completeness) {
        Objects.requireNonNull(result, "result");
        Map<String, String> metrics = new LinkedHashMap<>(result.metrics());
        metrics.putIfAbsent(AnalysisOutcomeCodes.METRIC_KEY, result.outcomeCode());
        List<String> sources = new java.util.ArrayList<>(2);
        for (String id : new String[] {leftCacheId, rightCacheId}) {
            if (id != null && !id.isBlank() && !sources.contains(id.trim())) {
                sources.add(id.trim());
            }
        }
        return base(AnalysisOperation.RELATIONSHIP, result.methodId(), result.status(), result.supportN(),
                metrics, sources, completeness, "u5.relationship.pairs",
                List.of("method=" + result.methodId(), "outcome=" + result.outcomeCode(),
                        "supportN=" + result.supportN()),
                List.of("associational", "not_tested_causal"));
    }

    public static AnalysisEnvelope fromG4Trend(G4TrendResult result, String sourceCacheId,
            CompletenessStatus completeness) {
        Objects.requireNonNull(result, "result");
        Map<String, String> metrics = new LinkedHashMap<>(result.metrics());
        metrics.putIfAbsent(AnalysisOutcomeCodes.METRIC_KEY, result.outcomeCode());
        return base(AnalysisOperation.TREND, result.methodId(), result.status(), result.supportN(), metrics,
                sourceCacheId, completeness, "u5.trend.fit",
                List.of("method=" + result.methodId(), "outcome=" + result.outcomeCode(),
                        "supportN=" + result.supportN()));
    }

    public static AnalysisEnvelope fromG4Crossing(G4CrossingResult result, String sourceCacheId,
            CompletenessStatus completeness) {
        Objects.requireNonNull(result, "result");
        Map<String, String> metrics = new LinkedHashMap<>(result.metrics());
        metrics.putIfAbsent(AnalysisOutcomeCodes.METRIC_KEY, result.outcomeCode());
        List<String> applicability = List.of();
        if ("outside_horizon".equals(result.outcomeCode())) {
            applicability = List.of("outside_horizon");
        }
        return base(AnalysisOperation.THRESHOLD_CROSSING, result.methodId(), result.status(),
                result.supportN(), metrics, sourceCacheId, completeness, "u5.trend.crossing",
                List.of("method=" + result.methodId(), "outcome=" + result.outcomeCode(),
                        "supportN=" + result.supportN()),
                applicability);
    }

    private static AnalysisEnvelope base(AnalysisOperation operation, String methodId,
            EvidenceStatus status, long supportN, Map<String, String> metrics, String sourceCacheId,
            CompletenessStatus completeness, String chartIntent, List<String> summaryFacts) {
        return base(operation, methodId, status, supportN, metrics, sourceCacheId, completeness,
                chartIntent, summaryFacts, List.of());
    }

    private static AnalysisEnvelope base(AnalysisOperation operation, String methodId,
            EvidenceStatus status, long supportN, Map<String, String> metrics, String sourceCacheId,
            CompletenessStatus completeness, String chartIntent, List<String> summaryFacts,
            List<String> applicability) {
        List<String> sources = sourceCacheId == null || sourceCacheId.isBlank() ? List.of() : List.of(sourceCacheId);
        return base(operation, methodId, status, supportN, metrics, sources, completeness, chartIntent,
                summaryFacts, applicability);
    }

    private static AnalysisEnvelope base(AnalysisOperation operation, String methodId,
            EvidenceStatus status, long supportN, Map<String, String> metrics, List<String> sourceCacheIds,
            CompletenessStatus completeness, String chartIntent, List<String> summaryFacts,
            List<String> applicability) {
        CompletenessStatus c = completeness == null ? CompletenessStatus.UNKNOWN : completeness;
        AnalysisEnvelopeBuilder b = AnalysisEnvelopeBuilder.create()
                .status(status)
                .operation(operation)
                .method(U5MethodRegistry.descriptor(methodId, "u5-demo"))
                .completeness(c)
                .n(supportN)
                .metrics(metrics)
                .summaryFacts(summaryFacts)
                .chartIntent(chartIntent)
                .inputsFullyScanned(true)
                .rowsRead(supportN)
                .rowsOutput(supportN);
        if (applicability != null) {
            for (String token : applicability) {
                b.addApplicability(token);
            }
        }
        for (String id : sourceCacheIds) {
            b.addSourceCacheId(id);
        }
        return b.build();
    }

    private static String chartIntent(AnalysisOperation op) {
        if (op == AnalysisOperation.OUTLIER) {
            return "u5.outlier.findings";
        }
        if (op == AnalysisOperation.CHANGE_POINT) {
            return "u5.change_point.findings";
        }
        if (op == AnalysisOperation.SPC) {
            return "u5.spc.findings";
        }
        return "u5.analysis";
    }
}
