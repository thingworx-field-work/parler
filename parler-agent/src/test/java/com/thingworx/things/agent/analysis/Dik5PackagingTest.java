package com.thingworx.things.agent.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.analysis.config.U5DemoAppProfiles;
import com.thingworx.things.agent.analysis.relationship.AssociationEvidence;
import com.thingworx.things.agent.analysis.relationship.SeriesAligner;
import com.thingworx.things.agent.analysis.stats.NumericObservation;
import com.thingworx.things.agent.analysis.stats.NumericSeries;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.llm.ToolSchemaSizer;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.AnalyzeCachedResultExecutor;
import com.thingworx.things.agent.tools.AnalyzeCachedResultToolSchema;
import com.thingworx.things.agent.tools.BuiltInTools;
import com.thingworx.things.agent.tools.ToolRegistry;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.NumberPrimitive;

class Dik5PackagingTest {

    Dik5PackagingTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        AgentToolContext.setConversationId("dik5-packaging");
        U5OperationAdmission.resetForTests();
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
        U5OperationAdmission.resetForTests();
    }

    @Test
    void quantificationSuccessAllowsPartialCompleteness() {
        AnalysisEnvelope env = AnalysisEnvelopeBuilder.create()
                .status(EvidenceStatus.SUCCESS)
                .operation(AnalysisOperation.RELATIONSHIP)
                .method(U5MethodRegistry.descriptor("pearson", "u5-demo"))
                .completeness(CompletenessStatus.PARTIAL)
                .n(10)
                .metrics(Map.of(AnalysisOutcomeCodes.METRIC_KEY, "association", "pairsDropped", "2"))
                .inputsFullyScanned(true)
                .build();
        assertTrue(AnalysisEnvelopeValidator.validate(env).isEmpty());
    }

    @Test
    void detectionSuccessStillRequiresComplete() {
        assertThrows(IllegalArgumentException.class, () -> AnalysisEnvelopeBuilder.create()
                .status(EvidenceStatus.SUCCESS)
                .operation(AnalysisOperation.OUTLIER)
                .method(U5MethodRegistry.descriptor("robust_z", "u5-demo"))
                .completeness(CompletenessStatus.PARTIAL)
                .n(10)
                .metrics(Map.of(AnalysisOutcomeCodes.METRIC_KEY, "outlier"))
                .inputsFullyScanned(true)
                .build());
    }

    @Test
    void spearmanMetricsAreRankNamespaced() {
        NumericSeries left = series(new double[] {10, 20, 20, 40});
        NumericSeries right = series(new double[] {1, 2, 3, 4});
        var r = AssociationEvidence.spearman(SeriesAligner.exact(left, right), null, null);
        assertTrue(r.metrics().containsKey("spearmanRho"));
        assertTrue(r.metrics().containsKey("rankCovariance"));
        assertFalse(r.metrics().containsKey("pearsonR"));
        assertFalse(r.metrics().containsKey("covariance"));
    }

    @Test
    void nearestAlignerRejectsHugeCandidateEstimate() {
        List<NumericObservation> left = new ArrayList<>();
        List<NumericObservation> right = new ArrayList<>();
        // 2001 x 2001 > 2_000_000 candidate estimate
        for (int i = 0; i < 2001; i++) {
            left.add(new NumericObservation(T0.plusMillis(i), i, 1.0));
            right.add(new NumericObservation(T0.plusMillis(i), i, 2.0));
        }
        assertThrows(IllegalStateException.class, () -> SeriesAligner.nearest(
                new NumericSeries(left, null, true),
                new NumericSeries(right, null, true),
                Duration.ofDays(365)));
    }

    @Test
    void analyzeCachedResult_outlierHandlePath() throws Exception {
        double[] values = new double[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 20};
        String cacheId = storeSeries(values);
        String args = "{\"operation\":\"outlier\",\"methodId\":\"robust_z\","
                + "\"cacheId\":\"" + cacheId + "\",\"timeColumn\":\"ts\",\"valueColumn\":\"v\"}";
        String json = AnalyzeCachedResultExecutor.execute(new ToolCall("1", AnalyzeCachedResultExecutor.TOOL_NAME, args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("OK", root.path("status").asText(), root.toString());
        assertTrue(root.has("analysisEnvelope"));
        assertEquals("outlier", root.path("analysisEnvelope").path("operation").asText());
    }

    @Test
    void analyzeCachedResult_rejectsValueArrays() throws Exception {
        String args = "{\"operation\":\"outlier\",\"methodId\":\"robust_z\","
                + "\"cacheId\":\"unused\",\"values\":[1,1,1,1,1,1,1,1,1,20]}";
        String json = AnalyzeCachedResultExecutor.execute(new ToolCall("1", AnalyzeCachedResultExecutor.TOOL_NAME, args));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("ERROR", root.path("status").asText());
        assertTrue(root.path("detail").asText().contains("row/value arrays"), root.toString());
    }

    @Test
    void analyzeSchema_handleOnly_noValueArrays() throws Exception {
        String schema = MAPPER.writeValueAsString(AnalyzeCachedResultToolSchema.parametersSchema());
        assertFalse(schema.contains("\"values\""), schema);
        assertFalse(schema.contains("epochMillis"), schema);
        assertFalse(schema.contains("rightValues"), schema);
        assertTrue(schema.contains("\"cacheId\""), schema);
        assertTrue(schema.contains("\"required\""), schema);
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        ToolDefinition analyze = reg.getAllDefinitions().stream()
                .filter(d -> AnalyzeCachedResultToolSchema.TOOL_NAME.equals(d.getName()))
                .findFirst()
                .orElseThrow();
        String surface = analyze.getDescription() + "\n" + MAPPER.writeValueAsString(analyze.getParametersSchema());
        assertFalse(surface.contains("offline demo"), surface);
        assertFalse(surface.toLowerCase().contains("\"values\""), surface);
    }

    @Test
    void analyzeToolAdvertised_andR11StillUnderCeiling() {
        assertTrue(AnalyzeCachedResultToolSchema.advertised());
        assertTrue(U5DemoAppProfiles.advertisedOperations().contains("trend"));
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        List<ToolDefinition> defs = new ArrayList<>(reg.getAllDefinitions());
        assertEquals(28, defs.size());
        assertTrue(defs.stream().anyMatch(d -> AnalyzeCachedResultToolSchema.TOOL_NAME.equals(d.getName())));
        int openai = ToolSchemaSizer.totalSchemaChars("openai-chat-completions-v1", defs);
        int anthropic = ToolSchemaSizer.totalSchemaChars("anthropic-messages-v1", defs);
        assertTrue(openai < 75_000, "openai=" + openai);
        assertTrue(anthropic < 75_000, "anthropic=" + anthropic);
        // Prints the handle-only surface footprint for manual inspection.
        System.out.println("DIK-5 R11 after (handle-only): toolCount=" + defs.size()
                + " openai.totalSchemaChars=" + openai
                + " anthropic.totalSchemaChars=" + anthropic);
    }

    @Test
    void s6RemainsMeasuredDefer_noPercentileFieldsAdmitted() {
        // DIK-5 re-measure against conformant analyze surface: still no safe admission.
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        ToolDefinition summarize = reg.getAllDefinitions().stream()
                .filter(d -> "summarize_cached_result".equals(d.getName()))
                .findFirst()
                .orElseThrow();
        String schema = summarize.getParametersSchema().toString();
        assertFalse(schema.contains("percentilesEvaluated"));
        assertFalse(schema.contains("percentilesSkipped"));
    }

    private static String storeSeries(double[] values) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition ts = new FieldDefinition();
        ts.setName("ts");
        ts.setBaseType(BaseTypes.DATETIME);
        shape.addFieldDefinition(ts);
        FieldDefinition v = new FieldDefinition();
        v.setName("v");
        v.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(v);
        InfoTable table = new InfoTable(shape);
        for (int i = 0; i < values.length; i++) {
            ValueCollection vc = new ValueCollection();
            vc.put("ts", new DatetimePrimitive(new DateTime(T0.plusSeconds(i).toEpochMilli())));
            vc.put("v", new NumberPrimitive(values[i]));
            table.addRow(vc);
        }
        return TabularArtifactHub.store(table, SourceDescriptor.builder()
                .completenessStatus(CompletenessStatus.COMPLETE)
                .rowsExamined((long) values.length)
                .rowsReturned((long) values.length)
                .build());
    }

    private static NumericSeries series(double[] values) {
        List<NumericObservation> obs = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            obs.add(new NumericObservation(T0.plusMillis(i * 1000L), i, values[i]));
        }
        return new NumericSeries(obs, null, true);
    }
}
