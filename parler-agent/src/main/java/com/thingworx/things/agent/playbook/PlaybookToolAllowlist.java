package com.thingworx.things.agent.playbook;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Built-in tool names that are {@linkplain com.thingworx.things.agent.llm.ToolDefinition#isPlaybookSafe() playbook-safe}
 * in {@link com.thingworx.things.agent.tools.BuiltInTools}, plus executor-only aliases synthesized in
 * {@link PlaybookToolDefinitionsMerge} for validation.
 * <p>
 * <strong>Normative gate:</strong> {@link PlaybookValidator} consults merged {@link com.thingworx.things.agent.llm.ToolDefinition}
 * flags only. This set is a <strong>compatibility / diagnostics anchor</strong> (tests, operators) and must stay equal
 * to the playbook-safe names produced by {@code PlaybookToolDefinitionsMerge.merge(builtInRegistry, missingExt)} for
 * the default built-in registry — see {@link com.thingworx.things.agent.playbook.PlaybookValidatorTest#playbookToolAllowlist_matchesMergedPlaybookSafeBuiltIns()}.
 */
public final class PlaybookToolAllowlist {

    /** Alphabetical for stable diffs; keep in sync with BuiltInTools playbookSafe flags + executor aliases. */
    public static final Set<String> TOOL_NAMES = Collections.unmodifiableSet(new LinkedHashSet<>(Set.of(
            "acknowledge_alerts",
            "analyze_cached_result",
            "analyze_entity_set",
            "build_chart_from_tabular_result",
            "build_history_overlay_chart",
            "describe_entity_schema",
            "discover_thing_members",
            "fetch_cached_result",
            "get_property_values",
            "invoke_service",
            "list_asset_types",
            "list_entities_by_type",
            "query_alert_history",
            "query_alert_summary",
            "query_entities",
            "query_entities_by_taxonomy",
            "query_numeric_property_history",
            "query_property_history",
            "query_stream_data",
            "query_value_stream_property_history",
            "resolve_asset_type",
            "resolve_thing",
            "spotlight_search",
            "summarize_cached_result",
            "tabulate_cached_result")));

    private PlaybookToolAllowlist() {}

    public static boolean isAllowed(String toolName) {
        return toolName != null && TOOL_NAMES.contains(toolName);
    }
}
