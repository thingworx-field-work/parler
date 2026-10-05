package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.regex.Pattern;

import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.analysis.ComputingOperationAdmission;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.join.U4OperationAdmission;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.NumberPrimitive;

/**
 * TQJ-5: {@code tabulate_cached_result} {@code mode=quality}/{@code resample} share U4 executors.
 */
class TabulateU4SeriesModeTest {

    TabulateU4SeriesModeTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        AgentToolContext.setConversationId("tqj5-u4-series");
        U4OperationAdmission.resetForTests();
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
        U4OperationAdmission.resetForTests();
        ComputingOperationAdmission.resetForTests();
    }

    @Test
    void qualityMode_successReturnsU4Shell_noFindingCacheId() throws Exception {
        String cid = storeSeries();
        JsonNode out = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(call(
                "{\"cacheId\":\"" + cid + "\",\"mode\":\"quality\","
                        + "\"timeColumn\":\"ts\",\"valueColumn\":\"v\","
                        + "\"windowStart\":\"2026-01-01T00:00:00Z\","
                        + "\"windowEnd\":\"2026-01-01T01:00:00Z\"}")));
        assertEquals("OK", out.path("status").asText(), out.toString());
        assertFalse(out.has("findingCacheId"), out.toString());
        assertFalse(out.path("mayPublish").asBoolean(true));
        assertTrue(out.has("analysisEnvelope"));
        assertEquals("quality", out.path("analysisEnvelope").path("operation").asText());
    }

    @Test
    void qualityMode_missingWindow_failsFast() throws Exception {
        String cid = storeSeries();
        JsonNode out = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(call(
                "{\"cacheId\":\"" + cid + "\",\"mode\":\"quality\",\"timeColumn\":\"ts\"}")));
        assertEquals("ERROR", out.path("status").asText());
        assertEquals(U4SeriesToolArgs.ARGUMENT_MISSING, out.path("reason").asText());
    }

    @Test
    void qualityAdmissionDisabled_withdrawsAndRejects() throws Exception {
        U4OperationAdmission.setQualityEnabled(false);
        assertAdvertisedSurfaceOmitsMode(TabulateCachedResultToolSchema.MODE_QUALITY);

        String cid = storeSeries();
        JsonNode out = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(call(
                "{\"cacheId\":\"" + cid + "\",\"mode\":\"quality\","
                        + "\"timeColumn\":\"ts\",\"valueColumn\":\"v\","
                        + "\"windowStart\":\"2026-01-01T00:00:00Z\","
                        + "\"windowEnd\":\"2026-01-01T01:00:00Z\"}")));
        assertEquals(U4OperationAdmission.QUALITY_UNAVAILABLE, out.path("reason").asText());
    }

    @Test
    void partialAdmission_qualityOffResampleOn_omitsQualityEverywhere() throws Exception {
        U4OperationAdmission.setQualityEnabled(false);
        U4OperationAdmission.setResampleEnabled(true);
        assertAdvertisedSurfaceOmitsMode(TabulateCachedResultToolSchema.MODE_QUALITY);
        assertTrue(TabulateCachedResultToolSchema.advertisedSeriesModes()
                .contains(TabulateCachedResultToolSchema.MODE_RESAMPLE));
        String surface = fullTabulateAdvertisedSurface();
        assertTrue(surface.contains("mode=resample"), surface);
        assertFalse(surface.contains("mode=quality"), surface);
    }

    @Test
    void partialAdmission_resampleOffQualityOn_omitsResampleEverywhere() throws Exception {
        U4OperationAdmission.setQualityEnabled(true);
        U4OperationAdmission.setResampleEnabled(false);
        // aggregation is shared with period_compare — disable both so the prop withdraws
        U4OperationAdmission.setPeriodCompareEnabled(false);
        assertAdvertisedSurfaceOmitsMode(TabulateCachedResultToolSchema.MODE_RESAMPLE);
        assertTrue(TabulateCachedResultToolSchema.advertisedSeriesModes()
                .contains(TabulateCachedResultToolSchema.MODE_QUALITY));
        String surface = fullTabulateAdvertisedSurface();
        assertTrue(surface.contains("mode=quality"), surface);
        assertFalse(surface.contains("mode=resample"), surface);
        assertFalse(surface.contains("\"aggregation\""), surface);
    }

    @Test
    void rollingMode_publishesFindingCacheId() throws Exception {
        String cid = storeSeries();
        JsonNode out = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(call(
                "{\"cacheId\":\"" + cid + "\",\"mode\":\"rolling\","
                        + "\"timeColumn\":\"ts\",\"valueColumn\":\"v\","
                        + "\"windowStart\":\"2026-01-01T00:00:00Z\","
                        + "\"windowEnd\":\"2026-01-01T01:00:00Z\","
                        + "\"observationWindow\":3}")));
        assertEquals("OK", out.path("status").asText(), out.toString());
        assertTrue(out.hasNonNull("findingCacheId"), out.toString());
        assertEquals("rolling", out.path("analysisEnvelope").path("operation").asText());
    }

    @Test
    void rateOfChangeMode_publishesFindingCacheId() throws Exception {
        String cid = storeSeries();
        JsonNode out = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(call(
                "{\"cacheId\":\"" + cid + "\",\"mode\":\"rate_of_change\","
                        + "\"timeColumn\":\"ts\",\"valueColumn\":\"v\","
                        + "\"windowStart\":\"2026-01-01T00:00:00Z\","
                        + "\"windowEnd\":\"2026-01-01T01:00:00Z\"}")));
        assertEquals("OK", out.path("status").asText(), out.toString());
        assertTrue(out.hasNonNull("findingCacheId"), out.toString());
        assertEquals("rate_of_change", out.path("analysisEnvelope").path("operation").asText());
    }

    @Test
    void periodCompareMode_assessmentOnly() throws Exception {
        String cid = storeSeries();
        JsonNode out = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(call(
                "{\"cacheId\":\"" + cid + "\",\"mode\":\"period_compare\","
                        + "\"timeColumn\":\"ts\",\"valueColumn\":\"v\","
                        + "\"originalWindowStart\":\"2026-01-01T00:00:00Z\","
                        + "\"originalWindowEnd\":\"2026-01-01T00:30:00Z\","
                        + "\"currentWindowStart\":\"2026-01-01T00:30:00Z\","
                        + "\"currentWindowEnd\":\"2026-01-01T01:00:00Z\","
                        + "\"aggregation\":\"MEAN\"}")));
        assertEquals("OK", out.path("status").asText(), out.toString());
        assertFalse(out.has("findingCacheId"), out.toString());
        assertFalse(out.path("mayPublish").asBoolean(true));
        assertEquals("period_compare", out.path("analysisEnvelope").path("operation").asText());
    }

    @Test
    void periodCompareAdmissionDisabled_withdrawsDualWindowProps() throws Exception {
        U4OperationAdmission.setPeriodCompareEnabled(false);
        String surface = fullTabulateAdvertisedSurface();
        Pattern token = Pattern.compile("\\bperiod_compare\\b");
        assertFalse(token.matcher(surface).find(), surface);
        assertFalse(surface.contains("originalWindowStart"), surface);
    }

    /**
     * Design §12 / TABULAR_INSIGHT §6.2: a disabled series mode token must not appear anywhere in
     * the advertised tool description + parameters schema (enum or unquoted description text).
     * Iterates {@link TabulateCachedResultToolSchema#SERIES_MODES} so future series modes inherit
     * the same gate when added to that list.
     */
    @Test
    void eachSeriesMode_partialDisable_omitsDisabledTokenFromFullSurface() throws Exception {
        for (String mode : TabulateCachedResultToolSchema.SERIES_MODES) {
            U4OperationAdmission.resetForTests();
            ComputingOperationAdmission.resetForTests();
            if (TabulateCachedResultToolSchema.MODE_QUALITY.equals(mode)) {
                U4OperationAdmission.setQualityEnabled(false);
            } else if (TabulateCachedResultToolSchema.MODE_RESAMPLE.equals(mode)) {
                U4OperationAdmission.setResampleEnabled(false);
            } else if (TabulateCachedResultToolSchema.MODE_ROLLING.equals(mode)) {
                U4OperationAdmission.setRollingEnabled(false);
            } else if (TabulateCachedResultToolSchema.MODE_RATE_OF_CHANGE.equals(mode)) {
                U4OperationAdmission.setRateOfChangeEnabled(false);
            } else if (TabulateCachedResultToolSchema.MODE_COUNTER_DELTA.equals(mode)) {
                ComputingOperationAdmission.setCounterDeltaEnabled(false);
            } else if (TabulateCachedResultToolSchema.MODE_ROLLING_STATS.equals(mode)) {
                ComputingOperationAdmission.setRollingStatsEnabled(false);
            } else if (TabulateCachedResultToolSchema.MODE_TIME_WEIGHTED.equals(mode)) {
                ComputingOperationAdmission.setTimeWeightedEnabled(false);
            } else {
                throw new AssertionError("add admission toggle for new SERIES_MODES entry: " + mode);
            }
            assertAdvertisedSurfaceOmitsMode(mode);
        }
    }

    @Test
    void resampleMode_publishesFindingCacheId() throws Exception {
        String cid = storeSeries();
        JsonNode out = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(call(
                "{\"cacheId\":\"" + cid + "\",\"mode\":\"resample\","
                        + "\"timeColumn\":\"ts\",\"valueColumn\":\"v\","
                        + "\"windowStart\":\"2026-01-01T00:00:00Z\","
                        + "\"windowEnd\":\"2026-01-01T02:00:00Z\","
                        + "\"aggregation\":\"SUM\"}")));
        assertEquals("OK", out.path("status").asText(), out.toString());
        assertTrue(out.hasNonNull("findingCacheId"), out.toString());
        assertTrue(out.path("mayPublish").asBoolean(false));
        assertEquals("resample", out.path("analysisEnvelope").path("operation").asText());
    }

    @Test
    void resampleMode_invalidAggregation_failsFast() throws Exception {
        String cid = storeSeries();
        JsonNode out = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(call(
                "{\"cacheId\":\"" + cid + "\",\"mode\":\"resample\","
                        + "\"timeColumn\":\"ts\",\"valueColumn\":\"v\","
                        + "\"windowStart\":\"2026-01-01T00:00:00Z\","
                        + "\"windowEnd\":\"2026-01-01T02:00:00Z\","
                        + "\"aggregation\":\"MEDIAN\"}")));
        assertEquals("ERROR", out.path("status").asText());
        assertEquals(U4SeriesToolArgs.AGGREGATION_INVALID, out.path("reason").asText());
        assertFalse(out.has("findingCacheId"));
    }

    private static void assertAdvertisedSurfaceOmitsMode(String mode) throws Exception {
        assertFalse(TabulateCachedResultToolSchema.advertisedModes().contains(mode), mode);
        assertFalse(TabulateCachedResultToolSchema.advertisedSeriesModes().contains(mode), mode);
        String surface = fullTabulateAdvertisedSurface();
        Pattern token = Pattern.compile("\\b" + Pattern.quote(mode) + "\\b");
        assertFalse(token.matcher(surface).find(),
                "disabled mode token must not appear in advertised surface: " + mode + "\n" + surface);
    }

    private static String fullTabulateAdvertisedSurface() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        ToolDefinition tabulate = reg.getAllDefinitions().stream()
                .filter(d -> "tabulate_cached_result".equals(d.getName()))
                .findFirst()
                .orElseThrow();
        return tabulate.getDescription() + "\n" + MAPPER.writeValueAsString(tabulate.getParametersSchema());
    }

    private static ToolCall call(String args) {
        return new ToolCall("1", "tabulate_cached_result", args);
    }

    private static String storeSeries() throws Exception {
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
        for (int i = 0; i < 12; i++) {
            ValueCollection vc = new ValueCollection();
            vc.put("ts", new DatetimePrimitive(new DateTime(T0.plusSeconds(i * 300L).toEpochMilli())));
            vc.put("v", new NumberPrimitive((double) (i + 1)));
            table.addRow(vc);
        }
        return TabularArtifactHub.store(table, SourceDescriptor.builder()
                .completenessStatus(CompletenessStatus.COMPLETE)
                .rowsExamined(12L)
                .rowsReturned(12L)
                .build());
    }
}
