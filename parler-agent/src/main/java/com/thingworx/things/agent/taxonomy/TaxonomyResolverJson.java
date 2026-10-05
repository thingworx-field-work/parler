package com.thingworx.things.agent.taxonomy;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.AgentThing;

/** JSON envelopes for taxonomy resolver tools and operator services. */
public final class TaxonomyResolverJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TaxonomyResolverJson() {}

    /**
     * @param stale {@code null} omit (no cache); {@code false}/{@code true} when cache exists (CONTRACTS §1.2)
     */
    public static String error(String code, String message, Boolean stale, Boolean truncated,
            Integer totalUnderlyingCount) {
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", code);
            o.put("message", message != null ? message : "");
            if (stale != null) {
                o.put("stale", stale);
            }
            putTruncation(o, truncated, totalUnderlyingCount);
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"TAXONOMY_RESOLVER_ERROR\",\"message\":\""
                    + (message != null ? message.replace("\"", "'") : "") + "\"}";
        }
    }

    public static String unavailable() {
        return error("TAXONOMY_UNAVAILABLE", "No valid repository asset taxonomy is loaded.", null, null, null);
    }

    /** Asset-type tools when the semantic taxonomy cache is loaded but no asset-type rows are configured. */
    public static String assetTypesNotConfigured(boolean stale) {
        return error("ASSET_TYPES_NOT_CONFIGURED",
                "No asset-type taxonomy is loaded (asset-types.json is absent, empty, or could not be read).",
                staleField(stale), null, null);
    }

    /** {@code stale} for resolver tools when semantic taxonomy cache is loaded. */
    public static Boolean staleField(boolean cacheStale) {
        return cacheStale ? Boolean.TRUE : Boolean.FALSE;
    }

    public static String diagnosticsJson(ApplicationSemanticTaxonomySnapshot sem, AgentThing agent, boolean refreshed) {
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", sem.isLoaded() ? "success" : "error");
            if (refreshed) {
                o.put("refreshed", true);
            }
            o.put("loaded", sem.isLoaded());
            o.put("stale", sem.isStale());
            String src = sem.effectiveSourcePath();
            if (src != null && !src.isEmpty()) {
                o.put("sourcePath", src);
            }
            o.put("assetTypeCount", sem.assetTypeCount());
            if (sem.lastSuccessfulRefresh() != null) {
                o.put("lastSuccessfulRefresh", sem.lastSuccessfulRefresh().toString());
            }
            if (sem.lastAttemptedRefresh() != null) {
                o.put("lastAttemptedRefresh", sem.lastAttemptedRefresh().toString());
            }
            o.set("diagnostics", diagnosticsArray(sem.diagnostics()));
            if (agent != null) {
                o.put("taxonomyPromptInjection", agent.getTaxonomyPromptInjectionEffective());
            }
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"TAXONOMY_DIAGNOSTICS_ERROR\",\"message\":\""
                    + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    public static ArrayNode diagnosticsArray(List<TaxonomyDiagnostic> diagnostics) {
        ArrayNode arr = MAPPER.createArrayNode();
        if (diagnostics == null) {
            return arr;
        }
        for (TaxonomyDiagnostic d : diagnostics) {
            ObjectNode item = MAPPER.createObjectNode();
            item.put("severity", d.severity() == TaxonomyDiagnostic.Severity.ERROR ? "error" : "warning");
            item.put("code", d.code());
            item.put("message", d.message());
            arr.add(item);
        }
        return arr;
    }

    public static void putTruncation(ObjectNode o, Boolean truncated, Integer totalUnderlyingCount) {
        if (!Boolean.TRUE.equals(truncated)) {
            return;
        }
        o.put("truncated", true);
        if (totalUnderlyingCount != null) {
            o.put("totalUnderlyingCount", totalUnderlyingCount);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
