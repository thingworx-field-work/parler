package com.thingworx.things.agent.taxonomy;

/** {@code AgentSettings.taxonomyPromptInjection} allowed values. {@link #FULL_TABLE} appends optional repository Markdown only (no generated taxonomy table). */
public final class TaxonomyPromptInjection {

    public static final String FULL_TABLE = "full_table";
    public static final String RESOLVER_GUIDANCE_ONLY = "resolver_guidance_only";
    public static final String NONE = "none";

    public static final String RESOLVER_GUIDANCE_TEXT =
            "Application taxonomy is available through resolver tools, not through an injected table.\n\n"
                    + "- To count or list application asset types, call list_asset_types.\n"
                    + "- To map user asset type text to a ThingWorx parent, call resolve_asset_type.\n"
                    + "- To resolve a specific asset to a unique canonical Thing name, call resolve_thing (requires v3 identity-types.json array rules).\n"
                    + "- Do not guess application asset types from generic ThingTemplate inventory when taxonomy resolver tools are available.\n"
                    + "- If resolver tools return TAXONOMY_UNAVAILABLE, use generic platform exploration tools only when the user asks for generic ThingWorx platform exploration or no application taxonomy is available. Do not claim an application asset taxonomy answer from platform inventory.\n"
                    + "- If you need EntityType/EntityName for query_entities_by_taxonomy, obtain them from resolve_asset_type first.";

    private TaxonomyPromptInjection() {}

    public static boolean isAllowed(String value) {
        return FULL_TABLE.equals(value) || RESOLVER_GUIDANCE_ONLY.equals(value) || NONE.equals(value);
    }

    public static String effectiveOrDefault(String raw) {
        if (raw == null || raw.isBlank()) {
            return FULL_TABLE;
        }
        String v = raw.trim();
        return isAllowed(v) ? v : FULL_TABLE;
    }
}
