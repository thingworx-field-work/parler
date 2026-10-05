package com.thingworx.things.agent.tools;

import java.util.HashMap;
import java.util.Map;

/**
 * Classifies a model-facing tool name into its {@link ToolBucket}
 * (docs/operations/tool-schema-admission-control.md §2.3, anchored to docs/agent/all-tools.md). The mapping is a
 * static, auditable table — no LLM, no heuristics beyond the {@code utilization_} extended-tool prefix. Names not in
 * the table fall to {@link ToolBucket#OTHER} (admitted by default so unknown deployment-specific tools are never
 * hidden).
 */
public final class ToolBuckets {

    static final String UTILIZATION_PREFIX = "utilization_";

    private static final Map<String, ToolBucket> TABLE = buildTable();

    private ToolBuckets() {}

    private static Map<String, ToolBucket> buildTable() {
        Map<String, ToolBucket> m = new HashMap<>();
        // Identity / routing
        m.put("resolve_thing", ToolBucket.IDENTITY_ROUTING);
        m.put("resolve_asset_type", ToolBucket.IDENTITY_ROUTING);
        m.put("list_asset_types", ToolBucket.IDENTITY_ROUTING);
        m.put("list_entities_by_type", ToolBucket.IDENTITY_ROUTING);
        m.put("spotlight_search", ToolBucket.IDENTITY_ROUTING);
        // Entity set query
        m.put("query_entities", ToolBucket.ENTITY_SET_QUERY);
        m.put("query_entities_by_taxonomy", ToolBucket.ENTITY_SET_QUERY);
        m.put("analyze_entity_set", ToolBucket.ENTITY_SET_QUERY);
        // Current values / trends (includes the cached-result/tabular/chart pipeline and property writes)
        m.put("get_property_values", ToolBucket.CURRENT_VALUES_TRENDS);
        m.put("query_property_history", ToolBucket.CURRENT_VALUES_TRENDS);
        m.put("query_stream_data", ToolBucket.CURRENT_VALUES_TRENDS);
        m.put("fetch_cached_result", ToolBucket.CURRENT_VALUES_TRENDS);
        m.put("tabulate_cached_result", ToolBucket.CURRENT_VALUES_TRENDS);
        m.put("summarize_cached_result", ToolBucket.CURRENT_VALUES_TRENDS);
        m.put("build_chart_from_tabular_result", ToolBucket.CURRENT_VALUES_TRENDS);
        m.put("build_history_overlay_chart", ToolBucket.CURRENT_VALUES_TRENDS);
        m.put("set_property_value", ToolBucket.CURRENT_VALUES_TRENDS);
        // Alerts
        m.put("query_alert_summary", ToolBucket.ALERTS);
        m.put("query_alert_history", ToolBucket.ALERTS);
        m.put("acknowledge_alerts", ToolBucket.ALERTS);
        // Metadata exploration
        m.put("describe_entity_schema", ToolBucket.METADATA_EXPLORATION);
        m.put("discover_thing_members", ToolBucket.METADATA_EXPLORATION);
        m.put("invoke_service", ToolBucket.METADATA_EXPLORATION);
        // Documents
        m.put("resolve_document_set", ToolBucket.DOCUMENTS);
        m.put("search_document_chunks", ToolBucket.DOCUMENTS);
        m.put("get_document_chunk", ToolBucket.DOCUMENTS);
        // Skills / playbooks
        m.put("get_agent_skill", ToolBucket.SKILLS_PLAYBOOKS);
        m.put("start_playbook", ToolBucket.SKILLS_PLAYBOOKS);
        return m;
    }

    public static ToolBucket bucketOf(String toolName) {
        if (toolName == null || toolName.isEmpty()) {
            return ToolBucket.OTHER;
        }
        if (toolName.startsWith(UTILIZATION_PREFIX)) {
            return ToolBucket.UTILIZATION;
        }
        return TABLE.getOrDefault(toolName, ToolBucket.OTHER);
    }

    /**
     * Parses a host-context {@code requiredBuckets} string (the lower-snake enum name, e.g. {@code "entity_set_query"})
     * into a {@link ToolBucket}; returns {@code null} for unknown values so callers can ignore typos rather than fail.
     */
    public static ToolBucket parseBucket(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (key.isEmpty()) {
            return null;
        }
        for (ToolBucket b : ToolBucket.values()) {
            if (b.name().equals(key)) {
                return b;
            }
        }
        return null;
    }
}
