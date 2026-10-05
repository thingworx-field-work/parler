package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.types.BaseTypes;

/**
 * Characterization and close-condition tests for cache-adjacent tool discrepancies: intersect
 * completeness vocabulary (E1), reserved {@code start_playbook} name (E2), strict pre-HITL value
 * canonicalization (E3), shared history row limit (E10), and the alert ack batch limit (S7).
 * Close-condition tests exercise real production seams.
 */
class U1bSlice1B0EvidenceLockTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ScalarThingnamePreflight.resolveThingRecoverableOverrideForTests = true;
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> true;
    }

    @AfterEach
    void tearDown() {
        ScalarThingnamePreflight.clearModelGateOverrideForTests();
        SetPropertyValueExecutor.clearHitlTestOverrides();
        AlertToolsExecutor.clearAlertToolsExecutorTestHooks();
        AgentToolContext.clear();
    }

    // --- E1 characterization ---

    @Test
    void e1_nonIntersectWriterOmitsCompletenessVocabulary() {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("totalCount", 12);
        EntityHierarchyIntersectHelper.writeIntersectSuccessFields(out, false, 100, 12, true, false);
        assertFalse(out.has("hasMore"));
        assertFalse(out.has("truncated"));
        assertFalse(out.has("totalUnderlyingCount"));
        assertFalse(out.has("queryHasMore"));
        assertFalse(out.has("preIntersectMatchCount"));
        assertEquals(12, out.get("totalCount").asInt());
    }

    @Test
    void e1_close_nonIntersectTruncatedEmitsPublicVocabulary() {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("totalCount", 12);
        BoundedQueryCompleteness.Result r = BoundedQueryCompleteness.evaluate(
                BoundedQueryCompleteness.ListingValidity.VALID_TABLE,
                12,
                BoundedQueryCompleteness.PlatformTotal.ok(6000),
                5000,
                false,
                0,
                false,
                false);
        BoundedQueryCompleteness.writeNonIntersectFields(out, r);
        assertTrue(out.path("truncated").asBoolean(false),
                "non-intersect truncated path must emit truncated:true via production writer");
        assertEquals(6000, out.path("totalUnderlyingCount").asInt(-1));
        assertTrue(out.path("hasMore").asBoolean(false));
    }

    // --- E2 characterization ---

    @Test
    void e2_registryAloneStillOmitsStartPlaybookButAuthorityReservesIt() {
        // Registry + aliases still omit start_playbook; the reserved-name authority closes the
        // admission hole without registering a ToolDefinition.
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg);
        Set<String> registryOnly = new HashSet<>();
        for (ToolDefinition td : reg.getAllDefinitions()) {
            registryOnly.add(td.getName());
        }
        registryOnly.addAll(reg.getExecutorOnlyAliases());
        assertFalse(registryOnly.contains("start_playbook"));
        assertTrue(ReservedBuiltinToolNames.fromRegistry(reg).contains("start_playbook"));
    }

    @Test
    void e2_close_startPlaybookAlwaysReservedEvenWithoutPlaybookLoad() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg);
        assertTrue(ReservedBuiltinToolNames.fromRegistry(reg)
                .contains(ReservedBuiltinToolNames.START_PLAYBOOK));
        assertTrue(ReservedBuiltinToolNames.alwaysReservedDynamicNames()
                .contains(ReservedBuiltinToolNames.START_PLAYBOOK));
    }

    // --- E3: strict pre-HITL canonicalize ---

    @Test
    void e3_identityFailureStillYieldsNoGatedCall() throws Exception {
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        SetPropertyValueExecutor.clearHitlTestOverrides();
        ToolCall call = new ToolCall("1", "set_property_value",
                "{\"thingName\":\"Pump-1\",\"propertyName\":\"Enabled\",\"baseType\":\"BOOLEAN\",\"value\":\"maybe\"}");
        SetPropertyValueExecutor.SetPropertyHitlGate g = SetPropertyValueExecutor.gateSetPropertyValueForHitl(call);
        assertNotNull(g.earlyErrorJson());
        assertNull(g.gatedToolCall());
    }

    @Test
    void e3_close_invalidBooleanNeverCreatesPendingApproval() throws Exception {
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> true;
        SetPropertyValueExecutor.hitlPropertyBaseTypeOverrideForTests = (t, p) -> BaseTypes.BOOLEAN;
        ToolCall call = new ToolCall("1", "set_property_value",
                "{\"thingName\":\"Pump-1\",\"propertyName\":\"Enabled\",\"value\":\"maybe\"}");
        SetPropertyValueExecutor.SetPropertyHitlGate g = SetPropertyValueExecutor.gateSetPropertyValueForHitl(call);
        assertNotNull(g.earlyErrorJson());
        assertNull(g.gatedToolCall());
        assertEquals("INVALID_BOOLEAN_VALUE", MAPPER.readTree(g.earlyErrorJson()).path("code").asText());
        assertTrue(SetPropertyValueExecutor.canonicalizeRequestedValue(BaseTypes.BOOLEAN,
                MAPPER.readTree("\"maybe\"")).isError());
    }

    // --- E10 characterization ---

    private static final java.nio.file.Path PROPERTY_TOOLS_EXECUTOR_SRC = java.nio.file.Path.of(
            "src/main/java/com/thingworx/things/agent/tools/PropertyToolsExecutor.java");

    @Test
    void e10_bothBranchesCallSharedHistoryRowLimitHelper() throws Exception {
        // Production-source seam: both branches call HistoryRowLimitPrecedence.resolveHistoryRowLimit,
        // so the value-stream branch cannot overwrite the row limit.
        String src = java.nio.file.Files.readString(PROPERTY_TOOLS_EXECUTOR_SRC);
        String valueStream = methodBody(src, "doQueryValueStreamPropertyHistoryCompact");
        String numeric = methodBody(src, "doQueryNumericPropertyHistory");
        assertTrue(callsSharedHistoryRowLimitHelper(numeric),
                "numeric branch must call shared HistoryRowLimitPrecedence helper");
        assertTrue(callsSharedHistoryRowLimitHelper(valueStream),
                "value-stream branch must call shared HistoryRowLimitPrecedence helper");
        assertFalse(valueStreamOverwritesWithMaxPoints(valueStream),
                "value-stream must not overwrite maxRows with maxPoints");
    }

    @Test
    void e10_close_bothBranchesUseMaxRowsWins() throws Exception {
        // Production seam: both PropertyToolsExecutor branch bodies call the shared helper;
        // value-stream no longer overwrites with maxPoints.
        String src = java.nio.file.Files.readString(PROPERTY_TOOLS_EXECUTOR_SRC);
        String valueStream = methodBody(src, "doQueryValueStreamPropertyHistoryCompact");
        String numeric = methodBody(src, "doQueryNumericPropertyHistory");
        assertTrue(usesMaxRowsWinsElseIf(numeric) || callsSharedHistoryRowLimitHelper(numeric),
                "numeric branch must keep maxRows-wins");
        assertTrue(usesMaxRowsWinsElseIf(valueStream) || callsSharedHistoryRowLimitHelper(valueStream),
                "value-stream must use maxRows-wins (not maxPoints overwrite)");
        assertFalse(valueStreamOverwritesWithMaxPoints(valueStream),
                "value-stream must not overwrite maxRows with maxPoints");
        // Helper dual-present fixture (same production authority the branches call).
        ObjectNode dual = MAPPER.createObjectNode();
        dual.put("maxRows", 50);
        dual.put("maxPoints", 200);
        assertEquals(50, HistoryRowLimitPrecedence.resolveHistoryRowLimit(dual, 5000));
    }

    /** True when the branch body uses numeric-style {@code if (maxRows) … else if (maxPoints)}. */
    private static boolean usesMaxRowsWinsElseIf(String methodBody) {
        return methodBody.contains("else if (root.has(\"maxPoints\"))");
    }

    /** True when the branch calls the shared history row-limit helper. */
    private static boolean callsSharedHistoryRowLimitHelper(String methodBody) {
        return methodBody.contains("resolveHistoryRowLimit")
                || methodBody.contains("HistoryRowLimitPrecedence")
                || methodBody.contains("propertyHistoryRowLimit");
    }

    /** True when value-stream still does unconditional maxPoints overwrite after maxRows init. */
    private static boolean valueStreamOverwritesWithMaxPoints(String methodBody) {
        int elseIfMaxPoints = methodBody.indexOf("else if (root.has(\"maxPoints\"))");
        if (elseIfMaxPoints >= 0) {
            return false;
        }
        int maxRowsInit = methodBody.indexOf("root.has(\"maxRows\")");
        int maxPointsOverwrite = methodBody.indexOf("if (root.has(\"maxPoints\"))");
        return maxRowsInit >= 0 && maxPointsOverwrite >= 0;
    }

    /** Extract a {@code private static} method definition body (not a call site). */
    private static String methodBody(String src, String methodName) {
        String sig = "private static String " + methodName + "(";
        int start = src.indexOf(sig);
        assertTrue(start >= 0, "missing definition " + methodName);
        int brace = src.indexOf('{', start);
        assertTrue(brace > start, "missing body for " + methodName);
        int depth = 0;
        for (int i = brace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(brace, i + 1);
                }
            }
        }
        throw new AssertionError("unclosed body for " + methodName);
    }

    // --- S7 characterization ---

    @Test
    void s7_policyProbeIsOnePastMaxBatchAndExceedsAt501() {
        assertEquals(500, AlertSpecificAckPolicy.MAX_BATCH_ROWS);
        assertEquals(501, AlertSpecificAckPolicy.PROBE_MAX_ITEMS);
        assertEquals(AlertSpecificAckPolicy.ProbeOutcome.EXCEEDS_LIMIT,
                AlertSpecificAckPolicy.classifyUnackedRowCount(501));
        assertEquals(AlertSpecificAckPolicy.ProbeOutcome.WITHIN_LIMIT,
                AlertSpecificAckPolicy.classifyUnackedRowCount(500));
    }

    @Test
    void s7_executorWiresProbeMaxToPolicyConstant() throws Exception {
        var field = AlertToolsExecutor.class.getDeclaredField("ACK_SUMMARY_PROBE_ITEMS");
        field.setAccessible(true);
        assertEquals(AlertSpecificAckPolicy.PROBE_MAX_ITEMS, field.getInt(null));
    }

    @Test
    void s7_close_executorLevelExceedsLimitPerformsNoWrite() throws Exception {
        // Production seam: AlertToolsExecutor.doAcknowledgeAlerts classify-before-write (same path as
        // AlertSpecificAckExecutorGateTest). Green only because EXCEEDS_LIMIT returns before Acknowledge*.
        java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
        AlertToolsExecutor.specificAlertsAckWriteAttemptsForTests = writes;
        AlertToolsExecutor.ackSummaryProbeOverrideForTests = () -> {
            com.thingworx.metadata.DataShapeDefinition dsd = new com.thingworx.metadata.DataShapeDefinition();
            com.thingworx.types.InfoTable it = new com.thingworx.types.InfoTable(dsd);
            for (int i = 0; i < 501; i++) {
                it.addRow(new com.thingworx.types.collections.ValueCollection());
            }
            return new AlertSummaryAckProbe.SummaryProbeOutcome(it, null);
        };
        ToolCall call = new ToolCall("1", "acknowledge_alerts",
                "{\"thingName\":\"Pump-1\",\"mode\":\"specific_alerts\",\"propertyName\":\"OverTemp\"}");
        var n = MAPPER.readTree(AlertToolsExecutor.executeAcknowledgeAlerts(call));
        assertEquals("ACK_MATCHES_EXCEED_LIMIT", n.path("code").asText());
        assertEquals(0, writes.get(), "regression: EXCEEDS_LIMIT must not invoke ack write");
    }
}
