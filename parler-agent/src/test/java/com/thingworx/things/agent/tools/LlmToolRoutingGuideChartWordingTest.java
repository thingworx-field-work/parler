package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

public class LlmToolRoutingGuideChartWordingTest {

    @Test
    public void routingGuide_containsNeutralChartWording() throws Exception {
        try (InputStream in = LlmToolRoutingGuideChartWordingTest.class.getResourceAsStream(
                "/com/thingworx/things/agent/llm_tool_routing_guide.txt")) {
            assertTrue(in != null, () -> "classpath resource missing");
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(s.contains("the rendered chart shows"));
            assertTrue(s.contains("by screen position"));
            assertFalse(s.contains("Parler renders the chart below your reply"));
        }
    }

    /** Resource-level guard so routing guide cannot regress alone. */
    @Test
    public void routingGuide_fetchCachedResultIsDisplayScopeNotFullTableComputation() throws Exception {
        try (InputStream in = LlmToolRoutingGuideChartWordingTest.class.getResourceAsStream(
                "/com/thingworx/things/agent/llm_tool_routing_guide.txt")) {
            assertTrue(in != null, () -> "classpath resource missing");
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(s.contains("`fetch_cached_result` pages data for display only"));
            assertTrue(s.contains("it does not sort or aggregate"));
            assertTrue(s.contains("Do not page an entire table into the model to calculate it"));
            assertTrue(s.contains("tabulate_cached_result"));
        }
    }

    /** Bug 005 Tier 2.1 — discourage P5: sort + sampleRows extrapolation; steer to group_metric having. */
    @Test
    public void routingGuide_groupMetricHavingNotSampleRowsExtrapolation() throws Exception {
        try (InputStream in = LlmToolRoutingGuideChartWordingTest.class.getResourceAsStream(
                "/com/thingworx/things/agent/llm_tool_routing_guide.txt")) {
            assertTrue(in != null, () -> "classpath resource missing");
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(s.contains("Use `having` for requested group thresholds"));
            assertTrue(s.contains("sorting a sample does not establish which groups satisfy a threshold"));
            assertTrue(s.contains("`sampleRows` is a preview/page; `totalRows` is the output row count"));
            assertTrue(s.contains("Neither proves that unseen rows share sampled values"));
        }
    }

    @Test
    public void routingGuide_entity_set_difference_mentions_analyze_then_tabulate() throws Exception {
        try (InputStream in = LlmToolRoutingGuideChartWordingTest.class.getResourceAsStream(
                "/com/thingworx/things/agent/llm_tool_routing_guide.txt")) {
            assertTrue(in != null, () -> "classpath resource missing");
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(s.contains("analyze_entity_set"));
            assertTrue(s.contains("`difference` (left minus right)"));
            assertTrue(s.contains("tabulate_cached_result"));
            assertFalse(s.contains("before **`analyze_entity_set`** exists"));
        }
    }

    @Test
    public void routingGuide_retiredHierarchyCompositeToolNamesAbsent() throws Exception {
        try (InputStream in = LlmToolRoutingGuideChartWordingTest.class.getResourceAsStream(
                "/com/thingworx/things/agent/llm_tool_routing_guide.txt")) {
            assertTrue(in != null, () -> "classpath resource missing");
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(s.contains("query_asset_count_under_hierarchy_node"));
            assertFalse(s.contains("compare_alert_status_between_hierarchy_nodes"));
        }
    }

    /** Alert routing is owned by the default alert block, not the routing guide (one owner per rule). */
    @Test
    public void defaultAlertBlock_multiThingAlertSummaryUsesThingNamesArray() throws Exception {
        String s = AlertPromptDefaults.DEFAULT_ALERT_PROMPT_MARKDOWN;
        assertTrue(s.contains("`thingNames` containing 1–25 canonical Things"));
        assertTrue(s.contains("ALERT_SUMMARY_MULTI"));
        assertFalse(s.contains("requires **`thingName`** per Thing"));
        try (InputStream in = LlmToolRoutingGuideChartWordingTest.class.getResourceAsStream(
                "/com/thingworx/things/agent/llm_tool_routing_guide.txt")) {
            assertTrue(in != null, () -> "classpath resource missing");
            String guide = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(guide.contains("query_alert_summary"), "alert rules must not be duplicated in the routing guide");
        }
    }

    @Test
    public void routingGuide_numericHistoryCompactEvidence() throws Exception {
        try (InputStream in = LlmToolRoutingGuideChartWordingTest.class.getResourceAsStream(
                "/com/thingworx/things/agent/llm_tool_routing_guide.txt")) {
            assertTrue(in != null, () -> "classpath resource missing");
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(s.contains("query_property_history") && s.contains("Numeric history text contains compact evidence"));
            assertTrue(s.contains("do not expect a full `points` array"));
            assertTrue(s.contains("use the exact `aggregates` object"));
        }
    }

    @Test
    public void routingGuide_historyOverlaySteering() throws Exception {
        try (InputStream in = LlmToolRoutingGuideChartWordingTest.class.getResourceAsStream(
                "/com/thingworx/things/agent/llm_tool_routing_guide.txt")) {
            assertTrue(in != null, () -> "classpath resource missing");
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(s.contains("build_history_overlay_chart"));
            assertTrue(s.contains("live property-history traces on one chart"));
            assertTrue(s.contains("absolute_time"));
            assertTrue(s.contains("elapsed_time"));
            assertTrue(s.contains("yReferenceLines"));
            assertFalse(s.contains("build_period_over_period_chart"));
            assertFalse(s.contains("build_multi_series_history_chart"));
            assertFalse(s.contains("POP_MULTI_SERIES_UNAVAILABLE"));
        }
    }

    @Test
    public void routingGuide_longTableMultiSeriesTabularSteering() throws Exception {
        try (InputStream in = LlmToolRoutingGuideChartWordingTest.class.getResourceAsStream(
                "/com/thingworx/things/agent/llm_tool_routing_guide.txt")) {
            assertTrue(in != null, () -> "classpath resource missing");
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(s.contains("For a long cached table, use `seriesColumn`"));
            assertTrue(s.contains("seriesColumn"));
        }
    }

    @Test
    public void routingGuide_multiSeriesDataSourceTabularCacheVsLiveHistory() throws Exception {
        try (InputStream in = LlmToolRoutingGuideChartWordingTest.class.getResourceAsStream(
                "/com/thingworx/things/agent/llm_tool_routing_guide.txt")) {
            assertTrue(in != null, () -> "classpath resource missing");
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(s.contains("Do not pivot or re-query history solely to assemble a chart"));
            assertTrue(s.contains("Prefer a suitable existing long cached table over new history retrieval"));
            assertTrue(s.contains("live property-history"));
        }
    }

    /** JSON root-rows chart source: guidance must state the eligibility conditions, not mere presence of rows. */
    @Test
    public void routingGuide_jsonRootRowsQualifyingSourceStatesEligibility() throws Exception {
        try (InputStream in = LlmToolRoutingGuideChartWordingTest.class.getResourceAsStream(
                "/com/thingworx/things/agent/llm_tool_routing_guide.txt")) {
            assertTrue(in != null, () -> "classpath resource missing");
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(s.contains("extended-tool JSON result whose decoded `result` object is a complete small single table"));
            assertTrue(s.contains("business `status` absent or `success`"));
            assertTrue(s.contains("no partial-page signal"));
            assertTrue(s.contains("Other JSON does not update `last_invoke`"));
        }
    }
}
