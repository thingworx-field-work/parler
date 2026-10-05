package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.compaction.ContextBudgetPlanner;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.llm.ToolSchemaSizer;

/**
 * §4.1 planner-level acceptance bar: the {@code narrow} admission contract is not "bucket X excluded" but
 * "{@code OVERHEAD_EXCEEDS_CAP} cleared at the recorded effective cap". This test reproduces the incident shape — a
 * full tool surface whose fixed schema overhead overflows the effective per-request cap — and shows that the
 * post-narrow surface yields a positive {@code historyBudgetChars} at the same cap.
 */
class ToolAdmissionPlannerIncidentTest {

    private static final String API_SHAPE = "anthropic-messages-v1";

    private static ToolDefinition padded(String name, int schemaPad) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", "string");
        field.put("description", "x".repeat(Math.max(0, schemaPad)));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("arg", field);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        return new ToolDefinition(name, "tool " + name, schema);
    }

    /** The 34-tool surface with ~1.6KB schemas each, so the full set's fixed overhead is large. */
    private static List<ToolDefinition> fullSurface() {
        List<ToolDefinition> defs = new ArrayList<>();
        for (String n : List.of(
                "resolve_thing", "resolve_asset_type", "list_asset_types", "list_entities_by_type", "spotlight_search",
                "query_entities", "query_entities_by_taxonomy", "analyze_entity_set",
                "get_property_values", "query_property_history", "query_stream_data", "fetch_cached_result",
                "tabulate_cached_result", "summarize_cached_result", "build_chart_from_tabular_result",
                "set_property_value",
                "query_alert_summary", "query_alert_history", "acknowledge_alerts",
                "describe_entity_schema", "discover_thing_members", "invoke_service",
                "resolve_document_set", "search_document_chunks", "get_document_chunk",
                "get_agent_skill", "start_playbook",
                "utilization_records", "utilization_records_by_machine", "utilization_aggregate_by_state",
                "utilization_stats_for_aggregate", "utilization_machine_listing",
                "utilization_machine_listing_with_dates", "utilization_aggregate_by_state_time_fence")) {
            defs.add(padded(n, 1500));
        }
        return defs;
    }

    @Test
    void narrowClearsOverheadExceedsCapAtTheSameEffectiveCap() {
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", API_SHAPE, "custom-unknown-model");
        List<ToolDefinition> full = fullSurface();

        // Incident signals: a non-document, non-utilization Mashup card, nothing loaded, no required declarations.
        ToolAdmissionSignals incident = new ToolAdmissionSignals(
                "PTCTS.AssetMonitoring.ContainedCardsAndMapParler_MU",
                false, false, false, false, false, Collections.emptySet(), Collections.emptySet());
        List<ToolDefinition> narrowed = ToolAdmissionPolicy.narrow(full, incident).admitted();
        assertTrue(narrowed.size() < full.size(), "narrow must drop the idle gated buckets");

        int fullChars = ToolSchemaSizer.totalSchemaChars(API_SHAPE, full);
        int narrowChars = ToolSchemaSizer.totalSchemaChars(API_SHAPE, narrowed);
        assertTrue(narrowChars < fullChars);

        List<ChatMessage> messages = List.of(ChatMessage.user("compare the health status of assets between USA and Germany"));
        int userChars = "compare the health status of assets between USA and Germany".length();

        // Effective cap set between the two overheads so the full surface overflows and the narrow surface fits.
        long cap = userChars + ((long) fullChars + narrowChars) / 2L;

        ContextBudgetPlanner.Metrics mFull =
                ContextBudgetPlanner.Metrics.compute(messages, full, ids, 5_000_000, cap);
        ContextBudgetPlanner.Metrics mNarrow =
                ContextBudgetPlanner.Metrics.compute(messages, narrowed, ids, 5_000_000, cap);

        // Same effective cap for both.
        assertEquals(cap, mFull.effectiveRequestCapChars);
        assertEquals(cap, mNarrow.effectiveRequestCapChars);

        // Full surface: fixed overhead exceeds the cap -> would throw OVERHEAD_EXCEEDS_CAP.
        assertTrue(mFull.historyBudgetChars < 0, "full surface overflows: " + mFull.historyBudgetChars);
        assertEquals(1, mFull.historyClampedToZero);

        // Narrow surface: positive history budget at the same cap -> incident cleared.
        assertTrue(mNarrow.historyBudgetChars > 0, "narrow clears the cap: " + mNarrow.historyBudgetChars);
        assertEquals(0, mNarrow.historyClampedToZero);
    }
}
