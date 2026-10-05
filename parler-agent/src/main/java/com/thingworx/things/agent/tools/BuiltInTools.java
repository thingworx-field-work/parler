package com.thingworx.things.agent.tools;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import com.thingworx.logging.LogUtilities;
import com.thingworx.things.agent.llm.LlmJsonSchemaCompat;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.taxonomy.TaxonomyResolverExecutor;

/**
 * Built-in tools that expose ThingWorx platform capabilities to the LLM.
 *
 * Design philosophy (from mcp-twx):
 *   - Consolidate hundreds of ThingWorx APIs into a few meta-tools
 *   - Dedicated tools for common flows: {@code query_entities}, {@code query_entities_by_taxonomy},
 *     {@code list_entities_by_type}, {@code spotlight_search}
 *   - {@code invoke_service} is the generic path for services discovered via {@code discover_services} /
 *     {@code get_service_definition}, or for follow-up calls suggested by prior tool results — not for listing
 *     metadata entities when {@code list_entities_by_type} applies
 *
 * Key advantage over mcp-twx (external MCP server):
 *   - These tools execute via Java SDK calls INSIDE the ThingWorx JVM
 *   - Zero HTTP overhead, direct access to ThingUtilities, ThingManager, etc.
 *   - Inherits the current user's SecurityContext automatically
 *   - Can access internal APIs not exposed via REST
 */
public final class BuiltInTools {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(BuiltInTools.class);

    /** LLM schema text for scalar Thing instance names (property + alert built-ins + shared preflight contract). */
    static final String LLM_THINGNAME_SCALAR_DESCRIPTION =
            "Canonical ThingWorx Thing name (exact platform **Thing** name). If the user supplied a display label, "
                    + "serial number, suffix, or any uncertain asset identifier, call **resolve_thing** first (v3 "
                    + "identity taxonomy), then retry with **matches[0].name** from a **UNIQUE** result.";

    private BuiltInTools() {}

    /** Shared JSON Schema fragment for hierarchy expand ∩ on {@code query_entities*} (CONTRACTS/API_CONTRACT 2.4.11+). */
    private static void addIntersectToolArguments(Map<String, Object> props, boolean taxonomyTool) {
        Map<String, Object> intersectNames = new LinkedHashMap<>();
        intersectNames.put("type", "array");
        intersectNames.put("maxItems", 5000);
        intersectNames.put("items", Map.of("type", "string"));
        String preIntersectSemantics = taxonomyTool
                ? "**preIntersectMatchCount** = full post-LookupProperties count before ∩."
                : "**preIntersectMatchCount** = this QIT page count before ∩.";
        intersectNames.put("description",
                "Optional Thing-name set **B**: keep rows whose **name** is in the set (String.equals). "
                        + preIntersectSemantics
                        + " Adds **queryHasMore**, **expandHasMore**, **hasMore** (API_CONTRACT / entity-hierarchy §6).");
        props.put("intersectThingNames", intersectNames);
        props.put("intersectExpandHasMore", Map.of("type", "boolean",
                "description",
                "When **intersectThingNames** is used: sets **expandHasMore** (expand side may list more Things). "
                        + "**hasMore** ORs **queryHasMore** (QIT / internal listing truncation) with **expandHasMore**. Default false."));
    }

    /** Optional hierarchy display-name fragment for server-side ResolveNetworkID → GetAssetList → intersect (§6). */
    private static void addHierarchyNodeIdArgument(Map<String, Object> props) {
        props.put("hierarchyNodeId", Map.of("type", "string",
                "description",
                "Optional **hierarchy node id** (NetworkID) — **direct path** from Host Context or the page; "
                        + "calls **GetAssetList(hierarchyNodeId)** without **ResolveNetworkID**. "
                        + "Precedence after explicit **intersectThingNames**: **hierarchyNodeId** before **hierarchyNodeName**. "
                        + "Failures: **HIERARCHY_ASSET_LIST_FAILED** / **HIERARCHY_SCOPED_EMPTY** — **no** fallback to **hierarchyNodeName**."));
    }

    /** Optional hierarchy display-name fragment for server-side ResolveNetworkID → GetAssetList → intersect (§6). */
    private static void addHierarchyNodeNameArgument(Map<String, Object> props) {
        props.put("hierarchyNodeName", Map.of("type", "string",
                "description",
                "Optional **hierarchy node display-name fragment** from the **user message / conversation** (e.g. region or site). "
                        + "**Absolute first priority** for hierarchy-scoped intersect: when non-blank, the server calls "
                        + "**ResolveNetworkID(hierarchyNodeName)** then **GetAssetList** on the **single** resolved **NetworkID** "
                        + "(**0** rows → **HIERARCHY_RESOLVE_NOT_FOUND**; **2+** → **HIERARCHY_RESOLVE_AMBIGUOUS**; "
                        + "service failure → **HIERARCHY_RESOLVE_FAILED**). Omit when no hierarchy scope applies."));
    }

    public static void registerAll(ToolRegistry registry) {
        registerAll(registry, false, false);
    }

    /**
     * @param advertiseLegacyServiceDiscoveryTools when {@code true}, {@code discover_services} and
     *          {@code get_service_definition} receive {@link ToolDefinition}s merged to the LLM; when {@code false},
     *          they are {@linkplain ToolRegistry#registerExecutorOnly executor-only}. {@code discover_properties} is
     *          always executor-only for merge (replay / continuation still execute).
     */
    public static void registerAll(ToolRegistry registry, boolean advertiseLegacyServiceDiscoveryTools) {
        registerAll(registry, advertiseLegacyServiceDiscoveryTools, false);
    }

    /**
     * @param documentKnowledgeBuiltinsEnabled when {@code true}, register {@code search_document_chunks},
     *          {@code get_document_chunk}, and {@code resolve_document_set}; when {@code false}, skip them
     *          entirely (no executor-only reservation).
     */
    public static void registerAll(ToolRegistry registry, boolean advertiseLegacyServiceDiscoveryTools,
            boolean documentKnowledgeBuiltinsEnabled) {
        registry.register(invokeServiceDef(), InvokeServiceExecutor::executeInvokeService);
        registry.register(fetchCachedResultDef(), InvokeServiceExecutor::executeFetchCachedResult);
        registry.register(tabulateCachedResultDef(), CachedTabularToolsExecutor::executeTabulateCachedResult);
        registry.register(analyzeEntitySetDef(), AnalyzeEntitySetExecutor::execute);
        registry.register(summarizeCachedResultDef(), CachedTabularToolsExecutor::executeSummarizeCachedResult);
        // DIK-5: one conditional U5 analysis tool (operation enum); withdrawn when admission is off.
        if (AnalyzeCachedResultToolSchema.advertised()) {
            registry.register(analyzeCachedResultDef(), AnalyzeCachedResultExecutor::execute);
        } else {
            registry.registerExecutorOnly(AnalyzeCachedResultExecutor.TOOL_NAME,
                    AnalyzeCachedResultExecutor::execute);
        }
        // TQJ-5 Option A: model-visible path is tabulate_cached_result mode=*.
        // Keep executor-only names as demoted App/replay aliases sharing the same executors.
        registry.registerExecutorOnly(ExactJoinCachedResultExecutor.TOOL_NAME,
                ExactJoinCachedResultExecutor::execute);
        registry.registerExecutorOnly(QualityCachedResultExecutor.TOOL_NAME,
                QualityCachedResultExecutor::execute);
        registry.registerExecutorOnly(ResampleCachedResultExecutor.TOOL_NAME,
                ResampleCachedResultExecutor::execute);
        registry.registerExecutorOnly(RollingCachedResultExecutor.TOOL_NAME,
                RollingCachedResultExecutor::execute);
        registry.registerExecutorOnly(RateOfChangeCachedResultExecutor.TOOL_NAME,
                RateOfChangeCachedResultExecutor::execute);
        registry.registerExecutorOnly(PeriodCompareCachedResultExecutor.TOOL_NAME,
                PeriodCompareCachedResultExecutor::execute);
        registry.register(inspectCachedPayloadDef(), CachedPayloadInspect::executeInspectCachedPayload);
        registry.register(extractNestedDef(), ExtractNestedCachedResult::executeExtractNested);
        registry.register(buildChartFromTabularResultDef(), BuildChartFromTabularResultExecutor::execute);
        registry.register(declareChartGroupDef(), DeclareChartGroupExecutor::execute);
        registry.register(buildHistoryOverlayChartDef(), BuildHistoryOverlayChartExecutor::execute);
        if (advertiseLegacyServiceDiscoveryTools) {
            registry.register(discoverServicesDef(), MetadataDiscoveryExecutor::executeDiscoverServices);
            registry.register(getServiceDefinitionDef(), MetadataDiscoveryExecutor::executeGetServiceDefinition);
        } else {
            registry.registerExecutorOnly("discover_services", MetadataDiscoveryExecutor::executeDiscoverServices);
            registry.registerExecutorOnly("get_service_definition", MetadataDiscoveryExecutor::executeGetServiceDefinition);
        }
        registry.registerExecutorOnly("discover_properties", MetadataDiscoveryExecutor::executeDiscoverProperties);
        registry.register(discoverThingMembersDef(), DiscoverThingMembersExecutor::execute);
        // Legacy discover_services / get_service_definition / discover_properties: executor-only by default for LLM
        // merge; optional advertisement via AgentSettings.advertiseLegacyServiceDiscoveryTools (legacy-discovery-executor-only).
        registry.registerExecutorOnly("get_entity", GetEntityExecutor::execute);
        registry.register(describeEntitySchemaDef(), DescribeEntitySchemaExecutor::execute);
        registry.register(queryEntitiesDef(), QueryEntitiesExecutor::executeQueryEntities);
        registry.register(queryEntitiesByTaxonomyDef(), QueryEntitiesByTaxonomyExecutor::executeQueryEntitiesByTaxonomy);
        registry.register(listAssetTypesDef(), TaxonomyResolverExecutor::executeListAssetTypes);
        registry.register(resolveAssetTypeDef(), TaxonomyResolverExecutor::executeResolveAssetType);
        registry.register(resolveThingDef(), TaxonomyResolverExecutor::executeResolveThing);
        registry.register(listEntitiesByTypeDef(), ListEntitiesByTypeExecutor::execute);
        registry.register(getPropertyValuesDef(), PropertyToolsExecutor::executeGetPropertyValues);
        registry.register(queryPropertyHistoryDef(), PropertyToolsExecutor::executeQueryPropertyHistory);
        registry.registerExecutorAlias("query_numeric_property_history", "query_property_history");
        registry.registerExecutorAlias("query_value_stream_property_history", "query_property_history");
        registry.register(queryStreamDataDef(), StreamValueStreamToolsExecutor::executeQueryStreamData);
        registry.register(queryAlertSummaryDef(), AlertToolsExecutor::executeQueryAlertSummary);
        registry.register(queryAlertHistoryDef(), AlertToolsExecutor::executeQueryAlertHistory);
        registry.register(acknowledgeAlertsDef(), AlertToolsExecutor::executeAcknowledgeAlerts);
        registry.register(setPropertyValueDef(), BuiltInTools::executeSetPropertyValue);
        registry.register(spotlightSearchDef(), SpotlightSearchExecutor::executeSpotlightSearch);
        if (documentKnowledgeBuiltinsEnabled) {
            registry.register(DocumentKnowledgeToolSchemas.searchDocumentChunksDef(),
                    DocumentKnowledgeToolsExecutor::executeSearchDocumentChunks);
            registry.register(DocumentKnowledgeToolSchemas.getDocumentChunkDef(),
                    DocumentKnowledgeToolsExecutor::executeGetDocumentChunk);
            registry.register(DocumentKnowledgeToolSchemas.resolveDocumentSetDef(),
                    DocumentKnowledgeToolsExecutor::executeResolveDocumentSet);
        }
        registerGetAgentSkill(registry);
        registerLoadToolSchemas(registry);
        LOG.info("BuiltInTools.registerAll complete: {} tools in registry", registry.size());
    }

    /**
     * Registers only {@code get_agent_skill} (used when {@code enableBuiltInTools} is false so skill catalog text
     * remains accurate).
     */
    public static void registerGetAgentSkillOnly(ToolRegistry registry) {
        registerGetAgentSkill(registry);
        registerLoadToolSchemas(registry);
        LOG.info("BuiltInTools.registerGetAgentSkillOnly: {} tools in registry", registry.size());
    }

    private static void registerGetAgentSkill(ToolRegistry registry) {
        registry.register(getAgentSkillDef(), GetAgentSkillExecutor::execute);
    }

    /**
     * Registers the {@code load_tool_schemas} meta-tool executor-only (dispatchable but not advertised by default).
     * The {@code lazy} admission round filter advertises it with a per-turn catalog
     * (docs/operations/tool-schema-admission-control.md M3).
     */
    private static void registerLoadToolSchemas(ToolRegistry registry) {
        registry.registerExecutorOnly("load_tool_schemas", LoadToolSchemasExecutor::execute);
    }

    /** Load registered agent skill body by short id (Service or repository source; no LLM-supplied path injection). */
    private static ToolDefinition getAgentSkillDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("skill_name", Map.of(
                "type", "string",
                "description",
                "Short skill id of a **registered** agent skill. Skills are loaded from the configured "
                        + "`configurationRepository` FileRepository (`/skills/<id>/SKILL.md`). "
                        + "The per-turn catalog lists each skill’s `source` (always `repository` when present). "
                        + "Pass the short id only (same token as `/SkillName` in the user message), never the full Service name. "
                        + "Example ids: OrderWorkflow, alert_query."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"skill_name"});

        return new ToolDefinition("get_agent_skill",
                "Loads the full instructions text for a registered agent skill (Service or repository source). "
                        + "The chat system prompt lists skills with metadata only; call this tool to retrieve the complete body when relevant. "
                        + "On success the result is the skill body string; on failure a JSON object with `status`, `code`, and `message`.",
                schema);
    }

    private static Map<String, Object> rootServiceTargetEntityTypeProperty() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("enum", ThingworxRootEntityTypes.sortedRootEntityTypeNames());
        m.put("description",
                "Root ThingWorx entity type (from platform ThingworxEntityTypes). Concrete DataTable / Stream / ValueStream **Things** use "
                        + "entityType \"Thing\" and the instance name; the server may also normalize a GenericThing-derived ThingTemplate name "
                        + "(e.g. DataTable) to Thing when it appears in the dependency catalog.");
        return m;
    }

    // ── invoke_service ──────────────────────────────────────────────────
    // The single most powerful meta-tool: invokes ANY service on ANY entity.
    // Equivalent to mcp-twx's invoke_service but via direct Java API calls.
    private static ToolDefinition invokeServiceDef() {
        return InvokeServiceToolSchemaFragment.invokeServiceToolDefinition();
    }

    private static ToolDefinition fetchCachedResultDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("cacheId", Map.of("type", "string",
                "description", "cacheId from a prior large tabular tool (e.g. invoke_service INFOTABLE_LARGE **when** "
                        + "the response included cacheId — omit paging when cacheId was absent)."));
        props.put("offset", Map.of("type", "integer",
                "description", "Zero-based row offset (default 0). Success echoes offsetRequested / offsetEffective "
                        + "after clamp against totalRows."));
        props.put("limit", Map.of("type", "integer",
                "description", "Max rows to return (default 50, max 200). Success echoes limitRequested / limitEffective."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"cacheId"});

        return new ToolDefinition("fetch_cached_result",
                "After a large tabular tool (**invoke_service** INFOTABLE_LARGE, query_entities, query_entities_by_taxonomy, "
                        + "or list_entities_by_type) returned a **cacheId**, fetch a **page** from the in-memory cache for "
                        + "**this conversation** for UI display or browsing. Read-only paging — does not sort or aggregate. "
                        + "Paging is clamp-and-echo: **offset**/**limit** on success are effective values; also read "
                        + "**offsetRequested**/**limitRequested** when diagnosing overshoot. "
                        + "Do **not** call repeatedly to read the entire table into the model — use **tabulate_cached_result** "
                        + "or **summarize_cached_result** for full-table computation. If **cacheId** was omitted, **do not** "
                        + "call this tool — answer from **sampleRows** / **hint**.",
                schema, true);
    }

    private static ToolDefinition tabulateCachedResultDef() {
        Map<String, Object> schema = TabulateCachedResultToolSchema.parametersSchema();
        String desc = "Deterministic transforms over a **conversation-cached** tabular result (full table per cacheId; "
                + "fetch_cached_result is paging only). LARGE outputs get a new cacheId. "
                + "Modes: **filter_count**, **filter_rows**, **filter_sort_topn**, **group_metric** "
                + "(cached-table-decision-tools.md). "
                + "Distribution: **bin_numeric** (histogram bins with count/density) and **box_summary** "
                + "(per-group quartiles, whiskers, outliers) — run these before charting a distribution. ";
        List<String> u4 = TabulateCachedResultToolSchema.advertisedModes();
        if (u4.contains(TabulateCachedResultToolSchema.MODE_EXACT_JOIN)) {
            desc += "Also **exact_join** (INNER/LEFT). ";
        }
        if (u4.contains(TabulateCachedResultToolSchema.MODE_QUALITY)) {
            desc += "Also **quality** (window assessment). ";
        }
        if (u4.contains(TabulateCachedResultToolSchema.MODE_RESAMPLE)) {
            desc += "Also **resample**. ";
        }
        if (u4.contains(TabulateCachedResultToolSchema.MODE_ROLLING)) {
            desc += "Also **rolling**. ";
        }
        if (u4.contains(TabulateCachedResultToolSchema.MODE_RATE_OF_CHANGE)) {
            desc += "Also **rate_of_change**. ";
        }
        if (u4.contains(TabulateCachedResultToolSchema.MODE_PERIOD_COMPARE)) {
            desc += "Also **period_compare** (assessment-only). ";
        }
        boolean anyU4 = u4.contains(TabulateCachedResultToolSchema.MODE_EXACT_JOIN)
                || u4.contains(TabulateCachedResultToolSchema.MODE_QUALITY)
                || u4.contains(TabulateCachedResultToolSchema.MODE_RESAMPLE)
                || u4.contains(TabulateCachedResultToolSchema.MODE_ROLLING)
                || u4.contains(TabulateCachedResultToolSchema.MODE_RATE_OF_CHANGE)
                || u4.contains(TabulateCachedResultToolSchema.MODE_PERIOD_COMPARE);
        if (anyU4) {
            desc += "U4 modes return analysisEnvelope, not insightEnvelope. ";
        }
        desc += "Use for Top-N, group metrics, thresholds, and filtered tables — not sample-row guessing.";
        return new ToolDefinition("tabulate_cached_result", desc, schema, true);
    }

    private static ToolDefinition analyzeCachedResultDef() {
        Map<String, Object> schema = AnalyzeCachedResultToolSchema.parametersSchema();
        String desc = "Deterministic U5 analysis over a **conversation-cached** series handle. "
                + "One tool with **operation** enum: "
                + "outlier | change_point | spc | relationship | trend | threshold_crossing. "
                + "Requires **cacheId**, **timeColumn**, and **valueColumn** (plus **rightCacheId** for "
                + "relationship). Returns compact **analysisEnvelope** (status, outcomeCode, method, "
                + "support, metrics). Does **not** invent causality; relationship is association "
                + "evidence only. Handle inputs only — never pass row/value arrays.";
        return new ToolDefinition(AnalyzeCachedResultToolSchema.TOOL_NAME, desc, schema, true);
    }

    private static ToolDefinition summarizeCachedResultDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("cacheId", Map.of("type", "string",
                "description", "Cached table to summarize (full table). "
                        + "May be __PARLER_LAST_QUALIFYING_TABULAR_CACHE__ (same resolution order as tabulate_cached_result: per-turn then conversation mirror)."));
        props.put("percentileColumns", Map.of(
                "type", "array",
                "description", "Optional JSON array of non-empty strings (column names) for p50/p95 on numeric columns only. "
                        + "Must be an array of strings (no nulls, numbers, or objects); empty strings invalid. "
                        + "If omitted, p50/p95 apply only to the first 8 numeric columns in declaration order; others still get min/max/mean.",
                "items", Map.of("type", "string")));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"cacheId"});
        return new ToolDefinition("summarize_cached_result",
                "Column-level stats (null counts, numeric min/max/mean and capped p50/p95, categorical cardinality/top) "
                        + "over the **full** cached InfoTable. Success includes sourceCacheId. "
                        + "Use for summaries and null-heavy questions instead of eyeballing row JSON.",
                schema, true);
    }

    private static ToolDefinition inspectCachedPayloadDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("cacheId", Map.of("type", "string",
                "description", "Conversation cacheId from a LARGE_JSON invoke_service result (or a prior extract)."));
        props.put("mode", Map.of(
                "type", "string",
                "description", "inspect = bounded structure hints; extract = path-selected subtree (re-caches when over cap).",
                "enum", new String[]{"inspect", "extract"}));
        props.put("path", Map.of("type", "string",
                "description", "Required for mode=extract. Dotted keys + explicit [index] only (no glob/JSONPath)."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"cacheId", "mode"});
        // playbookSafe=false until deferred playbook-nested-consumption gate (no PlaybookJsonRowPath in U2).
        return new ToolDefinition("inspect_cached_payload",
                "Bounded inspect/extract over a **cached JSON/TEXT** payload (LARGE_JSON cacheId). "
                        + "Does not load the full oversize body into the model. "
                        + "For nested INFOTABLE cells inside a cached **table**, use **extract_nested** instead.",
                schema, false);
    }

    private static ToolDefinition extractNestedDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("sourceCacheId", Map.of("type", "string",
                "description", "Cached tabular cacheId that contains a nested INFOTABLE cell."));
        props.put("cellPath", Map.of("type", "string",
                "description", "Cell path: [rowIndex].fieldName… (dotted keys + explicit indexes only)."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"sourceCacheId", "cellPath"});
        // playbookSafe=false until deferred playbook-nested-consumption gate (no PlaybookJsonRowPath in U2).
        return new ToolDefinition("extract_nested",
                "Promote a nested INFOTABLE cell from a cached table into a **new** cacheId with lineage "
                        + "(sourceCacheId + cellPath). Use after inspect/tabulate shows nested tables that need "
                        + "their own fetch/tabulate/chart path.",
                schema, false);
    }

    private static ToolDefinition analyzeEntitySetDef() {
        return new ToolDefinition("analyze_entity_set",
                "Deterministic set algebra over two **already cached** entity/list tables: **difference**, **intersection**, **union**, or **symmetric_difference**. Does **not** query ThingWorx. "
                        + "Operands must supply explicit **cacheId** values (never the last-tabular sentinel). "
                        + "Every success returns a **new cacheId** for the full transformed table (including small inline rows) "
                        + "so the next step can call **tabulate_cached_result** / **build_chart_from_tabular_result** without paging the set. "
                        + "Do **not** pass **groupBy** here — use **tabulate_cached_result(mode=group_metric)** on the returned cacheId. "
                        + "Typical flows: template-minus-taxonomy → **difference**; overlap → **intersection**; combine two lists → **union**; items in exactly one list → **symmetric_difference**; then **tabulate_cached_result** → chart.",
                AnalyzeEntitySetToolSchema.parametersSchema(), true);
    }

    private static ToolDefinition buildHistoryOverlayChartDef() {
        Map<String, Object> seriesProps = new LinkedHashMap<>();
        seriesProps.put("label", Map.of("type", "string", "description", "Legend label for this trace."));
        seriesProps.put("thingName", Map.of("type", "string", "description", LLM_THINGNAME_SCALAR_DESCRIPTION));
        seriesProps.put("calendarPhrase", Map.of("type", "string",
                "description", "Optional single-day phrase resolved from anchorTime + user_timezone."));
        seriesProps.put("relativeDuration", Map.of("type", "string",
                "description", "Optional duration ending at the series anchor (e.g. 2h). Mutually exclusive with calendarPhrase and explicit bounds."));
        seriesProps.put("anchorOffset", Map.of("type", "string",
                "description", "Optional shift back from shared anchorTime before resolving this series window (e.g. 7d). "
                        + "Use with relativeDuration for shifted windows. Mutually exclusive with explicit startTime/endTime."));
        seriesProps.put("startTime", Map.of("type", "string",
                "description", "Optional ISO-8601 window start (alias: start)."));
        seriesProps.put("endTime", Map.of("type", "string",
                "description", "Optional ISO-8601 window end (alias: end)."));
        Map<String, Object> seriesItem = new LinkedHashMap<>();
        seriesItem.put("type", "object");
        seriesItem.put("properties", seriesProps);
        seriesItem.put("required", new String[]{"label", "thingName"});
        Map<String, Object> seriesArr = new LinkedHashMap<>();
        seriesArr.put("type", "array");
        seriesArr.put("minItems", 2);
        seriesArr.put("maxItems", 6);
        seriesArr.put("items", seriesItem);
        seriesArr.put("description",
                "2–6 history traces. Each entry resolves its own time window from shared anchorTime.");

        Map<String, Object> yRefItem = new LinkedHashMap<>();
        yRefItem.put("type", "object");
        yRefItem.put("properties", Map.of(
                "y", Map.of("type", "number", "description", "Y-axis value (finite numeric)."),
                "label", Map.of("type", "string", "description", "Optional line label."),
                "role", Map.of("type", "string",
                        "description", "Optional role: usl, ucl, lcl, lsl, target, limit, warning (default limit).")));
        yRefItem.put("required", new String[]{"y"});
        Map<String, Object> yRefArr = new LinkedHashMap<>();
        yRefArr.put("type", "array");
        yRefArr.put("maxItems", 12);
        yRefArr.put("items", yRefItem);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("propertyName", Map.of("type", "string",
                "description", "Same numeric property on every series Thing (NUMBER/INTEGER/LONG)."));
        props.put("series", seriesArr);
        props.put("xAxisMode", Map.of("type", "string",
                "enum", java.util.List.of("absolute_time", "elapsed_time", "normalized_time"),
                "description", "X-axis mode. absolute_time = real timestamps (same-window cross-Thing). "
                        + "elapsed_time = seconds from each series window start (shifted windows). "
                        + "normalized_time = map each series window to 0..1 for shape/profile comparison "
                        + "(different durations). When omitted, server picks absolute_time when all windows match, "
                        + "else elapsed_time. Set normalized_time explicitly for shape comparisons — not inferred."));
        props.put("chart_kind", Map.of("type", "string",
                "enum", java.util.List.of("line", "scatter"),
                "description", "Chart kind (default line)."));
        props.put("anchorTime", Map.of("type", "string",
                "description", "Optional ISO anchor for resolving all series windows (default: agent turn clock now)."));
        props.put("title", Map.of("type", "string", "description", "Optional chart title."));
        props.put("yLabel", Map.of("type", "string", "description", "Optional Y-axis label."));
        props.put("yReferenceLines", yRefArr);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"propertyName", "series"});

        return new ToolDefinition("build_history_overlay_chart",
                "Overlay **2–6 numeric property history traces** (each series: own Thing + window). "
                        + "**absolute_time** = same real window; **elapsed_time** = shifted windows; "
                        + "**normalized_time** = shape/profile (0..1). Prefer over repeated query_property_history. "
                        + "Long cached tables: use build_chart_from_tabular_result with seriesColumn instead. "
                        + "Result `seriesCaches[]` lists each series' `cacheId`, columns, `timeColumn` / `valueColumn` "
                        + "and the resolved request window (not coverage) for reuse by cached-result tools.",
                schema, true);
    }

    private static ToolDefinition declareChartGroupDef() {
        return new ToolDefinition(DeclareChartGroupExecutor.TOOL_NAME,
                "Declare one chart group (2-6 named slots) before building its charts, only when the user asks for a "
                        + "set of comparable charts; then build each member with build_chart_from_tabular_result and "
                        + "its groupMemberKey. One group per request; slots you do not build end as errors.",
                DeclareChartGroupToolSchema.parametersSchema(), false);
    }

    private static ToolDefinition buildChartFromTabularResultDef() {
        return new ToolDefinition("build_chart_from_tabular_result",
                "Build a Parler chart from a tabular tool result (invoke_service INFOTABLE*, query_entities, "
                        + "query_entities_by_taxonomy, list_entities_by_type, fetch_cached_result, tabulate_cached_result, "
                        + "or an extended-tool JSON result whose decoded `result` object is a complete small single table: "
                        + "business `status` absent or `success`, non-empty root `rows`, no partial-page signal, within inline limit). "
                        + "That JSON shape is a valid `last_invoke` source — do not re-query the same service through another entry point. "
                        + "From **analyze_entity_set**, tabulate (or fetch) its **cacheId** first. "
                        + "Supply **either** `kind` (line|bar|scatter|pie) **or** `intent` — never both or neither (provider-safe root schema). "
                        + "`source: \"last_invoke\"` = **most recent** qualifying tabular result of the **current request** (latest-wins; "
                        + "several qualifying results in one turn do **not** make it ambiguous). "
                        + "`source: \"cache_id\"` charts an earlier table via a returned top-level `cacheId` (qualifying JSON single tables "
                        + "**may** carry one on its outer envelope too). "
                        + "Evidence ids (`e1`, `e2`, …), tool call ids and `chartId` are **not** cacheIds. "
                        + "Use only a handle a result actually returned; never invent one; do not retry the same invalid id. "
                        + "A **new user request** starts `last_invoke` empty — history does not make them chartable without a fresh query. "
                        + "A `cacheId` you still hold stays usable with `source: \"cache_id\"` rather than re-running the query. "
                        + "Query A → chart A → query B → chart B; **one turn may emit several charts**. "
                        + "`CHART_EMITTED` means count it as done — do **not** rebuild it or claim only one chart per turn is allowed. "
                        + "See chart-intent.md and CHART_CONTRACT.md §2.5.",
                BuildChartFromTabularResultToolSchema.parametersSchema(), true);
    }

    // ── discover_services / get_service_definition / discover_properties / discover_thing_members (docs/agent/metadata_discovery.md)
    private static ToolDefinition discoverServicesDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("entityType", rootServiceTargetEntityTypeProperty());
        props.put("entityName", Map.of("type", "string", "description", "Entity name"));
        props.put("namePrefix", Map.of("type", "string", "description", "Optional filter: service names starting with this prefix"));
        props.put("offset", Map.of("type", "integer", "description", "Zero-based offset (default 0)"));
        props.put("maxItems", Map.of("type", "integer", "description", "Max services to return (default 80, max 200)"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"entityType", "entityName"});
        return new ToolDefinition("discover_services",
                "List service names and short descriptions on an entity (instance-effective). "
                        + "For **entityType Thing** (including GenericThing-normalized template names), the executor uses the same "
                        + "**visibility-aware** lookup and **public** service list as **discover_thing_members** — **for new "
                        + "prompts call discover_thing_members** (facet `services` or singular `service` with `memberName`); it "
                        + "also covers **properties**, **events**, and **subscriptions** facets that this tool does not. "
                        + "Keep this tool for historic replay or sequences that already invoked discover_services. "
                        + "For **non-Thing** entityType, this remains the normal path toward **get_service_definition** and **invoke_service**. "
                        + "Use to find which service to run next via invoke_service, or when invoke_service fails with SERVICE_NOT_FOUND. "
                        + "To list entities by collection type (ThingTemplate, Mashup, …), prefer list_entities_by_type over invoking EntityServices by hand. "
                        + "For parameters, call get_service_definition then invoke_service.",
                schema);
    }

    private static ToolDefinition getServiceDefinitionDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("entityType", rootServiceTargetEntityTypeProperty());
        props.put("entityName", Map.of("type", "string", "description", "Entity name"));
        props.put("serviceName", Map.of("type", "string", "description", "Service name"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"entityType", "entityName", "serviceName"});
        return new ToolDefinition("get_service_definition",
                "Slim service definition: parameters (name, baseType, required, dataShape for INFOTABLE) and result type. "
                        + "For **entityType Thing**, the same public service metadata as **discover_thing_members** facet `service` "
                        + "(including **invoke_service**-shaped **invokeExample** on success) — **for new Thing service-shape prompts "
                        + "prefer discover_thing_members** with facet `service` and **memberName**; keep this tool for replay, "
                        + "non-Thing entities, or when you already have a service name from a prior get_service_definition result. "
                        + "Parameters with baseType QUERY: pass a structured JSON object (filters/sorts/pagination) — preferred; "
                        + "a textual JSON object {...} may be accepted as a fallback, but not arrays or non-objects. "
                        + "Use to build invoke_service parameters.",
                schema);
    }

    private static ToolDefinition discoverPropertiesDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("thingName", Map.of("type", "string",
                "description", "Canonical ThingName. If uncertain, use resolve_thing first; on IDENTITY_AMBIGUOUS or no match, "
                        + "try spotlight_search."));
        props.put("namePrefix", Map.of("type", "string", "description", "Optional property name prefix filter"));
        props.put("offset", Map.of("type", "integer", "description", "Zero-based offset (default 0)"));
        props.put("maxItems", Map.of("type", "integer", "description", "Max properties (default 80, max 200)"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"thingName"});
        return new ToolDefinition("discover_properties",
                "List property definitions for a **Thing** instance (baseType, dataShape, category, etc.). "
                        + "Execution delegates to the same visibility-aware **`discover_thing_members`** engine "
                        + "(facet=properties). **Legacy-compatible** name — **for new prompts use `discover_thing_members`** "
                        + "(facet `properties` or singular `property`); it also surfaces **services**, **events**, and **subscriptions** "
                        + "facets that this tool does not. Keep this tool when replaying or continuing a thread "
                        + "that already called discover_properties. "
                        + "thingName must be canonical — resolve via resolve_thing (or spotlight_search) first if uncertain. "
                        + "For ThingTemplate / ThingShape / DataShape schema, prefer **describe_entity_schema**; a rare persisted "
                        + "replay may still call **get_entity** (executor-only, not on the merged LLM tool list) for a stamped "
                        + "**parler.entity.metadata.v1** combined dump when facets are truly insufficient.",
                schema);
    }

    private static ToolDefinition discoverThingMembersDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("thingName", Map.of("type", "string",
                "description", "Canonical Thing name. If uncertain, use resolve_thing first; on no match, try spotlight_search."));
        props.put("facet", Map.of(
                "type", "string",
                "enum", java.util.List.of("properties", "property", "services", "service", "events", "event",
                        "subscriptions"),
                "description",
                "Member facet. v1: properties (list), property (singular, requires memberName), services (public list), "
                        + "service (singular public service, requires memberName), events (list), event (singular, requires "
                        + "memberName), subscriptions (configured multi-event subscription list, read-only)."));
        props.put("memberName", Map.of("type", "string",
                "description", "Required for singular facets property, service, and event — member name "
                        + "(case-insensitive fallback after exact match for property, service, and event). "
                        + "Must be omitted for list facets (properties, services, events, subscriptions); use namePrefix "
                        + "to narrow a list — passing memberName on a list facet returns UNSUPPORTED_TOOL_PARAMETER."));
        props.put("namePrefix", Map.of("type", "string", "description", "Optional list filter on member name prefix."));
        props.put("category", Map.of("type", "string", "description", "Optional category filter where applicable."));
        props.put("baseType", Map.of("type", "string",
                "description", "Optional filter: property base type, or service result base type for facet=services "
                        + "(ignored for other facets when present)."));
        props.put("dataShape", Map.of("type", "string",
                "description", "Optional INFOTABLE dataShape filter for facet=properties (property aspect) and "
                        + "facet=events (EventDefinition.getDataShapeName); ignored for services and subscriptions."));
        props.put("offset", Map.of("type", "integer", "description", "Zero-based list offset (default 0)."));
        props.put("maxItems", Map.of("type", "integer", "description", "Max list rows (default 80, max 200)."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[] {"thingName"});
        return new ToolDefinition("discover_thing_members",
                "**Preferred** built-in for **concrete Thing** member metadata (properties, **public** services, **events**, "
                        + "and **configured subscriptions** in v1): visibility-aware lookup, bounded pagination, list and "
                        + "singular facets, same success/error envelope family as describe_entity_schema. "
                        + "This is the default greenfield path for Thing member metadata (properties / public services / "
                        + "events / subscriptions). "
                        + "Does not read property values, invoke services, fire events, refresh subscriptions, or return "
                        + "schema-entity definitions — for ThingTemplate / ThingShape / DataShape use describe_entity_schema.",
                schema, true);
    }

    // ── get_entity ──────────────────────────────────────────────────────
    private static ToolDefinition getEntityDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("entityType", Map.of(
                "type", "string",
                "enum", java.util.List.of("ThingTemplate", "ThingShape", "DataShape"),
                "description", "Entity type whose schema/definition you want. "
                        + "For a specific Thing instance's properties, use discover_thing_members + get_property_values instead."));
        props.put("entityName", Map.of("type", "string",
                "description", "Canonical name of the ThingTemplate, ThingShape, or DataShape."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"entityType", "entityName"});

        return new ToolDefinition("get_entity",
                "Executor-only / replay path: full combined schema/metadata for a ThingTemplate, ThingShape, or DataShape "
                        + "(properties, services, events with definitions). **Not merged into the LLM tool list** — call via "
                        + "persisted replay or **executeTool** when a stamped **parler.entity.metadata.v1** / Tier B payload requires it. "
                        + "**Prefer describe_entity_schema** for normal facet reads on schema entities; **prefer discover_thing_members + "
                        + "get_property_values** for concrete Thing instances. For a Thing's template chain, resolve the Thing then use "
                        + "**describe_entity_schema** on its ThingTemplate.",
                schema);
    }

    // ── describe_entity_schema ───────────────────────────────────────────
    private static ToolDefinition describeEntitySchemaDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("entityType", Map.of(
                "type", "string",
                "enum", java.util.List.of("ThingTemplate", "ThingShape", "DataShape"),
                "description", "Schema entity type. Thing instances are not accepted — use discover_thing_members + "
                        + "get_property_values for concrete Things."));
        props.put("entityName", Map.of("type", "string",
                "description", "Canonical name of the ThingTemplate, ThingShape, or DataShape."));
        props.put("facet", Map.of(
                "type", "string",
                "enum", java.util.List.of("summary", "properties", "services", "events", "fields", "service"),
                "description",
                "Facet to return. v1: summary (counts), list facets properties/services/events (ThingTemplate/ThingShape), "
                        + "fields (DataShape), singular service (memberName required). Singular property/event/field and "
                        + "subscriptions are not supported in v1 — use additional **describe_entity_schema** calls per facet; "
                        + "do not treat a single combined metadata dump as the default path. Persisted replay may still "
                        + "invoke **get_entity** (off-list) only when a stamped **parler.entity.metadata.v1** payload is explicitly required."));
        props.put("memberName", Map.of("type", "string",
                "description", "Required when facet is \"service\" — exact service name."));
        props.put("scope", Map.of(
                "type", "string",
                "enum", java.util.List.of("effective", "local"),
                "description", "effective (default): inherited definitions via platform effective APIs. "
                        + "local: ThingTemplate/ThingShape — members from getInstanceShape() only (not merged instance service/event lists); "
                        + "DataShape — getDataShape() before effective merge."));
        props.put("namePrefix", Map.of("type", "string", "description", "Optional name prefix filter for list facets."));
        props.put("category", Map.of("type", "string",
                "description", "Optional category filter for property/service/event list facets."));
        props.put("baseType", Map.of("type", "string",
                "description", "Optional base type filter for property/field list facets."));
        props.put("dataShape", Map.of("type", "string",
                "description", "Optional INFOTABLE dataShape filter for property/field list facets."));
        props.put("offset", Map.of("type", "integer", "description", "Zero-based offset for list facets (default 0)."));
        props.put("maxItems", Map.of("type", "integer",
                "description", "Page size for list facets (default 80, max 200)."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"entityType", "entityName"});

        return new ToolDefinition("describe_entity_schema",
                "Facet-bounded schema read for ThingTemplate, ThingShape, or DataShape: summary, paginated property/service/event "
                        + "lists, DataShape fields, or one public service definition (parameters + result type). "
                        + "Uses visibility-aware entity resolution. Does not return service implementation bodies. "
                        + "Code DESCRIBE_ENTITY_SCHEMA_PLATFORM_UNAVAILABLE means required platform schema APIs were missing, failed, or threw during template/shape member reads (local or effective list facets) — not an authoritative empty schema. "
                        + "**Default** inspection for schema entities: stay on this tool's facets. Tier B / persisted replay may still "
                        + "run **get_entity** (executor-only, not merged into the LLM tool list) when one stamped **parler.entity.metadata.v1** "
                        + "combined payload is required.",
                schema, true);
    }

    // ── query_entities (structured filter; not Spotlight) ───────────────
    private static ToolDefinition queryEntitiesDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("entityType", Map.of("type", "string",
                "description", "Use Thing (default semantics: list Thing instances implementing the template or shape)."));
        props.put("thingTemplate", Map.of("type", "string",
                "description", "ThingTemplate name. Exactly one of thingTemplate or thingShape is required."));
        props.put("thingShape", Map.of("type", "string",
                "description", "ThingShape name. Exactly one of thingTemplate or thingShape is required."));
        props.put("maxItems", Map.of("type", "integer",
                "description", "Page size / max rows (default 50, max 200). Maps to platform maxItems / nMaxItems."));
        props.put("offset", Map.of("type", "integer",
                "description", "Row offset for pagination when using QueryImplementingThingsOptimized* (default 0)."));
        props.put("namePrefix", Map.of("type", "string",
                "description", "Optional name mask / prefix passed to platform nameMask when supported."));
        props.put("withPermissions", Map.of("type", "boolean",
                "description", "Maps to platform withPermissions on Optimized services (include read/update/delete permission columns). Default false."));
        props.put("withData", Map.of("type", "boolean",
                "description", "Deprecated alias: treated like withPermissions for backward compatibility (Optimized services have no withData parameter)."));
        props.put("includeDescription", Map.of("type", "boolean",
                "description", "Add basic column description (semantic / UI). Default false; default basic columns are name only."));
        props.put("includeIsSystemObject", Map.of("type", "boolean",
                "description", "Add basic column isSystemObject. Default false."));
        props.put("includeTags", Map.of("type", "boolean",
                "description", "Add basic column tags. Default false."));
        props.put("includeConcreteTemplate", Map.of("type", "boolean",
                "description", "Add propertyNames column thingTemplate (leaf template per row). Only when thingTemplate is set; invalid with thingShape."));
        props.put("widePropertyColumns", Map.of("type", "boolean",
                "description", "If true, omit basicPropertyNames/propertyNames so the platform may return all columns (heavy). "
                        + "Default false: pass name-only basics + empty propertyNames EntityList (docs/agent/query_with_total_count.md §10)."));
        Map<String, Object> queryProp = new LinkedHashMap<>();
        queryProp.put("type", "object");
        queryProp.put("description",
                "Optional platform query object (filters/sorts) — ThingWorx-shaped Query dialect / query-spec subset "
                        + "(see docs/agent/query_capability.md). Only Shape/Template fields for Optimized.");
        props.put("query", queryProp);
        Map<String, Object> mtItem = new LinkedHashMap<>();
        mtItem.put("type", "object");
        mtItem.put("properties", Map.of(
                "vocabulary", Map.of("type", "string"),
                "vocabularyTerm", Map.of("type", "string")));
        mtItem.put("required", new String[]{"vocabulary", "vocabularyTerm"});
        Map<String, Object> modelTagsArr = new LinkedHashMap<>();
        modelTagsArr.put("type", "array");
        modelTagsArr.put("items", mtItem);
        modelTagsArr.put("description",
                "Optional platform Model tags on the service `tags` parameter (AND). "
                        + "For tag-based row filters inside a query object use the `query` parameter instead — different mechanism.");
        props.put("modelTags", modelTagsArr);
        addHierarchyNodeIdArgument(props);
        addHierarchyNodeNameArgument(props);
        addIntersectToolArguments(props, false);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"entityType"});
        // E15/B4: XOR is enforced by QueryEntitiesExecutor + description prose.
        // Do NOT encode as root oneOf/anyOf/allOf — strict providers reject that shape
        // (see LlmJsonSchemaCompat.assertNoRootSchemaCombinators; chart-intent prior fix).

        return new ToolDefinition("query_entities",
                "List Things implementing a ThingTemplate or ThingShape. Model keys (**Stream**, **DataTable**, …) map to "
                        + "templates/shapes (key-resolution.md Phase 0.5). For taxonomy-scoped asset-class lists use "
                        + "**resolve_asset_type** then **query_entities_by_taxonomy** — taxonomy first, not template guesswork. "
                        + "Provide **exactly one** of thingTemplate or thingShape (both or neither → BOTH_TEMPLATE_AND_SHAPE / MISSING_TEMPLATE_OR_SHAPE). "
                        + "Root JSON Schema is provider-safe; mutual exclusion is not expressed as a root-level `oneOf`. "
                        + "Default columns are lean (name only); widen via includeDescription/includeTags/widePropertyColumns. "
                        + "Uses QueryImplementingThingsOptimizedWithTotalCount when available. Optional modelTags, query filters, "
                        + "**hierarchyNodeId**/**hierarchyNodeName** (GetAssetList intersect), and **intersectThingNames**. "
                        + "**totalRows** reflects full match count when known (totalRowsInferred when inferred). "
                        + "Large sets cache like fetch_cached_result. Not fuzzy search — use spotlight_search.",
                schema, true);
    }

    /** Taxonomy-style listing + projection + exact-match property filter on implementors of one template or shape. */
    private static ToolDefinition queryEntitiesByTaxonomyDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("EntityType", Map.of("type", "string",
                "description", "Exactly \"ThingTemplate\" or \"ThingShape\" (case-sensitive). Only one parent dimension per call."));
        props.put("EntityName", Map.of("type", "string",
                "description", "ThingTemplate or ThingShape name. Prefer the **exact** strings returned by **resolve_asset_type** when the user refers to an application asset class. Existence is not pre-checked; invalid names fail at runtime."));
        props.put("CriticalProperties", Map.of("type", "string",
                "description", "Semicolon-separated property names (trimmed; empty segments dropped). "
                        + "These columns are included in the projected rootEntityList (along with name). Duplicate \"name\" is allowed."));
        props.put("AdditionalProperties", Map.of("type", "string",
                "description", "Optional extra semicolon-separated property names; same parsing rules as CriticalProperties."));
        props.put("LookupProperties", Map.of("type", "object",
                "description", "JSON object: propertyName → value. A Thing matches if **any** entry matches the live property value (**OR**). "
                        + "Exact match only (no wildcards/regex yet). Omit or {} to skip lookup filtering."));
        addHierarchyNodeIdArgument(props);
        addHierarchyNodeNameArgument(props);
        addIntersectToolArguments(props, true);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[] {"EntityType", "EntityName"});

        return new ToolDefinition("query_entities_by_taxonomy",
                "List Things under one ThingTemplate or ThingShape with taxonomy column projection. "
                        + "Use **resolve_asset_type** first for asset-type text — copy **entityType**/**entityName** (do not invent parents). "
                        + "Join resolver **criticalProperties** into **CriticalProperties** (semicolon-separated) when projecting names/columns. "
                        + "QIT listing + LookupProperties OR-filter; success per AGENT-TAXONOMY.md §5.2.1 "
                        + "(EMPTY | INLINE | LARGE; LARGE adds cacheId + sample). "
                        + "Success JSON uses **totalCount** (filtered count), not **totalRows**. "
                        + "Optional **hierarchyNodeName**, **intersectThingNames** / **intersectExpandHasMore** (same as query_entities). "
                        + "LARGE uses session cache like fetch_cached_result.",
                schema, true);
    }

    private static ToolDefinition listAssetTypesDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("maxItems", Map.of("type", "integer",
                "description", "Optional page size (default 500, hard cap 500). Omitted maxItems still uses the default "
                        + "cap — the full catalog is never dumped inline. When truncated, success includes "
                        + "hasMore=true, resultKind=ASSET_TYPES_LARGE, and totalCount of the full catalog."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        return new ToolDefinition("list_asset_types",
                "List application asset types from `/taxonomies/asset-types.json` when that v3 object map is configured and loaded (v3 identity array may be absent). "
                        + "For v2 object identity-types.json (version 2) this lists flattened rows from that file. "
                        + "Use when the user asks how many asset types exist or wants the catalog without guessing ThingTemplates. "
                        + "Always bounded: default **maxItems=500** (hard cap 500) even when the argument is omitted. "
                        + "Truncated catalogs return **ASSET_TYPES_LARGE** with **hasMore**; **totalCount** remains the "
                        + "full catalog size. Success echoes **maxItemsRequested** / **maxItemsEffective**.",
                schema, true);
    }

    private static ToolDefinition resolveAssetTypeDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("text", Map.of("type", "string",
                "description", "User-facing asset type phrase to resolve to a configured asset type key and ThingWorx parent."));
        Map<String, Object> schema = Map.of("type", "object", "properties", props, "required", new String[] {"text"});
        return new ToolDefinition("resolve_asset_type",
                "Map user asset type text to an asset type key, ThingTemplate/ThingShape parent, and **criticalProperties** name list. "
                        + "Call **resolve_thing** when the user names a specific asset instance.",
                schema, true);
    }

    private static ToolDefinition resolveThingDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("text", Map.of("type", "string",
                "description", "User-facing identifier (display name, serial, suffix, or canonical Thing name)."));
        props.put("assetTypeKey", Map.of("type", "string",
                "description",
                "Optional exact **key** from **list_asset_types** / asset-types.json to narrow identity rules to that asset class."));
        Map<String, Object> schema = Map.of("type", "object", "properties", props, "required", new String[] {"text"});
        return new ToolDefinition("resolve_thing",
                "Resolve user text to a unique canonical Thing name using v3 identity-types.json array rules. "
                        + "Requires at least one loaded v3 identity rule (asset-types.json is optional unless you pass **assetTypeKey**). "
                        + "Optional **assetTypeKey** narrows matching rules when asset-type rows are loaded.",
                schema, true);
    }

    // ── list_entities_by_type (EntityServices GetEntityList*) ───────────
    private static ToolDefinition listEntitiesByTypeDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("entityCollectionType", Map.of("type", "string",
                "description", "ThingWorx collection type string (e.g. ThingTemplate, ThingShape, Mashup, DataShape, User). "
                        + "NOT Thing — use query_entities or spotlight_search."));
        props.put("nameMask", Map.of("type", "string",
                "description", "Optional name pattern. Semantics depend on useRegEx: SQL LIKE vs regex."));
        props.put("useRegEx", Map.of("type", "boolean",
                "description", "If true, calls GetEntityListByRegEx; if false, GetEntityList (SQL LIKE). Default false."));
        props.put("maxItems", Map.of("type", "integer",
                "description", "Max rows (default 50, max 200). Platform may stop earlier when enough matches."));
        Map<String, Object> tagItem = new LinkedHashMap<>();
        tagItem.put("type", "object");
        tagItem.put("properties", Map.of(
                "vocabulary", Map.of("type", "string"),
                "vocabularyTerm", Map.of("type", "string")));
        tagItem.put("required", new String[]{"vocabulary", "vocabularyTerm"});
        Map<String, Object> tagsArr = new LinkedHashMap<>();
        tagsArr.put("type", "array");
        tagsArr.put("items", tagItem);
        tagsArr.put("description",
                "Optional Model tags filter (AND). Same as invoke_service TAGS: [{\"vocabulary\":\"...\",\"vocabularyTerm\":\"...\"}]. Omit = no tag filter.");
        props.put("tags", tagsArr);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"entityCollectionType"});
        return new ToolDefinition("list_entities_by_type",
                "List metadata entities of a given collection type via Resource EntityServices (GetEntityList or GetEntityListByRegEx). "
                        + "Never pass **`entityCollectionType=Stream`**, **`DataTable`**, or **`ValueStream`** when the user asks how many/list **Things** — those are **`query_entities`** **thingTemplate**/ **thingShape** keys, not **`GetEntityList`** **`type`** strings (success would mislead toward zero). "
                        + "If this tool returns **`code=ENTITY_COLLECTION_TYPE_RESOLVED_AS_MODEL_KEY`**, **immediately** call **`query_entities`** again using **`repair.arguments`** **before** telling the user the count is zero. "
                        + "**Repair trigger (S1, documented):** when the platform rejects the collection type, the executor "
                        + "detects English failure text containing both **invalid** and **entity** and **type** (substring "
                        + "match on the platform message — not a structured fault code) and may emit the Phase 0.5 "
                        + "**repair** envelope via GenericThing.GetIncomingDependencies. Treat that as a retry signal, "
                        + "not as proof of an empty catalog. "
                        + "This is **not** a substitute for **list_asset_types** / **resolve_asset_type**: do not use name-mask listing here to answer “how many asset types” or to invent **AssetType** inventory when application taxonomy is available (see llm_tool_routing_guide.txt **Asset taxonomy**). "
                        + "For Thing instances under a template/shape use query_entities instead. "
                        + "Optional tags filter uses platform Model tags (empty = match all). "
                        + "Large results use the same session cache as invoke_service / fetch_cached_result.",
                schema, true);
    }

    // ── get_property_values (batch) ─────────────────────────────────────
    private static ToolDefinition getPropertyValuesDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("thingName", Map.of("type", "string", "description", LLM_THINGNAME_SCALAR_DESCRIPTION));
        Map<String, Object> names = new LinkedHashMap<>();
        names.put("type", "array");
        names.put("maxItems", 40);
        names.put("items", Map.of("type", "string"));
        names.put("description", "Property names to read (max 40)");
        props.put("propertyNames", names);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"thingName", "propertyNames"});

        return new ToolDefinition("get_property_values",
                "Read current values of multiple Thing properties in one call (batch, max 40). "
                        + "**thingName** must be a canonical ThingWorx name; non-canonical labels return "
                        + "**IDENTITY_RESOLUTION_REQUIRED**; **`recoveryHint`** (to **resolve_thing**) is included only when "
                        + "v3 identity rules are loaded for this agent turn. "
                        + "propertyNames must be exact ThingWorx property names; use discover_thing_members "
                        + "(facet=properties or facet=property) first when only a business label is known. "
                        + "Returns per-property ok/value or error; "
                        + "PROPERTY_METADATA_UNRESOLVED means the name was not resolved and no value was read.",
                schema, true);
    }

    // ── query_property_history (unified numeric + value-stream history) ─
    private static ToolDefinition queryPropertyHistoryDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("thingName", Map.of("type", "string", "description", LLM_THINGNAME_SCALAR_DESCRIPTION));
        props.put("propertyName", Map.of("type", "string",
                "description", "Property name. The server resolves the base type: NUMBER/INTEGER/LONG use the numeric "
                        + "trend path (optional aggregates + Parler auto chart when applicable); other logged types use "
                        + "value-stream QueryPropertyHistory with bounded compact evidence (no auto chart in 0.1.162)."));
        props.put("startTime", Map.of("type", "string",
                "description", "Start time (ISO-8601). Omit with endTime for platform default window. "
                        + "Alias: start."));
        props.put("endTime", Map.of("type", "string",
                "description", "End time (ISO-8601). Optional. Alias: end."));
        props.put("calendarPhrase", Map.of("type", "string",
                "description",
                "Optional: **today** / **yesterday** / **tomorrow** (single day, user's **user_timezone** IANA). "
                        + "Mutually exclusive with startTime/endTime and relativeDuration."));
        props.put("relativeDuration", Map.of("type", "string",
                "description",
                "Optional: duration ending **now** (e.g. **30m**, **24h**) — closed-open semantics per "
                        + "`docs/agent/time-interpretation.md`. Mutually exclusive with startTime/endTime and calendarPhrase."));
        props.put("maxItems", Map.of("type", "integer",
                "description", "Max rows to read from the platform history service (default 1000, cap 5000). "
                        + "Aliases (still accepted): maxRows, maxPoints — when several are present, precedence is "
                        + "maxItems > maxRows > maxPoints. Success extras echo maxItemsRequested / maxItemsEffective. "
                        + "LLM-visible success bodies are always compact (sampleRows + cacheId; aggregates when requested)."));
        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("type", "array");
        actions.put("items", Map.of("type", "string"));
        actions.put("description",
                "Optional aggregate actions for **numeric** properties only (mean, min, max, sum, stddev, variance, median, "
                        + "count, first, last). Empty or omit for raw series. **Non-numeric:** omit actions — non-empty "
                        + "actions return NUMERIC_ACTIONS_UNSUPPORTED_FOR_PROPERTY_TYPE.");
        props.put("actions", actions);

        Map<String, Object> refY = Map.of("type", "number");
        Map<String, Object> refLabel = Map.of("type", "string");
        Map<String, Object> refRole = Map.of(
                "type", "string",
                "description",
                "usl/lsl = spec limits; ucl/lcl = control limits; target = center line; limit = generic; warning = advisory.",
                "enum", java.util.List.of("usl", "ucl", "lcl", "lsl", "target", "limit", "warning"));
        Map<String, Object> refItemProps = new LinkedHashMap<>();
        refItemProps.put("y", refY);
        refItemProps.put("label", refLabel);
        refItemProps.put("role", refRole);
        Map<String, Object> refItem = new LinkedHashMap<>();
        refItem.put("type", "object");
        refItem.put("properties", refItemProps);
        refItem.put("required", new String[]{"y"});
        Map<String, Object> yRefLines = new LinkedHashMap<>();
        yRefLines.put("type", "array");
        yRefLines.put("maxItems", 12);
        yRefLines.put("description",
                "Optional horizontal Y-axis lines (SPC limits, thresholds). Drawn on auto chart wire frame (numeric path).");
        yRefLines.put("items", refItem);
        props.put("y_reference_lines", yRefLines);

        props.put("kind", Map.of(
                "type", "string",
                "enum", java.util.List.of("line", "bar", "scatter"),
                "description", "Chart kind for automatic chart frame on numeric trends (default line)."));
        props.put("title", Map.of("type", "string", "description", "Optional chart title override (numeric path)."));
        props.put("x_label", Map.of("type", "string", "description", "Optional X-axis label (numeric path)."));
        props.put("y_label", Map.of("type", "string", "description", "Optional Y-axis label (numeric path)."));
        Map<String, Object> reqTrProps = new LinkedHashMap<>();
        reqTrProps.put("start", Map.of("type", "string",
                "description", "ISO-8601 instant (UTC …Z recommended). Chart X-axis span when query bounds omitted."));
        reqTrProps.put("end", Map.of("type", "string", "description", "ISO-8601 instant (UTC …Z recommended)."));
        Map<String, Object> reqTr = new LinkedHashMap<>();
        reqTr.put("type", "object");
        reqTr.put("properties", reqTrProps);
        reqTr.put("description",
                "Optional chart window for the auto ChartBlock when startTime/endTime are not passed (numeric path). "
                        + "Prefer always setting startTime and endTime so the query and chart share the same bounds.");
        props.put("requestedTimeRange", reqTr);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"thingName", "propertyName"});

        return new ToolDefinition("query_property_history",
                "Query time-series history for a Thing property. **thingName** must be canonical "
                        + "(else **IDENTITY_RESOLUTION_REQUIRED**; **recoveryHint** to **resolve_thing** when v3 rules loaded). "
                        + "Server picks numeric vs value-stream path from property metadata. "
                        + "Prefer ISO **startTime**/**endTime** (UTC …Z) so chart and query bounds align; or **calendarPhrase** / "
                        + "**relativeDuration** when ISO omitted. Numeric: optional **actions** + auto chart wire. "
                        + "Non-numeric: compact **VALUE_STREAM_HISTORY_INLINE** (sampleRows + **cacheId**, no points array). "
                        + "Value-stream uses **oldestFirst=true**; **sampleRows** are first N ascending rows (**historyOrder=oldest_first**).",
                schema, true);
    }

    /** Stream Thing {@code QueryStreamData} — natural-time + ISO bounds. */
    private static ToolDefinition queryStreamDataDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("thingName", Map.of("type", "string", "description", LLM_THINGNAME_SCALAR_DESCRIPTION
                + " Must name a **Stream** Thing (implements Stream / RemoteStream QueryStreamData)."));
        props.put("startTime", Map.of("type", "string",
                "description", "Start time (ISO-8601). Omit both bounds for platform-default window. Alias: start."));
        props.put("endTime", Map.of("type", "string",
                "description", "End time (ISO-8601). Alias: end."));
        props.put("calendarPhrase", Map.of("type", "string",
                "description",
                "Optional: **today** / **yesterday** / **tomorrow** (user **user_timezone** IANA). "
                        + "Mutually exclusive with startTime/endTime and relativeDuration."));
        props.put("relativeDuration", Map.of("type", "string",
                "description",
                "Optional: duration ending **now** (e.g. **30m**, **24h**). Mutually exclusive with ISO bounds and calendarPhrase."));
        props.put("maxItems", Map.of("type", "integer",
                "description", "Cap rows returned (default 500, max 5000). Maps to QueryStreamData maxItems."));
        props.put("oldestFirst", Map.of("type", "boolean",
                "description", "Sort oldest-first when true (default false)."));
        props.put("source", Map.of("type", "string",
                "description", "Optional stream entry source filter (QueryStreamData **source** parameter)."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"thingName"});
        return new ToolDefinition("query_stream_data",
                "Query tabular stream rows via platform **QueryStreamData** on a Stream Thing. "
                        + "**thingName** uses the same canonical Thing preflight as other scalar Thing tools "
                        + "(non-canonical → **IDENTITY_RESOLUTION_REQUIRED** with optional **recoveryHint**). "
                        + "Prefer this over invoke_service for natural-language time windows: **calendarPhrase**, **relativeDuration**, "
                        + "or ISO **startTime**/**endTime** (aliases start/end) — same resolver as query_property_history. "
                        + "Large results use the INFOTABLE_LARGE envelope (sampleRows; cacheId when server cached).",
                schema, true);
    }

    private static ToolDefinition queryAlertSummaryDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> thingNames = new LinkedHashMap<>();
        thingNames.put("type", "array");
        thingNames.put("minItems", 1);
        thingNames.put("maxItems", AlertSummaryMultiRollup.MAX_THING_NAMES_PER_CALL);
        thingNames.put("items", Map.of("type", "string", "description", LLM_THINGNAME_SCALAR_DESCRIPTION));
        thingNames.put("description",
                "One or more canonical ThingWorx names. Pass a single-element array for one Thing. Non-canonical "
                        + "names are reported per-Thing in identityErrors[] (partial success when at least one resolves). "
                        + "Maximum " + AlertSummaryMultiRollup.MAX_THING_NAMES_PER_CALL + " names per call.");
        props.put("thingNames", thingNames);
        props.put("ackState", Map.of("type", "string",
                "enum", java.util.List.of("all", "acknowledged", "unacknowledged"),
                "description", "Default all. Use unacknowledged for active-only."));
        props.put("propertyName", Map.of("type", "string", "description", "Optional: filter to one source property."));
        props.put("alertName", Map.of("type", "string", "description", "Optional QUERY EQ on alert name."));
        props.put("alertType", Map.of("type", "string", "description", "Optional QUERY EQ on alertType."));
        props.put("priorityMin", Map.of("type", "integer"));
        props.put("priorityMax", Map.of("type", "integer"));
        props.put("sort", Map.of("type", "string",
                "enum", java.util.List.of("default", "timestamp_asc", "timestamp_desc", "priority_asc", "priority_desc"),
                "description",
                "Optional QUERY sort for summary rows. Mutually exclusive with **advancedQuery.sorts**."));
        props.put("limit", Map.of("type", "integer", "description", "maxItems (default 100, max 500)."));
        props.put("advancedQuery", Map.of("type", "string",
                "description", "Optional raw ThingWorx-shaped Query dialect JSON (query-spec subset) merged AND with "
                        + "typed filters. See docs/agent/query_capability.md."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"thingNames"});
        return new ToolDefinition("query_alert_summary",
                "Current alert **summary** for one or more Things via Resource AlertFunctions.QueryAlertSummaryForThing "
                        + "(in-memory snapshot, not history). **thingNames** must be canonical ThingWorx names; "
                        + "non-canonical labels return per-Thing **IDENTITY_RESOLUTION_REQUIRED** entries when other "
                        + "Things succeed. Multiple Things return **ALERT_SUMMARY_MULTI** rollups (counts + topAlerts); "
                        + "a single Thing returns the usual INFOTABLE envelope. **`recoveryHint`** (to **resolve_thing**) "
                        + "is included only when v3 identity rules are loaded for this agent turn. "
                        + "Optional **sort** maps to QUERY **sorts** (not combinable with **advancedQuery.sorts**). "
                        + "Large single-Thing tables use INFOTABLE_LARGE (sampleRows; cacheId when server cached). "
                        + "Prefer over invoke_service for correct parameter mapping.",
                schema, true);
    }

    private static ToolDefinition queryAlertHistoryDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("thingName", Map.of("type", "string", "description", LLM_THINGNAME_SCALAR_DESCRIPTION));
        props.put("startTime", Map.of("type", "string", "description", "ISO-8601 start; optional (default window applied)."));
        props.put("endTime", Map.of("type", "string", "description", "ISO-8601 end; optional (defaults to now)."));
        props.put("calendarPhrase", Map.of("type", "string",
                "description",
                "Optional English phrase naming **one** local calendar day: **today**, **yesterday**, or **tomorrow** "
                        + "(word-boundary match). Uses host **user_timezone** (IANA). Mutually exclusive with "
                        + "startTime/endTime and relativeDuration."));
        props.put("relativeDuration", Map.of("type", "string",
                "description",
                "Optional Parler duration ending **now** (e.g. **30m**, **24h**, **7d**) — closed-open window per "
                        + "`docs/agent/time-interpretation.md` §4.4. Mutually exclusive with startTime/endTime and "
                        + "calendarPhrase."));
        // B8: timePreset / oldestFirst unpublished from model-visible schema; executor retains replay aliases.
        props.put("alertName", Map.of("type", "string"));
        props.put("propertyName", Map.of("type", "string", "description", "QUERY EQ on sourceProperty."));
        props.put("alertType", Map.of("type", "string"));
        props.put("priorityMin", Map.of("type", "integer"));
        props.put("priorityMax", Map.of("type", "integer"));
        props.put("order", Map.of("type", "string",
                "enum", java.util.List.of("newest_first", "oldest_first"),
                "description",
                "Optional sort direction (newest events first vs oldest first). Prefer this over legacy replay-only "
                        + "boolean sort aliases."));
        props.put("limit", Map.of("type", "integer", "description", "maxItems (default 100, max 500)."));
        props.put("advancedQuery", Map.of("type", "string",
                "description", "Optional raw ThingWorx-shaped Query dialect JSON (query-spec subset) merged AND with "
                        + "typed filters. See docs/agent/query_capability.md."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"thingName"});
        return new ToolDefinition("query_alert_history",
                "Alert **history** timeline for one Thing via AlertFunctions.QueryAlertHistory. **thingName** must be canonical; non-canonical labels "
                        + "return **IDENTITY_RESOLUTION_REQUIRED**; **`recoveryHint`** (to **resolve_thing**) is included only when "
                        + "v3 identity rules are loaded for this agent turn. Always bounded; response includes appliedStartTime/appliedEndTime, "
                        + "timeRangeSource, optional appliedTimePreset / implicitDefaultWindowDays, sort order, and **historyQueryResource** (resource-backed path; "
                        + "uses platform summary-manager filtered stream per installed server). Prefer **startTime**/**endTime** "
                        + "(ISO-8601), or **calendarPhrase** / **relativeDuration** when bounds are omitted; prefer **order** "
                        + "for sort direction.",
                schema, true);
    }

    private static ToolDefinition acknowledgeAlertsDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("thingName", Map.of("type", "string", "description", LLM_THINGNAME_SCALAR_DESCRIPTION));
        props.put("mode", Map.of("type", "string",
                "enum", java.util.List.of("specific_alerts", "property_all"),
                "description", "Default specific_alerts: summary probe then AcknowledgeAlertFromSummary; when exactly one unacked row matches propertyName without alertName, may use narrow AcknowledgeAlert (same property scope only)."));
        props.put("propertyName", Map.of("type", "string", "description", "Required for specific_alerts and property_all."));
        props.put("alertName", Map.of("type", "string", "description", "Optional: narrow specific_alerts to one alert name."));
        props.put("message", Map.of("type", "string", "description", "Optional ack message."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"thingName"});
        return new ToolDefinition("acknowledge_alerts",
                "Acknowledge alerts on AlertFunctions. **thingName** must be a canonical ThingWorx name; non-canonical labels return **IDENTITY_RESOLUTION_REQUIRED** before any acknowledge or summary probe (**no** side effects on preflight failure). Default **specific_alerts** requires propertyName; **property_all** is explicit bulk on that property. "
                        + "Success JSON may include **platformAckService**, **countsAvailable** (boolean), **ackMessage** (echo of optional **message** argument), and **note**; "
                        + "**specific_alerts** summary probe (**QueryAlertSummaryForThing**) and ack service failures return **status** error with normalized **code** (e.g. PLATFORM_ALERT_PERMISSION). "
                        + "empty **specific_alerts** uses **message** for the human-readable status line.",
                schema, true);
    }

    // ── set_property_value ──────────────────────────────────────────────
    private static ToolDefinition setPropertyValueDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("thing_name", Map.of("type", "string", "description", "Target Thing name"));
        props.put("property_name", Map.of("type", "string", "description", "Property name"));
        props.put("base_type", Map.of("type", "string",
                "description", "ThingWorx BaseType enum (STRING, NUMBER, INTEGER, LONG, BOOLEAN, DATETIME, …). "
                        + "Omit only if unsure — server may infer from property metadata."));
        Map<String, Object> valueProp = new LinkedHashMap<>();
        valueProp.put("anyOf", Arrays.asList(
                Map.of("type", "string"),
                Map.of("type", "number"),
                Map.of("type", "integer"),
                Map.of("type", "boolean")));
        valueProp.put("description",
                "Literal matching base_type (STRING→string, NUMBER→number, BOOLEAN→boolean, DATETIME→ISO-8601 string).");
        props.put("value", valueProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"thing_name", "property_name", "value"});

        return new ToolDefinition("set_property_value",
                "Request to write a Thing property. Requires human approval on Parler AlwaysOn before execution. "
                        + "Always set base_type when known (discover_thing_members / get_property_values).",
                schema);
    }

    /**
     * Fallback when {@link com.thingworx.things.agent.AgentThing#executeToolCall} reaches the registry for
     * {@code set_property_value} (should be rare: non-Parler paths return before {@code executeTool}). Does not write.
     */
    private static String executeSetPropertyValue(com.thingworx.things.agent.llm.ToolCall call) throws Exception {
        LOG.debug("set_property_value: registry fallback (non-Parler / unexpected dispatch)");
        return SetPropertyValueExecutor.blockedOutsideParlerContextJson();
    }

    // ── spotlight_search (SearchFunctions.SpotlightSearchV2, in-process) ─
    private static ToolDefinition spotlightSearchDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", Map.of("type", "string",
                "description", "Spotlight search text (names, descriptions, etc.)"));
        props.put("maxItems", Map.of("type", "integer",
                "description", "Max hits (default 30, max 100)"));
        // E17: entityTypes unpublished — accepted-but-ignored field must not stay model-visible.

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"query"});

        return new ToolDefinition("spotlight_search",
                "Fuzzy search across ThingWorx metadata via platform SearchFunctions.SpotlightSearchV2 (same as REST Spotlight; "
                        + "invoked in-JVM, not HTTP). For structured filters use query_entities. "
                        + "Type filters via entityTypes are **not supported** in this release — omit them.",
                schema, true);
    }

    /**
     * Verifies every built-in tool parameters map satisfies {@link LlmJsonSchemaCompat} (strict Azure / OpenAI
     * function JSON Schema). Used by offline JUnit so regressions surface before a live provider call.
     */
    public static void assertAllRegisteredToolSchemasPassLlmCompatCheck() {
        ToolRegistry reg = new ToolRegistry();
        registerAll(reg, false, false);
        for (ToolDefinition d : reg.getAllDefinitions()) {
            LlmJsonSchemaCompat.assertCompatible(d.getName(), d.getParametersSchema());
        }
        ToolRegistry regWithDocumentKnowledge = new ToolRegistry();
        registerAll(regWithDocumentKnowledge, false, true);
        for (ToolDefinition d : regWithDocumentKnowledge.getAllDefinitions()) {
            LlmJsonSchemaCompat.assertCompatible(d.getName(), d.getParametersSchema());
        }
        // Executor-only: not merged into the LLM tool list, but replay / persisted calls must keep valid JSON Schema.
        LlmJsonSchemaCompat.assertCompatible("get_entity", getEntityDef().getParametersSchema());
        LlmJsonSchemaCompat.assertCompatible("discover_properties", discoverPropertiesDef().getParametersSchema());
        LlmJsonSchemaCompat.assertCompatible("discover_services", discoverServicesDef().getParametersSchema());
        LlmJsonSchemaCompat.assertCompatible("get_service_definition", getServiceDefinitionDef().getParametersSchema());
    }
}
