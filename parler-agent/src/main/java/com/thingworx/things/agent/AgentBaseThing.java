package com.thingworx.things.agent;

import org.slf4j.Logger;

import com.thingworx.logging.LogUtilities;
import com.thingworx.metadata.annotations.ThingworxConfigurationTableDefinition;
import com.thingworx.metadata.annotations.ThingworxConfigurationTableDefinitions;
import com.thingworx.metadata.annotations.ThingworxDataShapeDefinition;
import com.thingworx.metadata.annotations.ThingworxFieldDefinition;
import com.thingworx.things.agent.compaction.LlmReplayCompactionGate;
import com.thingworx.things.agent.cache.ArtifactCacheTurnAdmission;
import com.thingworx.things.Thing;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.LlmProviderResolveException;
import com.thingworx.things.agent.llm.LLMAPIProviderResolver;
import com.thingworx.things.agent.taxonomy.TaxonomyPromptInjection;
import com.thingworx.things.agent.tools.BuiltInTools;
import com.thingworx.things.agent.tools.ParlerHitlAuditLog;
import com.thingworx.things.agent.tools.ToolRegistry;
import com.thingworx.system.ContextType;

@ThingworxConfigurationTableDefinitions(
    tables = {
        @ThingworxConfigurationTableDefinition(
            name = "AgentSettings",
            description = "Agent Behavior Settings",
            isMultiRow = false,
            dataShape = @ThingworxDataShapeDefinition(
                fields = {
                    @ThingworxFieldDefinition(
                        name = "llmApiProviderRef",
                        description = "LLM API Provider Thing (implements LLMAPIProviderShape). Required for Chat/Parler; "
                            + "endpoint, credentials, and model/deployment live on that Thing (see docs/agent/llm-api-provider.md §5.9).",
                        baseType = "THINGNAME",
                        aspects = { "thingShape:LLMAPIProviderShape", "friendlyName:LLM API Provider" },
                        ordinal = 0),
                    @ThingworxFieldDefinition(
                        name = "temperature",
                        description = "Sampling temperature (0.0–2.0). Lower = more deterministic.",
                        baseType = "NUMBER",
                        aspects = { "defaultValue:0.1" },
                        ordinal = 1),
                    @ThingworxFieldDefinition(
                        name = "maxTokens",
                        description = "Max tokens in LLM response. -1 for provider default.",
                        baseType = "INTEGER",
                        aspects = { "defaultValue:-1" },
                        ordinal = 2),
                    @ThingworxFieldDefinition(
                        name = "maxIterations",
                        description = "Max agent loop iterations (tool call rounds). Safety limit.",
                        baseType = "INTEGER",
                        aspects = { "defaultValue:10" },
                        ordinal = 3),
                    @ThingworxFieldDefinition(
                        name = "agentTimeout",
                        description = "Total agent loop timeout in milliseconds",
                        baseType = "NUMBER",
                        aspects = { "defaultValue:3600000" },
                        ordinal = 4),
                    @ThingworxFieldDefinition(
                        name = "systemPrompt",
                        description = "Default system prompt. Can be overridden per Chat call.",
                        baseType = "TEXT",
                        aspects = { "defaultValue:You are a ThingWorx AI assistant. "
                            + "Use the available tools to help the user interact with the ThingWorx platform." },
                        ordinal = 5),
                    @ThingworxFieldDefinition(
                        name = "appendBuiltInToolRoutingGuide",
                        description = "Append bundled tool-routing instructions (query_entities, query_entities_by_taxonomy, EntityServices, spotlight) to the system prompt sent to the LLM. Repo markdown like AGENT-CONTEXT.md is not auto-loaded.",
                        baseType = "BOOLEAN",
                        aspects = { "defaultValue:true" },
                        ordinal = 6),
                    @ThingworxFieldDefinition(
                        name = "enableBuiltInTools",
                        description = "Enable built-in ThingWorx tools (invoke_service, describe_entity_schema, discover_thing_members, query_entities, …)",
                        baseType = "BOOLEAN",
                        aspects = { "defaultValue:true" },
                        ordinal = 7),
                    @ThingworxFieldDefinition(
                        name = "allowImplicitInvocation",
                        description = "When true, the agent may in the future auto-load skill bodies when the user message matches skill metadata (reserved). When false (default), skill full text loads only via /SkillName in the message or the get_agent_skill tool.",
                        baseType = "BOOLEAN",
                        aspects = { "defaultValue:false" },
                        ordinal = 8),
                    @ThingworxFieldDefinition(
                        name = "hitlAuditDebugAll",
                        description = "When true, DEBUG-tier PARLER_HITL decision_rejected lines also log at WARN for this agent (lab). Restart this Thing after changing; save-only may not refresh registration. See docs/agent/AGENT-ALWAYSON-TWX.md and ParlerHitlAuditLog.",
                        baseType = "BOOLEAN",
                        aspects = { "defaultValue:false" },
                        ordinal = 9),
                    @ThingworxFieldDefinition(
                        name = "exportFileRepository",
                        description = "Optional FileRepository Thing for Parler list-class table CSV export when totalRows exceeds tableCsvExportRowThreshold or the wire carries a partial sample (see docs/ui/table-view-solution.md §5). Empty disables export.",
                        baseType = "THINGNAME",
                        aspects = { "thingTemplate:FileRepository", "friendlyName:Export File Repository" },
                        ordinal = 10),
                    @ThingworxFieldDefinition(
                        name = "tableCsvExportRowThreshold",
                        description = "When a table's totalRows exceeds this integer, or totalRows is greater than the inline row count, the agent attempts UTF-8 CSV export via SaveText on exportFileRepository before emitting type:table.",
                        baseType = "INTEGER",
                        aspects = { "defaultValue:200" },
                        ordinal = 11),
                    @ThingworxFieldDefinition(
                        name = "configurationRepository",
                        description = "File Repository containing AgentThing configuration files (skills, taxonomy Markdown, policies, extended tools). Empty disables repository-backed configuration without log.",
                        baseType = "THINGNAME",
                        aspects = { "thingTemplate:FileRepository", "friendlyName:Configuration Repository" },
                        ordinal = 12),
                    @ThingworxFieldDefinition(
                        name = "taxonomyPromptInjection",
                        description = "Controls LLM-visible taxonomy prompt injection: full_table (default), resolver_guidance_only, or none. See docs/agent/taxonomy.md.",
                        baseType = "STRING",
                        aspects = { "defaultValue:full_table" },
                        ordinal = 13),
                    @ThingworxFieldDefinition(
                        name = "tableCsvExportMaxChars",
                        description = "Max UTF-16 code units (Java String length) for one large-table CSV before Parler skips export (skipped_limit). Platform SaveText still applies; this caps agent-side CSV materialization. Clamped 1_000_000..200_000_000.",
                        baseType = "INTEGER",
                        aspects = { "defaultValue:50000000" },
                        ordinal = 14),
                    @ThingworxFieldDefinition(
                        name = "llmContextMaxChars",
                        description = "Upper bound on model-facing request size for context budget planning (characters). Provider-specific limits are still applied as min(providerLimit*3.5, this value). See docs/agent/context-compaction.md. Clamped 10_000..2_000_000.",
                        baseType = "INTEGER",
                        aspects = { "defaultValue:750000" },
                        ordinal = 15),
                    @ThingworxFieldDefinition(
                        name = "advertiseLegacyServiceDiscoveryTools",
                        description = "When true, legacy discover_services and get_service_definition are included in the merged LLM tool list; discover_properties remains executor-only for merge. Default false. See docs/agent/legacy-discovery-executor-only.md.",
                        baseType = "BOOLEAN",
                        aspects = { "defaultValue:false" },
                        ordinal = 16),
                    @ThingworxFieldDefinition(
                        name = "documentKnowledgeBuiltinsEnabled",
                        description = "When true, advertise internal Java search_document_chunks, get_document_chunk, and resolve_document_set built-ins. When false, do not register those names (extended tools may use the same names). Default false. See docs/agent/document-chunk-tools.md.",
                        baseType = "BOOLEAN",
                        aspects = { "defaultValue:false" },
                        ordinal = 17),
                    @ThingworxFieldDefinition(
                        name = "documentKnowledgeRepository",
                        description = "FileRepository Thing containing document-knowledge packages. Empty disables repository-backed document tools. See docs/agent/document-chunk-tools.md.",
                        baseType = "THINGNAME",
                        aspects = { "thingTemplate:FileRepository", "friendlyName:Document Knowledge Repository" },
                        ordinal = 18),
                    @ThingworxFieldDefinition(
                        name = "documentKnowledgeRootPath",
                        description = "Root folder below documentKnowledgeRepository for packages. Default /document-knowledge.",
                        baseType = "STRING",
                        aspects = { "defaultValue:/document-knowledge" },
                        ordinal = 19),
                    @ThingworxFieldDefinition(
                        name = "documentKnowledgeIndexTtlSeconds",
                        description = "JVM cache TTL for the document-knowledge index. Clamped 30..86400; default 300.",
                        baseType = "INTEGER",
                        aspects = { "defaultValue:300" },
                        ordinal = 20),
                    @ThingworxFieldDefinition(
                        name = "documentKnowledgeMaxDocuments",
                        description = "v1 scan cap for package manifests. Clamped 1..10000; default 100.",
                        baseType = "INTEGER",
                        aspects = { "defaultValue:100" },
                        ordinal = 21),
                    @ThingworxFieldDefinition(
                        name = "documentKnowledgeMaxChunks",
                        description = "v1 cap for total indexed chunks. Clamped 1..1000000; default 10000.",
                        baseType = "INTEGER",
                        aspects = { "defaultValue:10000" },
                        ordinal = 22),
                    @ThingworxFieldDefinition(
                        name = "documentKnowledgeSearchDefaultLimit",
                        description = "Default search result count. Clamped 1..100; default 5.",
                        baseType = "INTEGER",
                        aspects = { "defaultValue:5" },
                        ordinal = 23),
                    @ThingworxFieldDefinition(
                        name = "documentKnowledgeSearchMaxLimit",
                        description = "Maximum search result count. Clamped to searchDefaultLimit..100; default 10.",
                        baseType = "INTEGER",
                        aspects = { "defaultValue:10" },
                        ordinal = 24),
                    @ThingworxFieldDefinition(
                        name = "documentKnowledgeSearchSnippetMaxChars",
                        description = "Maximum chars per search-match snippet. Clamped 50..10000; default 400.",
                        baseType = "INTEGER",
                        aspects = { "defaultValue:400" },
                        ordinal = 25),
                    @ThingworxFieldDefinition(
                        name = "documentKnowledgeChunkMaxChars",
                        description = "Maximum markdown chars returned by get_document_chunk. Clamped 500..500000; default 6000.",
                        baseType = "INTEGER",
                        aspects = { "defaultValue:6000" },
                        ordinal = 26),
                    @ThingworxFieldDefinition(
                        name = "documentTurnToolNarrowingDisabled",
                        description = "When true, disable document_search-only turn tool narrowing (D2). Default false (narrowing on). Set true to revert to the full merged tool list. TWX unset/false both mean not disabled. See docs/operations/doc-index-enhance.md D2.",
                        baseType = "BOOLEAN",
                        aspects = { "defaultValue:false" },
                        ordinal = 27),
                    @ThingworxFieldDefinition(
                        name = "toolAdmissionMode",
                        description = "Tool-schema admission mode: off (default; advertise all tools), narrow (drop irrelevant tool buckets up front by deterministic host-context/intent signals, keep the core set), or lazy (opt-in; advertise only the core set plus a load_tool_schemas meta-tool whose catalog lets the model load tool schemas on demand). Unknown/blank means off. See docs/operations/tool-schema-admission-control.md.",
                        baseType = "STRING",
                        aspects = { "defaultValue:off" },
                        ordinal = 28),
                    @ThingworxFieldDefinition(
                        name = "artifactCacheFileRepository",
                        description = "Required dedicated FileRepository Thing for the production Artifact Cache. Empty or unavailable rejects user turns (no in-memory fallback). See docs/agent/nearterm/cache-correctness-foundation.md.",
                        baseType = "THINGNAME",
                        aspects = { "thingTemplate:FileRepository", "friendlyName:Artifact Cache File Repository" },
                        ordinal = 29) })),
    }
)
public abstract class AgentBaseThing extends Thing {

    private static final long serialVersionUID = 1L;

    protected static final Logger _logger = LogUtilities.getInstance().getApplicationLogger(AgentBaseThing.class);

    protected static class Config {
        public static final String AgentSettings = "AgentSettings";
        public static final String Temperature = "temperature";
        public static final String MaxTokens = "maxTokens";
        public static final String MaxIterations = "maxIterations";
        public static final String AgentTimeout = "agentTimeout";
        public static final String SystemPrompt = "systemPrompt";
        public static final String AppendBuiltInToolRoutingGuide = "appendBuiltInToolRoutingGuide";
        public static final String EnableBuiltInTools = "enableBuiltInTools";
        public static final String AllowImplicitInvocation = "allowImplicitInvocation";
        public static final String HitlAuditDebugAll = "hitlAuditDebugAll";
        public static final String ExportFileRepository = "exportFileRepository";
        public static final String TableCsvExportRowThreshold = "tableCsvExportRowThreshold";
        public static final String TableCsvExportMaxChars = "tableCsvExportMaxChars";
        public static final String LlmContextMaxChars = "llmContextMaxChars";
        public static final String ConfigurationRepository = "configurationRepository";
        public static final String LlmApiProviderRef = "llmApiProviderRef";
        public static final String TaxonomyPromptInjection = "taxonomyPromptInjection";
        public static final String AdvertiseLegacyServiceDiscoveryTools = "advertiseLegacyServiceDiscoveryTools";
        public static final String DocumentKnowledgeBuiltinsEnabled = "documentKnowledgeBuiltinsEnabled";
        public static final String DocumentKnowledgeRepository = "documentKnowledgeRepository";
        public static final String DocumentKnowledgeRootPath = "documentKnowledgeRootPath";
        public static final String DocumentKnowledgeIndexTtlSeconds = "documentKnowledgeIndexTtlSeconds";
        public static final String DocumentKnowledgeMaxDocuments = "documentKnowledgeMaxDocuments";
        public static final String DocumentKnowledgeMaxChunks = "documentKnowledgeMaxChunks";
        public static final String DocumentKnowledgeSearchDefaultLimit = "documentKnowledgeSearchDefaultLimit";
        public static final String DocumentKnowledgeSearchMaxLimit = "documentKnowledgeSearchMaxLimit";
        public static final String DocumentKnowledgeSearchSnippetMaxChars = "documentKnowledgeSearchSnippetMaxChars";
        public static final String DocumentKnowledgeChunkMaxChars = "documentKnowledgeChunkMaxChars";
        public static final String DocumentTurnToolNarrowingDisabled = "documentTurnToolNarrowingDisabled";
        public static final String ToolAdmissionMode = "toolAdmissionMode";
        public static final String ArtifactCacheFileRepository = "artifactCacheFileRepository";
    }

    protected double _temperature = 0.1;
    protected int _maxTokens = -1;
    protected int _maxIterations = 10;
    protected long _agentTimeout = 3600000;
    protected String _systemPrompt = "";
    protected boolean _appendBuiltInToolRoutingGuide = true;
    protected boolean _enableBuiltInTools = true;
    /** When true, legacy discover_services / get_service_definition appear in merged LLM tool list (discover_properties stays executor-only). */
    protected boolean _advertiseLegacyServiceDiscoveryTools = false;
    /** When true, register Java document-knowledge built-ins; when false, leave names free for extended tools. */
    protected boolean _documentKnowledgeBuiltinsEnabled = false;
    /** When false (default), narrow LLM tools on document_search-only turns per doc-index-enhance D2. */
    protected boolean _documentTurnToolNarrowingDisabled = false;
    /** Tool-schema admission mode (off|narrow|lazy); default off = advertise all (tool-schema-admission-control §2.2). */
    protected String _toolAdmissionMode = "off";
    /** When false (default), skill bodies are not auto-injected from metadata match (reserved); use /SkillName or get_agent_skill. */
    protected boolean _allowImplicitInvocation = false;
    /** When true, elevate normally-DEBUG PARLER_HITL decision_rejected lines to WARN (see ParlerHitlAuditLog). */
    protected boolean _hitlAuditDebugAll = false;
    /** Optional FileRepository Thing name for Parler table CSV export (see {@code ParlerTableFileExportHook}). */
    protected String _exportFileRepository = "";
    /** Dedicated FileRepository Thing name for the U1A artifact cache; empty fails closed. */
    protected String _artifactCacheFileRepository = "";
    /** When {@code totalRows} exceeds this value (or inline sample is partial), CSV export may run before {@code wireTable}. */
    protected int _tableCsvExportRowThreshold = 200;
    /** Upper bound on Java {@link String#length()} for CSV built in {@link ParlerTableFileExportHook} before {@code skipped_limit}. */
    protected int _tableCsvExportMaxCsvChars = 50_000_000;
    /** Non-blank: resolve {@link com.thingworx.things.agent.llm.providers.LLMAPIProviderThing} per user turn (§5.9). */
    protected String _llmApiProviderRef = "";
    /** Upper bound for {@code LLM_CONTEXT_PLAN} / context budget ({@code docs/agent/context-compaction.md}). */
    protected int _llmContextMaxChars = 750_000;
    protected String _taxonomyPromptInjectionEffective = TaxonomyPromptInjection.FULL_TABLE;
    protected ToolRegistry _toolRegistry = new ToolRegistry();

    @Override
    protected void initializeThing(ContextType contextType) {
        Object providerRef = getConfigurationData().getValue(Config.AgentSettings, Config.LlmApiProviderRef);
        _llmApiProviderRef = providerRef instanceof String ? ((String) providerRef).trim()
                : (providerRef != null ? String.valueOf(providerRef).trim() : "");
        if (_llmApiProviderRef == null) {
            _llmApiProviderRef = "";
        }
        if (_llmApiProviderRef.isEmpty()) {
            _logger.error("[{}] AgentSettings.llmApiProviderRef is empty; Chat/Parler/TestConnection require an LLM API "
                    + "Provider Thing (docs/agent/llm-api-provider.md §5.9).", getName());
        } else {
            _logger.info("[{}] LLM calls use llmApiProviderRef={} (resolved per user turn).", getName(), _llmApiProviderRef);
        }

        Object temp = getConfigurationData().getValue(Config.AgentSettings, Config.Temperature);
        _temperature = temp instanceof Number ? ((Number) temp).doubleValue() : 0.1;
        _maxTokens = getIntFromConfig(Config.AgentSettings, Config.MaxTokens, -1);
        _maxIterations = getIntFromConfig(Config.AgentSettings, Config.MaxIterations, 10);
        Object at = getConfigurationData().getValue(Config.AgentSettings, Config.AgentTimeout);
        _agentTimeout = at instanceof Number ? ((Number) at).longValue() : 3_600_000L;
        Object sp = getConfigurationData().getValue(Config.AgentSettings, Config.SystemPrompt);
        _systemPrompt = sp instanceof String ? (String) sp : (sp != null ? String.valueOf(sp) : "");
        if (_systemPrompt == null) {
            _systemPrompt = "";
        }
        Object appendGuide = getConfigurationData().getValue(Config.AgentSettings, Config.AppendBuiltInToolRoutingGuide);
        _appendBuiltInToolRoutingGuide = appendGuide == null || Boolean.TRUE.equals(appendGuide);
        _enableBuiltInTools = Boolean.TRUE.equals(
                getConfigurationData().getValue(Config.AgentSettings, Config.EnableBuiltInTools));
        Object advLegacy = getConfigurationData().getValue(Config.AgentSettings,
                Config.AdvertiseLegacyServiceDiscoveryTools);
        _advertiseLegacyServiceDiscoveryTools = Boolean.TRUE.equals(advLegacy);
        Object docKnowledge = getConfigurationData().getValue(Config.AgentSettings,
                Config.DocumentKnowledgeBuiltinsEnabled);
        _documentKnowledgeBuiltinsEnabled = Boolean.TRUE.equals(docKnowledge);
        Object docTurnNarrowDisabled = getConfigurationData().getValue(Config.AgentSettings,
                Config.DocumentTurnToolNarrowingDisabled);
        _documentTurnToolNarrowingDisabled = Boolean.TRUE.equals(docTurnNarrowDisabled);
        Object toolAdmissionMode = getConfigurationData().getValue(Config.AgentSettings, Config.ToolAdmissionMode);
        _toolAdmissionMode = toolAdmissionMode instanceof String && !((String) toolAdmissionMode).trim().isEmpty()
                ? ((String) toolAdmissionMode).trim()
                : "off";
        Object allowImplicit = getConfigurationData().getValue(Config.AgentSettings, Config.AllowImplicitInvocation);
        _allowImplicitInvocation = Boolean.TRUE.equals(allowImplicit);
        Object hitlDbg = getConfigurationData().getValue(Config.AgentSettings, Config.HitlAuditDebugAll);
        _hitlAuditDebugAll = Boolean.TRUE.equals(hitlDbg);
        ParlerHitlAuditLog.registerAgentHitlAuditDebugAll(getName(), _hitlAuditDebugAll);

        Object er = getConfigurationData().getValue(Config.AgentSettings, Config.ExportFileRepository);
        _exportFileRepository = er instanceof String ? ((String) er).trim() : (er != null ? String.valueOf(er).trim() : "");
        if (_exportFileRepository == null) {
            _exportFileRepository = "";
        }
        Object acRepo = getConfigurationData().getValue(Config.AgentSettings, Config.ArtifactCacheFileRepository);
        _artifactCacheFileRepository = acRepo instanceof String ? ((String) acRepo).trim()
                : (acRepo != null ? String.valueOf(acRepo).trim() : "");
        if (_artifactCacheFileRepository == null) {
            _artifactCacheFileRepository = "";
        }
        ArtifactCacheTurnAdmission.logInitialization(this, _logger);
        _tableCsvExportRowThreshold = getIntFromConfig(
                Config.AgentSettings, Config.TableCsvExportRowThreshold, 200);
        if (_tableCsvExportRowThreshold < 1) {
            _tableCsvExportRowThreshold = 200;
        }
        _tableCsvExportMaxCsvChars = getIntFromConfig(
                Config.AgentSettings, Config.TableCsvExportMaxChars, 50_000_000);
        if (_tableCsvExportMaxCsvChars < 1_000_000) {
            _tableCsvExportMaxCsvChars = 1_000_000;
        }
        if (_tableCsvExportMaxCsvChars > 200_000_000) {
            _tableCsvExportMaxCsvChars = 200_000_000;
        }
        _llmContextMaxChars = getIntFromConfig(Config.AgentSettings, Config.LlmContextMaxChars, 750_000);
        if (_llmContextMaxChars < 10_000) {
            _llmContextMaxChars = 10_000;
        }
        if (_llmContextMaxChars > 2_000_000) {
            _llmContextMaxChars = 2_000_000;
        }
        Object staleReplayCompaction = getConfigurationData().getValue(Config.AgentSettings,
                LlmReplayCompactionGate.STALE_AGENT_SETTING_ENABLE_LLM_REPLAY_COMPACTION);
        if (staleReplayCompaction != null) {
            _logger.info(
                    "[{}] AgentSettings still contains stale field '{}' (ignored; replay compaction is always-on unless JVM {}=true).",
                    getName(), LlmReplayCompactionGate.STALE_AGENT_SETTING_ENABLE_LLM_REPLAY_COMPACTION,
                    LlmReplayCompactionGate.UNSAFE_DIAGNOSTICS_PROPERTY);
        }

        Object taxInj = getConfigurationData().getValue(Config.AgentSettings, Config.TaxonomyPromptInjection);
        String taxRaw = taxInj instanceof String ? ((String) taxInj).trim()
                : (taxInj != null ? String.valueOf(taxInj).trim() : "");
        if (taxRaw.isEmpty()) {
            _taxonomyPromptInjectionEffective = TaxonomyPromptInjection.FULL_TABLE;
        } else if (TaxonomyPromptInjection.isAllowed(taxRaw)) {
            _taxonomyPromptInjectionEffective = taxRaw;
        } else {
            _logger.error("[{}] AgentSettings.taxonomyPromptInjection invalid value \"{}\"; using full_table",
                    getName(), taxRaw);
            _taxonomyPromptInjectionEffective = TaxonomyPromptInjection.FULL_TABLE;
        }

        initializeTools();
    }

    /**
     * Effective Tier A/0/B replay compaction for this JVM instant: always-on unless
     * {@link LlmReplayCompactionGate#UNSAFE_DIAGNOSTICS_PROPERTY} or a test override suppresses it (Slice F).
     */
    protected boolean isLlmReplayCompactionEffective() {
        return LlmReplayCompactionGate.isReplayCompactionEffective();
    }

    public String getTaxonomyPromptInjectionEffective() {
        return _taxonomyPromptInjectionEffective;
    }

    /** @return configured FileRepository Thing name for optional Parler table CSV export; may be empty */
    public String getParlerTableExportRepositoryName() {
        return _exportFileRepository != null ? _exportFileRepository : "";
    }

    /** @return configured FileRepository Thing name for the U1A artifact cache; empty fails closed. */
    public String getArtifactCacheFileRepositoryName() {
        return _artifactCacheFileRepository != null ? _artifactCacheFileRepository : "";
    }

    /**
     * Core-owned cache accessor: blank/missing/non-FileRepository {@code artifactCacheFileRepository}
     * fails closed with {@link com.thingworx.things.agent.cache.ArtifactCacheFaultCode#REPOSITORY_UNAVAILABLE}.
     */
    public com.thingworx.things.agent.cache.ArtifactCache requireArtifactCache()
            throws com.thingworx.things.agent.cache.ArtifactCacheException {
        return com.thingworx.things.agent.cache.ArtifactCacheCore.requireCache(this, _logger);
    }

    /** @return row-count threshold that triggers optional CSV export (with partial-sample rule in {@code ParlerTableFileExportHook}) */
    public int getParlerTableExportRowThreshold() {
        return _tableCsvExportRowThreshold;
    }

    /** @return max UTF-8 characters for one CSV before {@code ParlerTableFileExportHook} sets {@code skipped_limit} */
    public int getParlerTableExportMaxCsvChars() {
        return _tableCsvExportMaxCsvChars;
    }

    /** @return built-in and registered tool definitions for this agent (repository authoring validation). */
    public ToolRegistry toolRegistry() {
        return _toolRegistry;
    }

    /**
     * @return configured FileRepository Thing name for the agent configuration repository; may be empty (no
     *         repository-backed skills, taxonomy Markdown, extended tools, or invoke_service policy file)
     */
    public String getConfigurationRepositoryThingName() {
        Object v = getConfigurationData().getValue(Config.AgentSettings, Config.ConfigurationRepository);
        String value = v instanceof String ? ((String) v).trim() : (v != null ? String.valueOf(v).trim() : "");
        return value != null ? value : "";
    }

    /** @return whether Java document-knowledge built-ins are registered for this agent */
    public boolean isDocumentKnowledgeBuiltinsEnabled() {
        return _documentKnowledgeBuiltinsEnabled;
    }

    /** @return whether document_search-only turns may attach a narrowed LLM tool list (D2; default true) */
    public boolean isDocumentTurnToolNarrowingEnabled() {
        return !_documentTurnToolNarrowingDisabled;
    }

    /** @return whether D2 tool narrowing is explicitly disabled via AgentSettings */
    public boolean isDocumentTurnToolNarrowingDisabled() {
        return _documentTurnToolNarrowingDisabled;
    }

    /** @return raw tool-schema admission mode string (off|narrow|lazy); default off (tool-schema-admission-control §2.2) */
    public String getToolAdmissionMode() {
        return _toolAdmissionMode;
    }

    /** @return FileRepository Thing for document-knowledge packages; may be empty */
    public String getDocumentKnowledgeRepository() {
        return trimConfigString(Config.DocumentKnowledgeRepository);
    }

    /** @return repository-relative root for document packages; default {@code /document-knowledge} when unset */
    public String getDocumentKnowledgeRootPath() {
        String value = trimConfigString(Config.DocumentKnowledgeRootPath);
        return value.isEmpty() ? "/document-knowledge" : value;
    }

    public int getDocumentKnowledgeIndexTtlSeconds() {
        return getIntFromConfig(Config.AgentSettings, Config.DocumentKnowledgeIndexTtlSeconds, 300);
    }

    public int getDocumentKnowledgeMaxDocuments() {
        return getIntFromConfig(Config.AgentSettings, Config.DocumentKnowledgeMaxDocuments, 100);
    }

    public int getDocumentKnowledgeMaxChunks() {
        return getIntFromConfig(Config.AgentSettings, Config.DocumentKnowledgeMaxChunks, 10_000);
    }

    public int getDocumentKnowledgeSearchDefaultLimit() {
        return getIntFromConfig(Config.AgentSettings, Config.DocumentKnowledgeSearchDefaultLimit, 5);
    }

    public int getDocumentKnowledgeSearchMaxLimit() {
        return getIntFromConfig(Config.AgentSettings, Config.DocumentKnowledgeSearchMaxLimit, 10);
    }

    public int getDocumentKnowledgeSearchSnippetMaxChars() {
        return getIntFromConfig(Config.AgentSettings, Config.DocumentKnowledgeSearchSnippetMaxChars, 400);
    }

    public int getDocumentKnowledgeChunkMaxChars() {
        return getIntFromConfig(Config.AgentSettings, Config.DocumentKnowledgeChunkMaxChars, 6_000);
    }

    private String trimConfigString(String fieldName) {
        Object v = getConfigurationData().getValue(Config.AgentSettings, fieldName);
        String value = v instanceof String ? ((String) v).trim() : (v != null ? String.valueOf(v).trim() : "");
        return value != null ? value : "";
    }

    private int getIntFromConfig(String tableName, String fieldName, int defaultValue) {
        Object val = getConfigurationData().getValue(tableName, fieldName);
        return val instanceof Number ? ((Number) val).intValue() : defaultValue;
    }

    /**
     * Resolves {@code llmApiProviderRef} to a client for this user turn.
     */
    protected LlmClient resolveLlmClientForTurn() throws LlmProviderResolveException {
        return LLMAPIProviderResolver.resolve(_llmApiProviderRef).getLlmClient();
    }

    /** True when {@code AgentSettings.llmApiProviderRef} is non-blank (Chat still fails at resolve if target is invalid). */
    protected boolean hasLlmRuntimeConfigured() {
        return _llmApiProviderRef != null && !_llmApiProviderRef.isBlank();
    }

    protected void initializeTools() {
        _toolRegistry = new ToolRegistry();
        if (_enableBuiltInTools) {
            BuiltInTools.registerAll(_toolRegistry, _advertiseLegacyServiceDiscoveryTools,
                    _documentKnowledgeBuiltinsEnabled);
        } else {
            BuiltInTools.registerGetAgentSkillOnly(_toolRegistry);
        }
        _logger.info("[{}] Tool registry size={} (builtInsEnabled={})", getName(), _toolRegistry.size(),
                _enableBuiltInTools);
    }
}
