package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.HistorySeriesComposerSupport;
import com.thingworx.things.agent.ToolResultEgressGateway;
import com.thingworx.things.agent.analysis.U5OperationAdmission;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * CM-2 acceptance (design §7, cases a–p). Generality first: any producer that declares roles gets the
 * same feedback; caches without roles get schema and case repair only; nothing is inferred from tool
 * names, column names or types.
 */
class AnalyzeCachedResultProjectionErrorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
        AgentToolContext.setConversationId("analyze-projection-error");
        U5OperationAdmission.resetForTests();
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
        U5OperationAdmission.resetForTests();
    }

    // ---------- fixtures ----------

    /** A table with a DATETIME column, one or more NUMBER columns and one STRING column. */
    private static InfoTable table(String timeName, List<String> numberNames, String labelName, int rows) {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition ts = new FieldDefinition();
        ts.setName(timeName);
        ts.setBaseType(BaseTypes.DATETIME);
        shape.addFieldDefinition(ts);
        for (String n : numberNames) {
            FieldDefinition f = new FieldDefinition();
            f.setName(n);
            f.setBaseType(BaseTypes.NUMBER);
            shape.addFieldDefinition(f);
        }
        if (labelName != null) {
            FieldDefinition l = new FieldDefinition();
            l.setName(labelName);
            l.setBaseType(BaseTypes.STRING);
            shape.addFieldDefinition(l);
        }
        InfoTable t = new InfoTable(shape);
        for (int i = 0; i < rows; i++) {
            ValueCollection row = new ValueCollection();
            row.put(timeName, new DatetimePrimitive(new DateTime(T0.plusSeconds(i).toEpochMilli())));
            for (String n : numberNames) {
                row.put(n, new NumberPrimitive(i + 1.0));
            }
            if (labelName != null) {
                row.put(labelName, new StringPrimitive("s" + i));
            }
            t.addRow(row);
        }
        return t;
    }

    /** Any producer declaring roles through the general writer entry (not a history tool). */
    private static String storeWithRoles(InfoTable t, String time, String value) throws Exception {
        return NumericHistoryCacheWriter.storeWithRoles(t, "test.producer", time, value, null).cacheId();
    }

    private static String storeNoRoles(InfoTable t) throws Exception {
        return TabularArtifactHub.store(t, SourceDescriptor.builder().sourceRouteId("test.producer").build());
    }

    private static JsonNode analyze(String argsJson) throws Exception {
        return MAPPER.readTree(AnalyzeCachedResultExecutor.execute(
                new ToolCall("c1", AnalyzeCachedResultExecutor.TOOL_NAME, argsJson)));
    }

    private static String outlier(String cacheId, String time, String value) {
        return "{\"operation\":\"outlier\",\"cacheId\":\"" + cacheId + "\",\"timeColumn\":\"" + time
                + "\",\"valueColumn\":\"" + value + "\"}";
    }

    private static String relationship(String left, String right, String time, String value, String rightValue) {
        return "{\"operation\":\"relationship\",\"cacheId\":\"" + left + "\",\"rightCacheId\":\"" + right
                + "\",\"timeColumn\":\"" + time + "\",\"valueColumn\":\"" + value + "\""
                + (rightValue == null ? "" : ",\"rightValueColumn\":\"" + rightValue + "\"")
                + ",\"alignment\":\"nearest\",\"toleranceMillis\":1000}";
    }

    private static void assertOuterErrorShape(JsonNode err, String rejected, String cacheId) {
        assertEquals("ERROR", err.path("status").asText());
        assertEquals("ARGUMENT_MISSING", err.path("reason").asText());
        assertTrue(err.path("detail").asText().startsWith("unknown projected column: "), err.toString());
        assertFalse(err.path("mayPublish").asBoolean());
        assertEquals(rejected, err.path("rejectedParameter").asText());
        assertEquals(cacheId, err.path("cacheId").asText());
        assertTrue(err.has("schema"), err.toString());
        assertFalse(err.has("recoveryActions"), "no G18 action in CM-S1");
        assertFalse(err.has("retryable"), "no server retry claim in CM-S1");
    }

    // ---------- cases ----------

    @Test
    void a_anyProducerDeclaringRoles_getsRoleSuggestionWithArbitraryNames() throws Exception {
        String cacheId = storeWithRoles(table("observedAt", List.of("reading"), "site", 8), "observedAt", "reading");
        JsonNode err = analyze(outlier(cacheId, "observedAt", "temperature"));
        assertOuterErrorShape(err, "valueColumn", cacheId);
        assertEquals("explicit", err.path("parameterSource").asText());
        assertFalse(err.has("side"));
        assertEquals(3, err.path("schema").path("columnCount").asInt());
        assertFalse(err.path("schema").path("truncated").asBoolean());
        assertEquals("reading", err.path("columnSuggestion").path("valueColumn").asText());
    }

    @Test
    void b_sameTwoColumnShapeWithoutRoles_getsSchemaOnly() throws Exception {
        String cacheId = storeNoRoles(table("timestamp", List.of("value"), null, 8));
        JsonNode err = analyze(outlier(cacheId, "timestamp", "operationalVoltage"));
        assertOuterErrorShape(err, "valueColumn", cacheId);
        assertEquals(2, err.path("schema").path("columns").size());
        assertFalse(err.has("columnSuggestion"), "no role, no name/type inference: " + err);
    }

    @Test
    void c_uniqueNumericButUnrelatedName_getsSchemaOnly() throws Exception {
        String cacheId = storeNoRoles(table("ts", List.of("temperature"), null, 8));
        JsonNode err = analyze(outlier(cacheId, "ts", "pressure"));
        assertOuterErrorShape(err, "valueColumn", cacheId);
        assertFalse(err.has("columnSuggestion"), "a single numeric column is not semantic uniqueness");
    }

    @Test
    void d_missingConflictingOrStaleRoles_giveNoSuggestion() throws Exception {
        String conflict = storeWithRoles(table("ts", List.of("v"), null, 8), "v", "v");
        JsonNode err1 = analyze(outlier(conflict, "ts", "bogus"));
        assertOuterErrorShape(err1, "valueColumn", conflict);
        assertFalse(err1.has("columnSuggestion"));

        String stale = TabularArtifactHub.store(table("ts", List.of("v"), null, 8),
                SourceDescriptor.builder().sourceRouteId("test.producer").timeColumn("ts").valueColumn("gone").build());
        JsonNode err2 = analyze(outlier(stale, "ts", "bogus"));
        assertOuterErrorShape(err2, "valueColumn", stale);
        assertFalse(err2.has("columnSuggestion"), "a role naming an absent column is not trusted");
    }

    @Test
    void e_uniqueCaseMatchIsSuggested_caseCollisionIsNot() throws Exception {
        String unique = storeNoRoles(table("ts", List.of("value"), null, 8));
        JsonNode err1 = analyze(outlier(unique, "ts", "Value"));
        assertEquals("value", err1.path("columnSuggestion").path("valueColumn").asText());

        String collision = storeNoRoles(table("ts", List.of("Value", "value"), null, 8));
        JsonNode err2 = analyze(outlier(collision, "ts", "VALUE"));
        assertOuterErrorShape(err2, "valueColumn", collision);
        assertFalse(err2.has("columnSuggestion"));
    }

    @Test
    void f_overlayRegression_propertyNameAsValueColumn() throws Exception {
        String left = HistorySeriesComposerSupport.storeNumericHistoryPointsInConversationCache(points(6)).cacheId();
        String right = HistorySeriesComposerSupport.storeNumericHistoryPointsInConversationCache(points(6)).cacheId();
        JsonNode err = analyze(relationship(left, right, "timestamp", "operationalVoltage", "contactForce"));
        assertOuterErrorShape(err, "valueColumn", left);
        assertEquals("left", err.path("side").asText());
        assertEquals("value", err.path("columnSuggestion").path("valueColumn").asText());
        assertEquals("timestamp", err.path("schema").path("columns").get(0).path("name").asText());
    }

    @Test
    void g_rightSideFailure_reportsRightSchemaAndRightRoles() throws Exception {
        String left = storeWithRoles(table("t", List.of("v"), null, 8), "t", "v");
        String right = storeWithRoles(table("t", List.of("reading"), "site", 8), "t", "reading");
        JsonNode err = analyze(relationship(left, right, "t", "v", "bogus"));
        assertOuterErrorShape(err, "rightValueColumn", right);
        assertEquals("right", err.path("side").asText());
        assertEquals("explicit", err.path("parameterSource").asText());
        assertEquals(3, err.path("schema").path("columnCount").asInt(), "right table's schema, not left's");
        assertEquals("reading", err.path("columnSuggestion").path("rightValueColumn").asText());
    }

    @Test
    void h_inheritedRightValueColumn_isReportedAsInherited() throws Exception {
        String left = storeWithRoles(table("t", List.of("v"), null, 8), "t", "v");
        String right = storeWithRoles(table("t", List.of("reading"), null, 8), "t", "reading");
        JsonNode err = analyze(relationship(left, right, "t", "v", null));
        assertOuterErrorShape(err, "rightValueColumn", right);
        assertEquals("inherited:valueColumn", err.path("parameterSource").asText());
        assertEquals("reading", err.path("columnSuggestion").path("rightValueColumn").asText());
    }

    @Test
    void i_sharedTimeColumnMissingOnRight_getsNoSuggestionEvenWithRole() throws Exception {
        String left = storeWithRoles(table("t", List.of("v"), null, 8), "t", "v");
        String right = storeWithRoles(table("observedAt", List.of("reading"), null, 8), "observedAt", "reading");
        JsonNode err = analyze(relationship(left, right, "t", "v", "reading"));
        assertOuterErrorShape(err, "timeColumn", right);
        assertEquals("right", err.path("side").asText());
        assertFalse(err.has("columnSuggestion"), "left already accepted timeColumn; never rename it from the right");
    }

    @Test
    void j_sameBadStringForBothParameters_reportsFirstFailingPosition() throws Exception {
        String cacheId = storeWithRoles(table("t", List.of("v"), null, 8), "t", "v");
        JsonNode err = analyze(outlier(cacheId, "x", "x"));
        assertOuterErrorShape(err, "timeColumn", cacheId);
        assertEquals("t", err.path("columnSuggestion").path("timeColumn").asText());
    }

    @Test
    void k_badCacheId_keepsExistingCacheMissPath() throws Exception {
        JsonNode err = analyze(outlier("00000000-0000-0000-0000-000000000000", "t", "v"));
        assertEquals("ERROR", err.path("status").asText());
        assertEquals("CACHE_MISS", err.path("reason").asText());
        assertFalse(err.has("rejectedParameter"));
        assertFalse(err.has("schema"));
    }

    @Test
    void l_correctColumns_stillSucceed() throws Exception {
        String cacheId = storeWithRoles(table("observedAt", List.of("reading"), null, 12), "observedAt", "reading");
        JsonNode ok = analyze(outlier(cacheId, "observedAt", "reading"));
        assertEquals("OK", ok.path("status").asText(), ok.toString());
        assertFalse(ok.has("schema"));
        assertFalse(ok.has("columnSuggestion"));
    }

    @Test
    void m_rightTimeColumnMissWithUniqueCaseMatch_gateWinsOverSpelling() throws Exception {
        String left = storeWithRoles(table("t", List.of("v"), null, 8), "t", "v");
        String right = storeWithRoles(table("T", List.of("reading"), null, 8), "T", "reading");
        JsonNode err = analyze(relationship(left, right, "t", "v", "reading"));
        assertOuterErrorShape(err, "timeColumn", right);
        assertFalse(err.has("columnSuggestion"));
    }

    @Test
    void n_spellingMatchBeatsRole() throws Exception {
        String cacheId = storeWithRoles(table("ts", List.of("Reading", "value"), null, 8), "ts", "value");
        JsonNode err = analyze(outlier(cacheId, "ts", "reading"));
        assertEquals("Reading", err.path("columnSuggestion").path("valueColumn").asText(),
                "explicit selection (case repair) wins over the declared role");
    }

    @Test
    void o_caseCollisionWithRole_doesNotFallThroughToRole() throws Exception {
        String cacheId = storeWithRoles(table("ts", List.of("Value", "value"), null, 8), "ts", "value");
        JsonNode err = analyze(outlier(cacheId, "ts", "VALUE"));
        assertOuterErrorShape(err, "valueColumn", cacheId);
        assertFalse(err.has("columnSuggestion"));
    }

    @Test
    void p_wideSchema_isBoundedExactAndSurvivesErrorEgress() throws Exception {
        List<String> numbers = new java.util.ArrayList<>();
        for (int i = 1; i <= 80; i++) {
            numbers.add(String.format("c%03d", i));
        }
        String longName = "x".repeat(ToolResultEgressGateway.hardTextExcerptChars() + 100);
        numbers.add(longName);
        String cacheId = storeWithRoles(table("ts", numbers, null, 4), "ts", "c001");

        String raw = AnalyzeCachedResultExecutor.execute(new ToolCall("c1", AnalyzeCachedResultExecutor.TOOL_NAME,
                outlier(cacheId, "ts", "zzz")));
        JsonNode err = MAPPER.readTree(raw);
        JsonNode schema = err.path("schema");
        assertEquals(82, schema.path("columnCount").asInt());
        assertTrue(schema.path("truncated").asBoolean());
        assertTrue(schema.path("columns").size() <= AnalyzeProjectionDiagnostics.MAX_SCHEMA_COLUMNS);
        assertTrue(MAPPER.writeValueAsString(schema).length() <= AnalyzeProjectionDiagnostics.MAX_SCHEMA_CHARS,
                "public schema is bounded by its serialized length: " + schema);
        for (JsonNode col : schema.path("columns")) {
            String name = col.path("name").asText();
            assertTrue(name.equals("ts") || name.matches("c\\d{3}"), "names are exact, never clipped: " + name);
        }
        assertEquals("c001", err.path("columnSuggestion").path("valueColumn").asText());

        // End to end through the existing error egress: identical names and suggestion.
        ToolResultEgressGateway.EgressResult egress = ToolResultEgressGateway.compactForLlmAppend(
                AnalyzeCachedResultExecutor.TOOL_NAME, "c1", raw, null);
        JsonNode seen = MAPPER.readTree(egress.getLlmContent());
        assertEquals(schema.path("columns"), seen.path("schema").path("columns"));
        assertEquals("c001", seen.path("columnSuggestion").path("valueColumn").asText());
        assertEquals("ARGUMENT_MISSING", seen.path("reason").asText());

        // A suggested column whose name the egress would excerpt is unrepresentable: no suggestion.
        String cacheId2 = storeWithRoles(table("ts", List.of(longName), null, 4), "ts", longName);
        JsonNode err2 = analyze(outlier(cacheId2, "ts", "zzz"));
        assertTrue(err2.path("schema").path("truncated").asBoolean());
        assertFalse(err2.has("columnSuggestion"));
        assertEquals(0, err2.path("schema").path("columns").size() - countNamed(err2, "ts"));
    }

    @Test
    void q_escapedLongNames_stayExactAndBounded() throws Exception {
        List<String> numbers = new java.util.ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            numbers.add("c" + i + "\\".repeat(300) + "\"q");
        }
        String cacheId = storeWithRoles(table("ts", numbers, null, 3), "ts", numbers.get(5));
        String raw = AnalyzeCachedResultExecutor.execute(new ToolCall("c1", AnalyzeCachedResultExecutor.TOOL_NAME,
                outlier(cacheId, "ts", "zzz")));
        JsonNode err = MAPPER.readTree(raw);
        JsonNode schema = err.path("schema");
        assertEquals(7, schema.path("columnCount").asInt());
        assertTrue(schema.path("truncated").asBoolean());
        assertTrue(MAPPER.writeValueAsString(schema).length() <= AnalyzeProjectionDiagnostics.MAX_SCHEMA_CHARS,
                "escaping counts toward the bound: " + MAPPER.writeValueAsString(schema).length());
        for (JsonNode col : schema.path("columns")) {
            String name = col.path("name").asText();
            assertTrue(name.equals("ts") || numbers.contains(name), "exact name or omitted, never clipped: " + name);
        }
        // The suggested (role) column is kept by dropping others from the end.
        assertEquals(numbers.get(5), err.path("columnSuggestion").path("valueColumn").asText());
        assertEquals(1, countNamed(err, numbers.get(5)));
        JsonNode seen = MAPPER.readTree(ToolResultEgressGateway.compactForLlmAppend(
                AnalyzeCachedResultExecutor.TOOL_NAME, "c1", raw, null).getLlmContent());
        assertEquals(schema, seen.path("schema"));
    }

    private static int countNamed(JsonNode err, String name) {
        int n = 0;
        for (JsonNode col : err.path("schema").path("columns")) {
            if (name.equals(col.path("name").asText())) {
                n++;
            }
        }
        return n;
    }

    private static List<HistorySeriesComposerSupport.HistoryPoint> points(int n) {
        List<HistorySeriesComposerSupport.HistoryPoint> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new HistorySeriesComposerSupport.HistoryPoint(T0.plusSeconds(60L * i), 10.0 + i));
        }
        return out;
    }
}
