package com.thingworx.things.agent.tools;

/**
 * Admission bucket — the narrowing unit for tool-schema admission control
 * (docs/operations/tool-schema-admission-control.md §2.3). Each model-facing tool maps to exactly one bucket via
 * {@link ToolBuckets#bucketOf(String)}; the {@code narrow} mode admits or drops whole buckets by deterministic
 * per-turn signals.
 */
public enum ToolBucket {
    /** resolve_thing, resolve_asset_type, list_asset_types, list_entities_by_type, spotlight_search. */
    IDENTITY_ROUTING,
    /** query_entities, query_entities_by_taxonomy, analyze_entity_set. */
    ENTITY_SET_QUERY,
    /** property/stream values, cache/tabular/chart, set_property_value. */
    CURRENT_VALUES_TRENDS,
    /** query_alert_summary, query_alert_history, acknowledge_alerts. */
    ALERTS,
    /** describe_entity_schema, discover_thing_members, invoke_service. */
    METADATA_EXPLORATION,
    /** resolve_document_set, search_document_chunks, get_document_chunk. */
    DOCUMENTS,
    /** get_agent_skill, start_playbook. */
    SKILLS_PLAYBOOKS,
    /** utilization_* extended tools. */
    UTILIZATION,
    /** Any tool that does not map to a known bucket (e.g. deployment-specific extended tools); admitted by default. */
    OTHER
}
