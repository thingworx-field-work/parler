package com.thingworx.things.agent.compaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.tools.ToolAdmissionPolicy;

/** CC-1.4 semantic preservation evidence for M1a description reduction. */
class M1aSemanticPreservationTest {

    private static final List<String> LAZY_DEFERRED_PRIORITY = List.of(
            "query_entities",
            "invoke_service",
            "query_property_history",
            "build_history_overlay_chart");

    @Test
    void priorityTools_matchSemanticChecklistOffMode() throws Exception {
        JsonNode checklist = M1aSemanticPreservationSupport.loadChecklist();
        List<ToolDefinition> tools = M1aBaselineSupport.buildOffModeTools();
        M1aSemanticPreservationSupport.assertPriorityToolSemantics(tools, checklist);
        M1aSemanticPreservationSupport.assertAppendixIndependentSemantics(tools, checklist);
        M1aSemanticPreservationSupport.assertTabulateFilterPredicateKeys();
        M1aSemanticPreservationSupport.assertInvokeServiceParametersNestedRule();
    }

    @Test
    void priorityTools_schemaStandsAloneWithRoutingGuideAppendDisabled() throws Exception {
        JsonNode checklist = M1aSemanticPreservationSupport.loadChecklist();
        M1aSemanticPreservationSupport.assertRoutingGuideAppendSwitch();
        List<ToolDefinition> tools = M1aBaselineSupport.buildOffModeTools();
        M1aSemanticPreservationSupport.assertAppendixIndependentSemantics(tools, checklist);
        M1aSemanticPreservationSupport.assertPriorityToolSemantics(tools, checklist);
    }

    @Test
    void priorityTools_schemaUnchangedWhenRoutingGuideAppendEnabled() throws Exception {
        JsonNode checklist = M1aSemanticPreservationSupport.loadChecklist();
        M1aSemanticPreservationSupport.assertRoutingGuideAppendSwitch();
        List<ToolDefinition> tools = M1aBaselineSupport.buildOffModeTools();
        M1aSemanticPreservationSupport.assertPriorityToolSemantics(tools, checklist);
    }

    @Test
    void lazyFirstRound_catalogBlurbsRetainIdentifiablePurpose() throws Exception {
        JsonNode checklist = M1aSemanticPreservationSupport.loadChecklist();
        ToolAdmissionPolicy.LazyResult lazy = M1aBaselineSupport.buildLazyFirstRoundResult();
        M1aSemanticPreservationSupport.assertLazyCatalogBlurbs(lazy, checklist);

        List<String> deferredPriority = lazy.catalog().stream()
                .map(ToolAdmissionPolicy.CatalogEntry::name)
                .filter(M1aEditableDescriptionMetrics.PRIORITY_TOOL_NAMES::contains)
                .collect(Collectors.toList());
        assertFalse(deferredPriority.isEmpty(), "expected deferred priority tools in lazy catalog");
    }

    @Test
    void lazyAdvertisedPriorityTools_keepFullSchemaSemantics() throws Exception {
        JsonNode checklist = M1aSemanticPreservationSupport.loadChecklist();
        List<ToolDefinition> lazyTools = M1aBaselineSupport.buildLazyFirstRoundTools();
        for (String name : List.of(
                "tabulate_cached_result", "build_chart_from_tabular_result", "query_entities_by_taxonomy")) {
            M1aSemanticPreservationSupport.assertToolByName(lazyTools, checklist, name);
        }
    }

    @Test
    void lazyDeferredPriorityTools_afterLoad_keepFullSchemaSemantics() throws Exception {
        JsonNode checklist = M1aSemanticPreservationSupport.loadChecklist();
        M1aSemanticPreservationSupport.assertDeferredPriorityToolsLoadEnvelopeAndSubsequentLazyRound(
                LAZY_DEFERRED_PRIORITY, checklist);
    }

    @Test
    void lazyBlurb_negativeTruncatedBlurbsFailRequiredFragments() throws Exception {
        JsonNode checklist = M1aSemanticPreservationSupport.loadChecklist();
        ToolAdmissionPolicy.LazyResult lazy = M1aBaselineSupport.buildLazyFirstRoundResult();
        for (ToolAdmissionPolicy.CatalogEntry entry : lazy.catalog()) {
            if (!M1aEditableDescriptionMetrics.PRIORITY_TOOL_NAMES.contains(entry.name())) {
                continue;
            }
            JsonNode spec = checklist.get("tools").get(entry.name());
            if (spec == null || !spec.has("lazyBlurbMustContain")) {
                continue;
            }
            assertTrue(M1aSemanticPreservationSupport.lazyBlurbMatchesChecklist(
                    entry.name(), entry.blurb(), checklist));
            String truncated = entry.blurb().substring(0, Math.min(24, entry.blurb().length()));
            assertFalse(M1aSemanticPreservationSupport.lazyBlurbMatchesChecklist(
                    entry.name(), truncated, checklist),
                    () -> "truncated blurb must fail checklist for " + entry.name() + ": " + truncated);
        }
    }

    @Test
    void tabulateFilterBrief_negativeWouldFailWithoutPredicateKeys() {
        M1aSemanticPreservationSupport.assertTabulateFilterPredicateKeys();
        @SuppressWarnings("unchecked")
        var props = (java.util.Map<String, Object>) com.thingworx.things.agent.tools.TabulateCachedResultToolSchema
                .parametersSchema().get("properties");
        String desc = String.valueOf(((java.util.Map<?, ?>) props.get("filters")).get("description"));
        assertFalse(desc.contains("value keys; AND/OR/NOT composites"),
                "regression: vague filter brief must not return");
    }
}
