package com.thingworx.things.agent.tools;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.analysis.AnalysisEnvelope;
import com.thingworx.things.agent.analysis.AnalysisEnvelopeJson;
import com.thingworx.things.agent.analysis.AnalysisOperation;
import com.thingworx.things.agent.analysis.G1DetectionResult;
import com.thingworx.things.agent.analysis.U5AnalysisEnvelopeFactory;
import com.thingworx.things.agent.analysis.U5OperationAdmission;
import com.thingworx.things.agent.analysis.changepoint.BinarySegmentation;
import com.thingworx.things.agent.analysis.outlier.IqrDetector;
import com.thingworx.things.agent.analysis.outlier.RobustZDetector;
import com.thingworx.things.agent.analysis.outlier.ZeroDispersionPolicy;
import com.thingworx.things.agent.analysis.relationship.AssociationEvidence;
import com.thingworx.things.agent.analysis.relationship.G2RelationshipResult;
import com.thingworx.things.agent.analysis.relationship.SeriesAligner;
import com.thingworx.things.agent.analysis.spc.SpcRunRule;
import com.thingworx.things.agent.analysis.spc.SpcRunRuleEvaluator;
import com.thingworx.things.agent.analysis.stats.NumericSeries;
import com.thingworx.things.agent.analysis.stats.NumericSeriesProjector;
import com.thingworx.things.agent.analysis.trend.G4CrossingResult;
import com.thingworx.things.agent.analysis.trend.G4TrendResult;
import com.thingworx.things.agent.analysis.trend.OlsTrend;
import com.thingworx.things.agent.analysis.trend.TheilSenTrend;
import com.thingworx.things.agent.analysis.trend.ThresholdCrossingEvaluator;
import com.thingworx.things.agent.cache.ArtifactCacheException;
import com.thingworx.things.agent.cache.ArtifactCacheTurnFaults;
import com.thingworx.things.agent.cache.TypedTabularStream;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;
import com.thingworx.things.agent.taskstate.AgentTaskState;

/**
 * DIK-5 model-visible {@code analyze_cached_result} executor. Projects conversation cache handles
 * into {@link NumericSeries} via U4 {@link TypedTabularStream} + {@link NumericSeriesProjector}.
 * Handle inputs only (§8.2) — never accepts row/value arrays.
 */
public final class AnalyzeCachedResultExecutor {

    public static final String TOOL_NAME = AnalyzeCachedResultToolSchema.TOOL_NAME;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnalyzeCachedResultExecutor() {}

    public static String execute(ToolCall toolCall) throws Exception {
        if (!U5OperationAdmission.analyzeEnabled()) {
            return errorJson(U5OperationAdmission.ANALYZE_UNAVAILABLE, null);
        }
        JsonNode args = MAPPER.readTree(toolCall.getArguments() == null ? "{}" : toolCall.getArguments());
        if (hasProhibitedValueArrays(args)) {
            return errorJson(U4SeriesToolArgs.ARGUMENT_MISSING,
                    "row/value arrays are not accepted; supply cacheId handle only");
        }
        String opRaw = text(args, "operation");
        if (opRaw == null) {
            return errorJson(U4SeriesToolArgs.ARGUMENT_MISSING, "operation required");
        }
        AnalysisOperation operation;
        try {
            operation = AnalysisOperation.valueOf(opRaw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return errorJson(U4SeriesToolArgs.ARGUMENT_MISSING, "unknown operation: " + opRaw);
        }
        if (!operation.isU5()) {
            return errorJson(U4SeriesToolArgs.ARGUMENT_MISSING, "operation must be a U5 family");
        }

        String cacheId;
        String timeColumn;
        String valueColumn;
        try {
            cacheId = U4SeriesToolArgs.requireCacheId(args);
            timeColumn = U4SeriesToolArgs.requireTimeColumn(args);
            valueColumn = requireValueColumn(args);
        } catch (IllegalArgumentException ex) {
            return errorJson(U4SeriesToolArgs.reasonFrom(ex.getMessage()), ex.getMessage());
        }
        String methodId = text(args, "methodId");
        try {
            AnalysisEnvelope envelope;
            List<ProjectedSeries> operands = new java.util.ArrayList<>(2);
            try {
                envelope = dispatch(operation, methodId, args, cacheId, timeColumn, valueColumn, operands);
            } catch (AnalyzeProjectionDiagnostics.ProjectionMiss miss) {
                return projectionErrorJson(miss);
            }
            recordAnalysisAssessment(envelope);
            ObjectNode out = MAPPER.createObjectNode();
            out.put("status", envelope.status() == EvidenceStatus.ERROR ? "ERROR" : "OK");
            out.put("reason", envelope.status().name());
            // Informational to the LLM; compact evidence still enters task-state for §8.3 guards.
            out.put("mayPublish", false);
            out.set("analysisEnvelope", MAPPER.readTree(AnalysisEnvelopeJson.toCompactJson(envelope)));
            if (envelope.status() != EvidenceStatus.ERROR) {
                out.set("sources", sourcesJson(operands));
            }
            return MAPPER.writeValueAsString(out);
        } catch (ArtifactCacheException ex) {
            ArtifactCacheTurnFaults.rethrowRepositoryUnavailable(ex);
            return errorJson(ex.code().name(), ex.getMessage());
        } catch (IllegalStateException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("BUDGET")) {
                return errorJson(ex.getMessage(), null);
            }
            throw ex;
        } catch (IllegalArgumentException ex) {
            return errorJson(U4SeriesToolArgs.reasonFrom(ex.getMessage()), ex.getMessage());
        }
    }

    private static AnalysisEnvelope dispatch(AnalysisOperation operation, String methodId, JsonNode args,
            String cacheId, String timeColumn, String valueColumn, List<ProjectedSeries> operands) throws Exception {
        boolean relationship = operation == AnalysisOperation.RELATIONSHIP;
        ProjectedSeries primary = project(cacheId, timeColumn, valueColumn,
                AnalyzeProjectionDiagnostics.SIDE_LEFT, relationship, AnalyzeProjectionDiagnostics.SOURCE_EXPLICIT);
        operands.add(primary);
        CompletenessStatus completeness = primary.completeness;
        switch (operation) {
            case OUTLIER: {
                String method = methodId == null ? "robust_z" : methodId;
                G1DetectionResult result = "iqr".equals(method)
                        ? IqrDetector.detect(primary.series, IqrDetector.DEFAULT_K, ZeroDispersionPolicy.INSUFFICIENT)
                        : RobustZDetector.detect(primary.series, RobustZDetector.DEFAULT_THRESHOLD,
                                ZeroDispersionPolicy.INSUFFICIENT);
                return U5AnalysisEnvelopeFactory.fromG1(operation, result, cacheId, completeness);
            }
            case CHANGE_POINT: {
                G1DetectionResult result =
                        BinarySegmentation.detect(primary.series, BinarySegmentation.Config.defaults());
                return U5AnalysisEnvelopeFactory.fromG1(operation, result, cacheId, completeness);
            }
            case SPC: {
                G1DetectionResult result =
                        SpcRunRuleEvaluator.evaluate(primary.series, java.util.EnumSet.of(SpcRunRule.R1));
                return U5AnalysisEnvelopeFactory.fromG1(operation, result, cacheId, completeness);
            }
            case RELATIONSHIP: {
                String rightCacheId = text(args, "rightCacheId");
                if (rightCacheId == null) {
                    throw new IllegalArgumentException(
                            U4SeriesToolArgs.ARGUMENT_MISSING + ": rightCacheId required for relationship");
                }
                String rightValueColumn = text(args, "rightValueColumn");
                String rightValueSource = AnalyzeProjectionDiagnostics.SOURCE_EXPLICIT;
                if (rightValueColumn == null) {
                    rightValueColumn = valueColumn;
                    rightValueSource = AnalyzeProjectionDiagnostics.SOURCE_INHERITED_VALUE;
                }
                ProjectedSeries right = project(rightCacheId, timeColumn, rightValueColumn,
                        AnalyzeProjectionDiagnostics.SIDE_RIGHT, true, rightValueSource);
                operands.add(right);
                String align = text(args, "alignment");
                com.thingworx.things.agent.analysis.relationship.AlignmentResult alignment =
                        "nearest".equalsIgnoreCase(align)
                                ? SeriesAligner.nearest(primary.series, right.series,
                                        Duration.ofMillis(args.path("toleranceMillis").asLong(1000L)))
                                : SeriesAligner.exact(primary.series, right.series);
                String method = methodId == null ? "pearson" : methodId;
                G2RelationshipResult result;
                if ("spearman".equals(method)) {
                    result = AssociationEvidence.spearman(alignment, null, null);
                } else if ("ols_pair".equals(method)) {
                    result = AssociationEvidence.olsPair(alignment, null, null);
                } else {
                    result = AssociationEvidence.pearson(alignment, null, null);
                }
                long dropped = Long.parseLong(result.metrics().getOrDefault("pairsDropped", "0"));
                CompletenessStatus c = dropped > 0L ? CompletenessStatus.PARTIAL : mergeCompleteness(
                        primary.completeness, right.completeness);
                return U5AnalysisEnvelopeFactory.fromG2(result, cacheId, rightCacheId, c);
            }
            case TREND: {
                String method = methodId == null ? "ols_trend" : methodId;
                G4TrendResult result = "theil_sen".equals(method)
                        ? TheilSenTrend.fit(primary.series, null)
                        : OlsTrend.fit(primary.series, null);
                return U5AnalysisEnvelopeFactory.fromG4Trend(result, cacheId, completeness);
            }
            case THRESHOLD_CROSSING: {
                double threshold = args.path("threshold").asDouble(Double.NaN);
                if (!Double.isFinite(threshold)) {
                    throw new IllegalArgumentException(
                            U4SeriesToolArgs.ARGUMENT_MISSING + ": threshold required");
                }
                long horizonSec = args.path("horizonSeconds").asLong(3600L);
                ThresholdCrossingEvaluator.Config cfg = ThresholdCrossingEvaluator.Config.defaults()
                        .withThreshold(threshold)
                        .withHorizon(Duration.ofSeconds(horizonSec));
                if ("theil_sen".equals(methodId)) {
                    cfg = cfg.withTheilSen(true);
                }
                G4CrossingResult result = ThresholdCrossingEvaluator.evaluate(primary.series, cfg);
                return U5AnalysisEnvelopeFactory.fromG4Crossing(result, cacheId, completeness);
            }
            default:
                throw new IllegalArgumentException("unsupported operation");
        }
    }

    private static ProjectedSeries project(String cacheId, String timeColumn, String valueColumn,
            String side, boolean relationship, String valueSource) throws Exception {
        try {
            ProjectedSeries projected = project(cacheId, timeColumn, valueColumn);
            projected.role = side;
            return projected;
        } catch (com.thingworx.things.agent.cache.UnknownProjectedColumnException miss) {
            boolean rightSide = AnalyzeProjectionDiagnostics.SIDE_RIGHT.equals(side);
            String parameter = AnalyzeProjectionDiagnostics.parameterFor(miss.projectionIndex(), rightSide);
            String source = miss.projectionIndex() == 0 ? AnalyzeProjectionDiagnostics.SOURCE_EXPLICIT : valueSource;
            throw new AnalyzeProjectionDiagnostics.ProjectionMiss(miss, cacheId, side, relationship, parameter, source);
        }
    }

    private static ProjectedSeries project(String cacheId, String timeColumn, String valueColumn)
            throws Exception {
        try (TypedTabularStream stream =
                TypedTabularStream.open(cacheId, List.of(timeColumn, valueColumn), 0)) {
            NumericSeries series =
                    NumericSeriesProjector.projectSourceOrder(stream, timeColumn, valueColumn, null);
            CompletenessStatus completeness = completenessOf(stream.sourceDescriptor());
            if (!series.inputsFullyScanned() && completeness == CompletenessStatus.COMPLETE) {
                completeness = CompletenessStatus.PARTIAL;
            }
            return new ProjectedSeries(series, completeness, cacheId, timeColumn, valueColumn,
                    stream.sourceDescriptor());
        }
    }

    /**
     * CM-4 {@code sources[]}: one row per actual operand (left, then right for RELATIONSHIP). Cache id
     * and columns are the ones the projection actually used; Thing/property come from the cache's
     * runtime descriptor and are omitted when the writer declared none. A same-cache pair yields two
     * rows with the same id; this is deliberately distinct from the unique {@code sourceCacheIds[]}.
     */
    private static ArrayNode sourcesJson(List<ProjectedSeries> operands) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (ProjectedSeries op : operands) {
            ObjectNode row = MAPPER.createObjectNode();
            row.put("role", op.role == null ? AnalyzeProjectionDiagnostics.SIDE_LEFT : op.role);
            row.put("cacheId", op.cacheId);
            row.put("timeColumn", op.timeColumn);
            row.put("valueColumn", op.valueColumn);
            SourceDescriptor d = op.descriptor;
            if (d != null && d.subjectThingName() != null && d.subjectPropertyName() != null) {
                row.put("thingName", d.subjectThingName());
                row.put("propertyName", d.subjectPropertyName());
            }
            arr.add(row);
        }
        return arr;
    }

    private static CompletenessStatus completenessOf(SourceDescriptor descriptor) {
        if (descriptor == null || descriptor.completenessStatus() == null) {
            return CompletenessStatus.COMPLETE;
        }
        return descriptor.completenessStatus();
    }

    private static CompletenessStatus mergeCompleteness(CompletenessStatus left, CompletenessStatus right) {
        if (left == CompletenessStatus.PARTIAL || right == CompletenessStatus.PARTIAL) {
            return CompletenessStatus.PARTIAL;
        }
        if (left == CompletenessStatus.UNKNOWN || right == CompletenessStatus.UNKNOWN) {
            return CompletenessStatus.UNKNOWN;
        }
        return CompletenessStatus.COMPLETE;
    }

    private static String requireValueColumn(JsonNode args) {
        String valueColumn = U4SeriesToolArgs.text(args, "valueColumn");
        if (valueColumn == null) {
            throw new IllegalArgumentException(U4SeriesToolArgs.ARGUMENT_MISSING + ": valueColumn required");
        }
        return valueColumn;
    }

    private static boolean hasProhibitedValueArrays(JsonNode args) {
        return isNonEmptyArray(args, "values")
                || isNonEmptyArray(args, "epochMillis")
                || isNonEmptyArray(args, "rightValues");
    }

    private static boolean isNonEmptyArray(JsonNode args, String field) {
        return args != null && args.has(field) && args.get(field).isArray() && args.get(field).size() > 0;
    }

    private static void recordAnalysisAssessment(AnalysisEnvelope envelope) {
        if (envelope == null || envelope.evidence() == null) {
            return;
        }
        AgentTaskState st = AgentToolContext.getAgentTaskState();
        if (st != null) {
            st.recordAnalysisAssessment(envelope.evidence());
        }
    }

    private static String text(JsonNode args, String field) {
        JsonNode n = args.get(field);
        if (n == null || n.isNull() || !n.isTextual()) {
            return null;
        }
        String s = n.asText();
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** CM-2: unchanged outer shape plus structured projection feedback. */
    private static String projectionErrorJson(AnalyzeProjectionDiagnostics.ProjectionMiss miss) throws Exception {
        ObjectNode err = MAPPER.createObjectNode();
        err.put("status", "ERROR");
        err.put("reason", U4SeriesToolArgs.ARGUMENT_MISSING);
        err.put("detail", miss.getMessage());
        err.put("mayPublish", false);
        AnalyzeProjectionDiagnostics.appendTo(err, miss);
        return MAPPER.writeValueAsString(err);
    }

    private static String errorJson(String reason, String detail) throws Exception {
        ObjectNode err = MAPPER.createObjectNode();
        err.put("status", "ERROR");
        err.put("reason", reason);
        if (detail != null && !detail.isBlank()) {
            err.put("detail", detail);
        }
        err.put("mayPublish", false);
        return MAPPER.writeValueAsString(err);
    }

    private static final class ProjectedSeries {
        final NumericSeries series;
        final CompletenessStatus completeness;
        final String cacheId;
        final String timeColumn;
        final String valueColumn;
        final SourceDescriptor descriptor;
        String role;

        ProjectedSeries(NumericSeries series, CompletenessStatus completeness, String cacheId, String timeColumn,
                String valueColumn, SourceDescriptor descriptor) {
            this.series = series;
            this.completeness = completeness;
            this.cacheId = cacheId;
            this.timeColumn = timeColumn;
            this.valueColumn = valueColumn;
            this.descriptor = descriptor;
        }
    }
}
