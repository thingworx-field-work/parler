package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolDefinition;

class ToolAdmissionPolicyTest {

    /** Incident fixture for admission-policy tests — population (d) @ 2026-06-28 (34 tools incl. seven pre-LLM-friendly utilization_* extended tools). Schemas empty; not the current built-in/extended manifest. See docs/agent/all-tools.md counting populations. */
    private static final List<String> FULL_SURFACE = List.of(
            // identity / routing
            "resolve_thing", "resolve_asset_type", "list_asset_types", "list_entities_by_type", "spotlight_search",
            // entity set query
            "query_entities", "query_entities_by_taxonomy", "analyze_entity_set",
            // current values / trends
            "get_property_values", "query_property_history", "query_stream_data", "fetch_cached_result",
            "tabulate_cached_result", "summarize_cached_result", "build_chart_from_tabular_result", "set_property_value",
            // alerts
            "query_alert_summary", "query_alert_history", "acknowledge_alerts",
            // metadata exploration
            "describe_entity_schema", "discover_thing_members", "invoke_service",
            // documents
            "resolve_document_set", "search_document_chunks", "get_document_chunk",
            // skills / playbooks
            "get_agent_skill", "start_playbook",
            // utilization (extended)
            "utilization_records", "utilization_records_by_machine", "utilization_aggregate_by_state",
            "utilization_stats_for_aggregate", "utilization_machine_listing", "utilization_machine_listing_with_dates",
            "utilization_aggregate_by_state_time_fence");

    private static List<ToolDefinition> fullSurface() {
        List<ToolDefinition> defs = new ArrayList<>();
        for (String n : FULL_SURFACE) {
            defs.add(new ToolDefinition(n, "", Map.of()));
        }
        return defs;
    }

    private static Set<String> names(List<ToolDefinition> defs) {
        return defs.stream().map(ToolDefinition::getName).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static ToolAdmissionSignals signals(String hostKey, boolean slash, boolean docScope, boolean skills,
            boolean playbook, boolean taxonomy, Set<String> requiredTools, Set<ToolBucket> requiredBuckets) {
        return new ToolAdmissionSignals(hostKey, slash, docScope, skills, playbook, taxonomy,
                requiredTools, requiredBuckets);
    }

    private static ToolAdmissionSignals noSignals(String hostKey) {
        return signals(hostKey, false, false, false, false, false, Collections.emptySet(), Collections.emptySet());
    }

    @Test
    void incidentTurn_dropsUtilizationDocumentsMetadata_keepsOperationalBase() {
        // The captured incident: Mashup card "ContainedCardsAndMap", no slash, no doc scope, no required decls.
        ToolAdmissionSignals s = noSignals("PTCTS.AssetMonitoring.ContainedCardsAndMapParler_MU");
        ToolAdmissionPolicy.Result r = ToolAdmissionPolicy.narrow(fullSurface(), s);
        Set<String> admitted = names(r.admitted());

        // Operational base + core stay.
        assertTrue(admitted.contains("resolve_thing"));
        assertTrue(admitted.contains("query_entities"));
        assertTrue(admitted.contains("query_alert_summary"));
        assertTrue(admitted.contains("tabulate_cached_result"));

        // Gated buckets with no signal are dropped.
        assertFalse(admitted.contains("invoke_service"), "metadata exploration dropped");
        assertFalse(admitted.contains("describe_entity_schema"));
        assertFalse(admitted.contains("search_document_chunks"), "documents dropped");
        assertTrue(admitted.stream().noneMatch(n -> n.startsWith("utilization_")), "utilization dropped");

        assertTrue(r.droppedBuckets().contains(ToolBucket.UTILIZATION));
        assertTrue(r.droppedBuckets().contains(ToolBucket.DOCUMENTS));
        assertTrue(r.droppedBuckets().contains(ToolBucket.METADATA_EXPLORATION));
        assertTrue(r.toolsAfter() < r.toolsBefore());
        assertFalse(r.reverted());
    }

    @Test
    void requiredTools_forceAdmitInvokeService_butNotWholeMetadataBucket() {
        ToolAdmissionSignals s = signals("anyCard", false, false, false, false, false,
                Set.of("invoke_service"), Collections.emptySet());
        Set<String> admitted = names(ToolAdmissionPolicy.narrow(fullSurface(), s).admitted());
        assertTrue(admitted.contains("invoke_service"), "host-context requiredTools force-admits invoke_service");
        assertFalse(admitted.contains("describe_entity_schema"), "other metadata tools stay dropped");
        assertFalse(admitted.contains("discover_thing_members"));
    }

    @Test
    void requiredBuckets_forceAdmitUtilization() {
        ToolAdmissionSignals s = signals("anyCard", false, false, false, false, false,
                Collections.emptySet(), EnumSet.of(ToolBucket.UTILIZATION));
        Set<String> admitted = names(ToolAdmissionPolicy.narrow(fullSurface(), s).admitted());
        assertTrue(admitted.contains("utilization_records"));
        assertTrue(admitted.contains("utilization_machine_listing_with_dates"));
    }

    @Test
    void utilizationHostKey_admitsUtilization() {
        Set<String> admitted = names(ToolAdmissionPolicy.narrow(
                fullSurface(), noSignals("SCPA.Utilization.DashboardParler_MU")).admitted());
        assertTrue(admitted.stream().anyMatch(n -> n.startsWith("utilization_")));
    }

    @Test
    void slashActive_admitsDocuments() {
        ToolAdmissionSignals s = signals("anyCard", true, false, false, false, false,
                Collections.emptySet(), Collections.emptySet());
        Set<String> admitted = names(ToolAdmissionPolicy.narrow(fullSurface(), s).admitted());
        assertTrue(admitted.contains("search_document_chunks"));
        assertTrue(admitted.contains("get_document_chunk"));
    }

    @Test
    void loadedSkillsAndPlaybook_admitTheirEntryPoints() {
        Set<String> without = names(ToolAdmissionPolicy.narrow(fullSurface(), noSignals("c")).admitted());
        assertFalse(without.contains("get_agent_skill"));
        assertFalse(without.contains("start_playbook"));

        ToolAdmissionSignals loaded = signals("c", false, false, true, true, false,
                Collections.emptySet(), Collections.emptySet());
        Set<String> with = names(ToolAdmissionPolicy.narrow(fullSurface(), loaded).admitted());
        assertTrue(with.contains("get_agent_skill"));
        assertTrue(with.contains("start_playbook"));
    }

    @Test
    void unknownTool_admittedByDefault() {
        List<ToolDefinition> merged = new ArrayList<>(fullSurface());
        merged.add(new ToolDefinition("deployment_special_tool", "", Map.of()));
        Set<String> admitted = names(ToolAdmissionPolicy.narrow(merged, noSignals("c")).admitted());
        assertTrue(admitted.contains("deployment_special_tool"), "OTHER bucket is never hidden");
    }

    @Test
    void emptyAfterNarrow_revertsToFullSet() {
        // Only gated tools, no signals, no core present -> would drop everything -> safety revert.
        List<ToolDefinition> merged = List.of(new ToolDefinition("invoke_service", "", Map.of()));
        ToolAdmissionPolicy.Result r = ToolAdmissionPolicy.narrow(merged, noSignals("c"));
        assertTrue(r.reverted());
        assertEquals(1, r.toolsAfter());
    }

    @Test
    void decisionLine_isAuditable() {
        ToolAdmissionPolicy.Result r = ToolAdmissionPolicy.narrow(
                fullSurface(), noSignals("ContainedCardsAndMap"));
        String line = ToolAdmissionPolicy.formatDecisionLine("SCPA_Agent_Sonnet", "narrow", r, 68049, 40000);
        assertTrue(line.startsWith("TOOL_ADMISSION agent=SCPA_Agent_Sonnet mode=narrow"));
        assertTrue(line.contains("droppedBuckets="));
        assertTrue(line.contains("UTILIZATION"));
        assertTrue(line.contains("toolSchemaCharsBefore=68049"));
        assertTrue(line.contains("toolSchemaCharsAfter=40000"));
        assertTrue(line.contains("toolsBefore=" + r.toolsBefore()));
        assertTrue(line.contains("droppedTools=") && line.contains("utilization_records"));
    }
}
