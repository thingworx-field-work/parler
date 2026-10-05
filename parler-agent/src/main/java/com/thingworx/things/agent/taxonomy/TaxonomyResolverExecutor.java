package com.thingworx.things.agent.taxonomy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.logging.LogUtilities;
import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.PromptContextCacheSnapshot;
import com.thingworx.things.agent.configrepo.ConfigurationRepositoryPaths;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.cache.ArtifactCacheTurnFaults;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.TaxonomyIdentifierResolver;

/** LLM tool executors for semantic taxonomy resolver tools (v2 identity object + v3 array + asset-types map). */
public final class TaxonomyResolverExecutor {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(TaxonomyResolverExecutor.class);
    private static final ObjectMapper MAPPER = TaxonomyResolverJson.mapper();

    private TaxonomyResolverExecutor() {}

    public static String executeListAssetTypes(ToolCall call) {
        return executeListAssetTypes(call, AgentToolContext.getAgentThing());
    }

    public static String executeListAssetTypes(ToolCall call, AgentThing agent) {
        try {
            ApplicationSemanticTaxonomySnapshot sem = requireAvailable(agent);
            Integer maxItems = null;
            if (call != null && call.getArguments() != null && !call.getArguments().isBlank()) {
                JsonNode root = MAPPER.readTree(call.getArguments());
                if (root != null && root.has("maxItems") && root.get("maxItems").isNumber()) {
                    maxItems = root.get("maxItems").asInt();
                }
            }
            return listAssetTypesForSemantics(sem, maxItems);
        } catch (Exception e) {
            LOG.warn("list_asset_types: {}", e.getMessage(), e);
            return resolveFailed(agent, e);
        }
    }

    /**
     * Package-private for unit tests: same response as {@link #executeListAssetTypes(ToolCall, AgentThing)} after
     * {@link #requireAvailable(AgentThing)}, without a live {@link AgentThing}.
     */
    static String listAssetTypesForSemantics(ApplicationSemanticTaxonomySnapshot sem) throws Exception {
        return listAssetTypesForSemantics(sem, null);
    }

    /** Default / hard page size for {@code list_asset_types} (B12 — always bounded). */
    static final int LIST_ASSET_TYPES_DEFAULT_MAX_ITEMS = 500;

    /**
     * B12: bounds returned rows even when {@code maxItems} is omitted (default =
     * {@link #LIST_ASSET_TYPES_DEFAULT_MAX_ITEMS}); {@code totalCount} stays full-catalog size.
     */
    static String listAssetTypesForSemantics(ApplicationSemanticTaxonomySnapshot sem, Integer maxItems) throws Exception {
        if (sem == null || !sem.isResolverAvailable()) {
            return TaxonomyResolverJson.unavailable();
        }
        if (sem.assetTypeCount() == 0) {
            return TaxonomyResolverJson.assetTypesNotConfigured(sem.isStale());
        }
        int total = sem.assetTypeCount();
        int requested = maxItems == null ? LIST_ASSET_TYPES_DEFAULT_MAX_ITEMS : maxItems;
        int cap = Math.min(Math.max(1, requested), LIST_ASSET_TYPES_DEFAULT_MAX_ITEMS);
        int emit = Math.min(cap, total);
        ObjectNode o = MAPPER.createObjectNode();
        o.put("status", "success");
        o.put("resultKind", emit < total ? "ASSET_TYPES_LARGE" : "ASSET_TYPES_INLINE");
        o.put("stale", sem.isStale());
        o.put("totalCount", total);
        o.put("returnedRows", emit);
        o.put("hasMore", emit < total);
        o.put("maxItemsRequested", requested);
        o.put("maxItemsEffective", emit);
        ArrayNode types = MAPPER.createArrayNode();
        int i = 0;
        for (AssetTypeEntry e : sem.assetTypes()) {
            if (i >= emit) {
                break;
            }
            types.add(assetTypeSummary(e));
            i++;
        }
        o.set("assetTypes", types);
        String src = sem.effectiveSourcePath();
        o.put("sourcePath", src != null && !src.isEmpty() ? src : ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON);
        return MAPPER.writeValueAsString(o);
    }

    public static String executeResolveAssetType(ToolCall call) {
        return executeResolveAssetType(call, AgentToolContext.getAgentThing());
    }

    public static String executeResolveAssetType(ToolCall call, AgentThing agent) {
        try {
            ApplicationSemanticTaxonomySnapshot sem = requireAvailable(agent);
            return resolveAssetTypeForSemantics(call, sem);
        } catch (Exception e) {
            LOG.warn("resolve_asset_type: {}", e.getMessage(), e);
            return resolveFailed(agent, e);
        }
    }

    /** Package-private for unit tests (see {@link #listAssetTypesForSemantics(ApplicationSemanticTaxonomySnapshot)}). */
    static String resolveAssetTypeForSemantics(ToolCall call, ApplicationSemanticTaxonomySnapshot sem) throws Exception {
        if (sem == null || !sem.isResolverAvailable()) {
            return TaxonomyResolverJson.unavailable();
        }
        if (sem.assetTypeCount() == 0) {
            return TaxonomyResolverJson.assetTypesNotConfigured(sem.isStale());
        }
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
        String text = root.path("text").asText("").trim();
        AgentToolContext.setLastResolvedAssetTypeKey(null);
        if (text.isEmpty()) {
            // S11: missing required arg uses INVALID_PARAMETERS (not ASSET_TYPE_NOT_FOUND).
            return TaxonomyResolverJson.error("INVALID_PARAMETERS", "text is required",
                    TaxonomyResolverJson.staleField(sem.isStale()), null, null);
        }
        List<TaxonomyAssetTypeResolver.Match> matches = TaxonomyAssetTypeResolver.findMatches(text, sem.assetTypes());
        if (matches.isEmpty()) {
            return TaxonomyResolverJson.error("ASSET_TYPE_NOT_FOUND", "No asset type matched.",
                    TaxonomyResolverJson.staleField(sem.isStale()), null, null);
        }
        if (matches.size() > 1) {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", "ASSET_TYPE_AMBIGUOUS");
            o.put("stale", sem.isStale());
            o.put("message", "Multiple asset types matched.");
            ArrayNode cands = MAPPER.createArrayNode();
            for (TaxonomyAssetTypeResolver.Match m : matches) {
                cands.add(TaxonomyAssetTypeResolver.candidateNode(m, MAPPER));
            }
            o.set("candidates", cands);
            return MAPPER.writeValueAsString(o);
        }
        TaxonomyAssetTypeResolver.Match m = matches.get(0);
        ObjectNode o = MAPPER.createObjectNode();
        o.put("status", "success");
        o.put("resultKind", "ASSET_TYPE_RESOLVED");
        o.put("stale", sem.isStale());
        ObjectNode at = MAPPER.createObjectNode();
        at.put("entityKey", m.entry.entityKey());
        at.put("key", m.entry.key());
        at.put("entityType", m.entry.parentEntityType());
        at.put("entityName", m.entry.parentEntityName());
        putQueryParentIfPresent(at, m.entry);
        ArrayNode dp = MAPPER.createArrayNode();
        for (String p : m.entry.criticalProperties()) {
            dp.add(p);
        }
        at.set("criticalProperties", dp);
        o.set("assetType", at);
        ObjectNode mb = MAPPER.createObjectNode();
        mb.put("field", m.field);
        mb.put("rule", m.rule);
        mb.put("value", m.value);
        o.set("matchedBy", mb);
        AgentToolContext.setLastResolvedAssetTypeKey(m.entry.key());
        return MAPPER.writeValueAsString(o);
    }

    public static String executeResolveThing(ToolCall call) {
        return executeResolveThing(call, AgentToolContext.getAgentThing());
    }

    public static String executeResolveThing(ToolCall call, AgentThing agent) {
        try {
            ApplicationSemanticTaxonomySnapshot sem = requireAvailable(agent);
            return resolveThingForSemantics(call, sem);
        } catch (Exception e) {
            ArtifactCacheTurnFaults.rethrowRepositoryUnavailable(e);
            LOG.warn("resolve_thing: {}", e.getMessage(), e);
            return resolveFailed(agent, e);
        }
    }

    /** Package-private for unit tests (see {@link #listAssetTypesForSemantics(ApplicationSemanticTaxonomySnapshot)}). */
    static String resolveThingForSemantics(ToolCall call, ApplicationSemanticTaxonomySnapshot sem) throws Exception {
        if (sem == null || !sem.isResolverAvailable()) {
            return TaxonomyResolverJson.unavailable();
        }
        if (sem.thingIdentityRules().isEmpty()) {
            return TaxonomyResolverJson.error("TAXONOMY_UNAVAILABLE",
                    "resolve_thing requires v3 identity-types.json (root JSON array of rules).",
                    TaxonomyResolverJson.staleField(sem.isStale()), null, null);
        }
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
        String text = root.path("text").asText("").trim();
        String assetTypeKey = root.path("assetTypeKey").asText("").trim();
        if (text.isEmpty()) {
            return TaxonomyResolverJson.error("IDENTITY_NOT_FOUND", "text is required",
                    TaxonomyResolverJson.staleField(sem.isStale()), null, null);
        }
        AssetTypeEntry assetOpt = null;
        if (!assetTypeKey.isEmpty()) {
            if (sem.assetTypeCount() == 0) {
                return TaxonomyResolverJson.error("ASSET_TYPE_NOT_FOUND",
                        "No asset-type taxonomy is loaded; assetTypeKey cannot be used until asset-types.json defines types.",
                        TaxonomyResolverJson.staleField(sem.isStale()), null, null);
            }
            List<AssetTypeEntry> rows = sem.entriesWithAssetTypeKey(assetTypeKey);
            if (rows.isEmpty()) {
                return TaxonomyResolverJson.error("ASSET_TYPE_NOT_FOUND",
                        "assetTypeKey must match a key in asset-types.json.",
                        TaxonomyResolverJson.staleField(sem.isStale()), null, null);
            }
            if (rows.size() > 1) {
                return TaxonomyResolverJson.error("ASSET_TYPE_AMBIGUOUS",
                        "Multiple taxonomy rows share this assetTypeKey.",
                        TaxonomyResolverJson.staleField(sem.isStale()), null, null);
            }
            assetOpt = rows.get(0);
        }
        boolean anyTruncated = false;
        Integer totalUnderlying = null;
        List<ThingIdentityRuleV3> rules = sem.thingIdentityRules();
        for (int i = 0; i < rules.size(); i++) {
            ThingIdentityRuleV3 rule = rules.get(i);
            if (!ruleMatchesAssetFilter(rule, assetOpt)) {
                continue;
            }
            AssetTypeEntry syn = rule.toSyntheticAssetTypeEntry(i, mergeCriticalForRule(assetOpt, rule), assetOpt);
            String json = TaxonomyIdentifierResolver.resolve(syn, text, sem.isStale());
            JsonNode n = MAPPER.readTree(json);
            if (n.path("truncated").asBoolean(false)) {
                anyTruncated = true;
                if (n.has("totalUnderlyingCount") && !n.get("totalUnderlyingCount").isNull()) {
                    totalUnderlying = n.get("totalUnderlyingCount").asInt();
                }
            }
            String status = n.path("status").asText("");
            if ("error".equals(status)) {
                String code = n.path("code").asText("");
                if ("IDENTITY_NOT_FOUND".equals(code)) {
                    continue;
                }
                if ("IDENTITY_AMBIGUOUS".equals(code)) {
                    return MAPPER.writeValueAsString(withResolveThingAssetTypeKey((ObjectNode) n, assetTypeKey));
                }
                return json;
            }
            if ("success".equals(status)) {
                String rk = n.path("resultKind").asText("");
                if (rk.contains("LARGE")) {
                    return MAPPER.writeValueAsString(withResolveThingAssetTypeKey((ObjectNode) n, assetTypeKey));
                }
                JsonNode matches = n.path("matches");
                if (matches.isArray() && !matches.isEmpty()) {
                    return MAPPER.writeValueAsString(withResolveThingAssetTypeKey((ObjectNode) n, assetTypeKey));
                }
            }
        }
        String msg = anyTruncated
                ? "No Thing matched the given text in the scanned result prefix; scope may be truncated."
                : "No Thing matched the given text.";
        return TaxonomyResolverJson.error("IDENTITY_NOT_FOUND", msg, TaxonomyResolverJson.staleField(sem.isStale()),
                anyTruncated ? Boolean.TRUE : null, totalUnderlying);
    }

    /** Replaces synthetic {@code v3zip:} keys with the caller's {@code resolve_thing} {@code assetTypeKey} echo. */
    private static ObjectNode withResolveThingAssetTypeKey(ObjectNode n, String requestAssetTypeKey) {
        n.put("assetTypeKey", requestAssetTypeKey != null ? requestAssetTypeKey : "");
        return n;
    }

    private static List<String> mergeCriticalForRule(AssetTypeEntry assetOpt, ThingIdentityRuleV3 rule) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (assetOpt != null) {
            for (String p : assetOpt.criticalProperties()) {
                if (p != null && !p.isBlank()) {
                    names.add(p.trim());
                }
            }
        }
        for (String p : rule.criticalProperties()) {
            if (p != null && !p.isBlank()) {
                names.add(p.trim());
            }
        }
        return List.copyOf(names);
    }

    /**
     * Optional {@code assetTypeKey} filter before building a synthetic row. For {@code ThingShape} asset rows, the
     * intersection with the rule's {@code baseThingTemplate} is enforced on scanned Things after QIT (
     * {@code TaxonomyIdentifierResolver} shape filter on implementing rows); this gate is blank-field sanity only.
     */
    static boolean ruleMatchesAssetFilter(ThingIdentityRuleV3 rule, AssetTypeEntry assetOpt) {
        if (assetOpt == null) {
            return true;
        }
        if ("ThingTemplate".equals(assetOpt.parentEntityType())) {
            return rule.baseThingTemplate().equals(assetOpt.parentEntityName());
        }
        if ("ThingShape".equals(assetOpt.parentEntityType())) {
            String shape = assetOpt.parentEntityName();
            String tmpl = rule.baseThingTemplate();
            return shape != null && !shape.isBlank() && tmpl != null && !tmpl.isBlank();
        }
        return false;
    }

    private static String resolveFailed(AgentThing agent, Exception e) {
        Boolean staleField = null;
        if (agent != null) {
            PromptContextCacheSnapshot snap = agent.getPromptContextSnapshot();
            ApplicationSemanticTaxonomySnapshot sem =
                    snap != null ? snap.getApplicationSemanticTaxonomy() : null;
            if (sem != null && sem.isResolverAvailable()) {
                staleField = TaxonomyResolverJson.staleField(sem.isStale());
            }
        }
        String msg = e.getMessage() != null ? e.getMessage() : "resolve failed";
        return TaxonomyResolverJson.error("TAXONOMY_RESOLVE_FAILED", msg, staleField, null, null);
    }

    static ApplicationSemanticTaxonomySnapshot requireAvailable() {
        return requireAvailable(AgentToolContext.getAgentThing());
    }

    static ApplicationSemanticTaxonomySnapshot requireAvailable(AgentThing agent) {
        if (agent == null) {
            return null;
        }
        PromptContextCacheSnapshot snap = agent.getPromptContextSnapshot();
        if (snap == null) {
            return null;
        }
        ApplicationSemanticTaxonomySnapshot sem = snap.getApplicationSemanticTaxonomy();
        if (sem == null || !sem.isResolverAvailable()) {
            return null;
        }
        return sem;
    }

    private static ObjectNode assetTypeSummary(AssetTypeEntry e) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("entityKey", e.entityKey());
        o.put("key", e.key());
        ArrayNode aliases = MAPPER.createArrayNode();
        for (String a : e.aliases()) {
            aliases.add(a);
        }
        o.set("aliases", aliases);
        o.put("entityType", e.parentEntityType());
        o.put("entityName", e.parentEntityName());
        putQueryParentIfPresent(o, e);
        ArrayNode dp = MAPPER.createArrayNode();
        for (String p : e.criticalProperties()) {
            dp.add(p);
        }
        o.set("criticalProperties", dp);
        return o;
    }

    private static void putQueryParentIfPresent(ObjectNode target, AssetTypeEntry e) {
        if (e.queryParent() == null) {
            return;
        }
        ObjectNode qp = MAPPER.createObjectNode();
        qp.put("entityType", e.queryParent().entityType());
        qp.put("entityName", e.queryParent().entityName());
        if (!e.queryParent().role().isEmpty()) {
            qp.put("role", e.queryParent().role());
        }
        target.set("queryParent", qp);
    }
}
