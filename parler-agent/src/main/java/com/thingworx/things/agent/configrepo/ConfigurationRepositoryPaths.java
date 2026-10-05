package com.thingworx.things.agent.configrepo;

/** Canonical paths under the Agent configuration FileRepository (docs/agent/configuration-repository.md). */
public final class ConfigurationRepositoryPaths {

    public static final String SKILLS_ROOT = "/skills";
    public static final String TAXONOMY_TYPE_MARKDOWN = "/taxonomies/type-taxonomy.md";
    public static final String IDENTITY_TYPES_JSON = "/taxonomies/identity-types.json";
    /** v3 asset type map; also used for legacy presence diagnostics when identity is v2. */
    public static final String ASSET_TYPES_JSON = "/taxonomies/asset-types.json";
    public static final String INVOKE_SERVICE_POLICY = "/policies/invoke_service.json";
    public static final String EXTENDED_TOOLS = "/tools/extended_tools.json";
    /** U3S application semantic profile (SP1). */
    public static final String SEMANTIC_PROFILE_JSON = "/semantics/semantic-profile.json";
    /**
     * U7 / G16 ordered Provider route profiles (SPR-0 path lock). References existing Provider
     * Thing names only; credentials stay on Provider Things. Load/validate in SPR-4.
     */
    public static final String PROVIDER_ROUTE_PROFILES = "/providers/route_profiles.json";
    /** Optional whole-block stable system prompt override (direct *.md files only; non-recursive). */
    public static final String SYSTEM_PROMPT_ROOT = "/SystemPrompt";

    private ConfigurationRepositoryPaths() {}
}
