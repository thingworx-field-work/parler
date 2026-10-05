package com.thingworx.things.agent.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.analysis.ComputingOperationAdmission;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.llm.ToolSchemaSizer;
import com.thingworx.things.agent.tools.ToolAdmissionPolicy;

class M1aBaselinePinTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Pinned census-baseline.json off-mode sevenPriorityEditableTotal (step 1 baseline). */
    private static final int SEVEN_PRIORITY_EDITABLE_BEFORE = 31_846;

    /** CC-1.4: at least 10% reduction → ceiling floor 28,661. */
    private static final int SEVEN_PRIORITY_EDITABLE_CEILING = 28_661;

    @Test
    void offMode_matchesPinnedCensusBaseline() throws Exception {
        JsonNode expected = loadPinnedBaseline();
        List<ToolDefinition> tools = M1aBaselineSupport.buildOffModeTools();
        assertEquals(29, tools.size(), "update baseline when BuiltInTools.registerAll or fixture changes");
        assertEquals(5, expected.get("baselineVersion").asInt());

        assertCombination(expected, "off", M1aBaselineSupport.OPENAI_API_SHAPE, tools, null);
        assertCombination(expected, "off", M1aBaselineSupport.ANTHROPIC_API_SHAPE, tools, null);
    }

    @Test
    void lazyFirstRound_matchesPinnedCensusBaseline() throws Exception {
        JsonNode expected = loadPinnedBaseline();
        ToolAdmissionPolicy.LazyResult lazy = M1aBaselineSupport.buildLazyFirstRoundResult();
        List<ToolDefinition> tools = M1aBaselineSupport.buildLazyFirstRoundTools();
        assertTrue(tools.stream().anyMatch(t -> "load_tool_schemas".equals(t.getName())));

        assertCombination(expected, "lazy", M1aBaselineSupport.OPENAI_API_SHAPE, tools, lazy);
        assertCombination(expected, "lazy", M1aBaselineSupport.ANTHROPIC_API_SHAPE, tools, lazy);
    }

    /**
     * {@code census-baseline.json} keeps the original pre-reduction census (routing guide 28,501 UTF-8 bytes). The
     * bundled guide was rewritten in the concise form afterwards, so the live value is asserted as a strict
     * reduction against that preserved "before" figure rather than as equality; the other two fields are still
     * exact pins.
     */
    @Test
    void routingGuideChars_reducedFromPinnedBaseline() throws Exception {
        JsonNode expected = loadPinnedBaseline();
        int before = expected.get("routingGuideChars").asInt();
        int live = M1aBaselineSupport.routingGuideChars();
        assertTrue(live > 0, "routing guide resource must be present");
        assertTrue(live < before, () -> "routing guide bytes must stay below the original census (before=" + before
                + " live=" + live + ")");
        assertEquals(expected.get("workflowHostContextUtf8Chars").asInt(),
                M1aBaselineSupport.workflowHostContextUtf8Chars());
        assertEquals(expected.get("taxonomyAdmissionContextUtf8Chars").asInt(),
                M1aBaselineSupport.taxonomyAdmissionContextUtf8Chars());
    }

    private static JsonNode loadPinnedBaseline() throws Exception {
        try (InputStream in = M1aBaselinePinTest.class.getResourceAsStream(
                "/context-compaction/m1a/census-baseline.json")) {
            assertNotNull(in, "missing pinned baseline resource");
            return JSON.readTree(in);
        }
    }

    private static void assertCombination(JsonNode expectedRoot, String mode, String apiShapeId,
            List<ToolDefinition> tools, ToolAdmissionPolicy.LazyResult lazyResult)
            throws Exception {
        JsonNode combo = findCombination(expectedRoot, mode, apiShapeId);
        assertNotNull(combo, "missing combination " + mode + " / " + apiShapeId);

        int beforeRoutingGuideChars = expectedRoot.get("routingGuideChars").asInt();
        int beforeToolSchemaChars = combo.get("toolSchemaChars").asInt();
        int beforeCombined = beforeToolSchemaChars + beforeRoutingGuideChars;

        int liveToolSchemaChars = ToolSchemaSizer.totalSchemaChars(apiShapeId, tools);
        int liveRoutingGuideChars = M1aBaselineSupport.routingGuideChars();
        int liveCombined = liveToolSchemaChars + liveRoutingGuideChars;

        assertStructureUnchanged(combo, tools);
        assertTrue(liveCombined <= beforeCombined,
                () -> "toolSchemaChars+routingGuideChars must not grow for " + mode + " / " + apiShapeId
                        + " (before=" + beforeCombined + " live=" + liveCombined + ")");

        if ("off".equals(mode)) {
            assertTrue(liveToolSchemaChars < beforeToolSchemaChars,
                    () -> "off-mode toolSchemaChars must strictly decrease (before=" + beforeToolSchemaChars
                            + " live=" + liveToolSchemaChars + ")");
            // CC-1.4 (scoped revision): the 10% dedup criterion is measured on the tool
            // surface its step-1 baseline had, i.e. with every computing mode withdrawn. Computing descriptions
            // have their own cap (TabulateCalendarBucketModeTest.descriptionAndSchemaBudgets_hold). Only this
            // measurement runs all-off; the fixture and every other assertion here stay all-on.
            int liveEditable = ComputingOperationAdmission.callWithAllDisabled(
                    () -> M1aEditableDescriptionMetrics.sevenPriorityToolsEditableTotal(
                            M1aBaselineSupport.buildOffModeTools()));
            assertTrue(liveEditable < SEVEN_PRIORITY_EDITABLE_BEFORE,
                    () -> "sevenPriorityEditableTotal must decrease from step-1 baseline (before="
                            + SEVEN_PRIORITY_EDITABLE_BEFORE + " live=" + liveEditable + ")");
            assertTrue(liveEditable <= SEVEN_PRIORITY_EDITABLE_CEILING,
                    () -> "sevenPriorityEditableTotal must be at least 10% lower (ceiling="
                            + SEVEN_PRIORITY_EDITABLE_CEILING + " live=" + liveEditable + ")");
        }

        if ("lazy".equals(mode)) {
            assertLazyCatalogIdentityUnchanged(combo, lazyResult, loadPinnedChecklist());
        }
    }

    private static JsonNode loadPinnedChecklist() throws Exception {
        return M1aSemanticPreservationSupport.loadChecklist();
    }

    private static void assertStructureUnchanged(JsonNode combo, List<ToolDefinition> tools) throws Exception {
        assertEquals(combo.get("toolCount").asInt(), tools.size());

        JsonNode expectedNames = combo.get("toolNamesOrdered");
        assertNotNull(expectedNames);
        Iterator<String> liveNames = tools.stream().map(ToolDefinition::getName).iterator();
        for (JsonNode nameNode : expectedNames) {
            assertTrue(liveNames.hasNext(), "toolNamesOrdered longer than live tool list");
            assertEquals(nameNode.asText(), liveNames.next());
        }
        assertFalse(liveNames.hasNext(), "live tool list longer than toolNamesOrdered");

        JsonNode expectedProjections = combo.get("parameterStructureProjections");
        assertNotNull(expectedProjections);
        for (ToolDefinition tool : tools) {
            if (tool == null || tool.getName() == null) {
                continue;
            }
            JsonNode expectedProjection = expectedProjections.get(tool.getName());
            assertNotNull(expectedProjection, "missing projection for " + tool.getName());
            assertEquals(expectedProjection.asText(),
                    M1aDescriptionProjection.structuralProjectionJson(tool.getParametersSchema()),
                    () -> "parameterStructureProjections mismatch for " + tool.getName());
        }
    }

    private static void assertLazyCatalogIdentityUnchanged(JsonNode combo,
            ToolAdmissionPolicy.LazyResult lazyResult, JsonNode semanticChecklist) {
        assertNotNull(lazyResult);
        JsonNode expectedCatalog = combo.get("lazyCatalog");
        assertNotNull(expectedCatalog);

        JsonNode expectedEntries = expectedCatalog.get("entries");
        assertEquals(expectedEntries.size(), lazyResult.catalog().size());
        for (int i = 0; i < lazyResult.catalog().size(); i++) {
            ToolAdmissionPolicy.CatalogEntry liveEntry = lazyResult.catalog().get(i);
            JsonNode expectedEntry = expectedEntries.get(i);
            assertEquals(expectedEntry.get("name").asText(), liveEntry.name());
        }

        M1aSemanticPreservationSupport.assertLazyCatalogBlurbs(lazyResult, semanticChecklist);

        JsonNode expectedAdvertised = expectedCatalog.get("advertisedNames");
        assertNotNull(expectedAdvertised);
        Iterator<String> liveAdvertised = lazyResult.advertised().stream().map(ToolDefinition::getName).iterator();
        for (JsonNode nameNode : expectedAdvertised) {
            assertTrue(liveAdvertised.hasNext());
            assertEquals(nameNode.asText(), liveAdvertised.next());
        }
        assertFalse(liveAdvertised.hasNext());
    }

    private static JsonNode findCombination(JsonNode root, String mode, String apiShapeId) {
        for (JsonNode node : root.get("combinations")) {
            if (mode.equals(node.get("admissionMode").asText())
                    && apiShapeId.equals(node.get("apiShapeId").asText())) {
                return node;
            }
        }
        return null;
    }
}
