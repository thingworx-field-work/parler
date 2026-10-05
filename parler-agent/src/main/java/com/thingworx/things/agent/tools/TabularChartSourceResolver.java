package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONObject;

import com.fasterxml.jackson.databind.JsonNode;
import com.thingworx.things.agent.ParlerTabularChartBuilder;
import com.thingworx.things.agent.ParlerTabularChartBuilder.SeriesSpec;
import com.thingworx.things.agent.cache.ArtifactCacheException;
import com.thingworx.things.agent.cache.ArtifactCacheFaultCode;
import com.thingworx.types.InfoTable;

/**
 * Resolves {@code source}/{@code cacheId} / {@code last_invoke} for {@code build_chart_from_tabular_result}
 * (Phase 1c structural extraction).
 */
public final class TabularChartSourceResolver {

    private TabularChartSourceResolver() {}

    /**
     * When {@code source} is {@code cache_id}, requires a non-blank {@code cacheId}. Used by callers that
     * short-circuit before {@link #resolveOrError} (e.g. phase-only chart intents) so argument shape stays
     * consistent with chart-emitting paths.
     *
     * @return error JSON body, or {@code null} if OK or source is not {@code cache_id}
     */
    public static String missingCacheIdForCacheSourceOrNull(JsonNode root) {
        String source = text(root, "source");
        if (source == null || source.isBlank()) {
            return null;
        }
        if (!"cache_id".equals(source.trim().toLowerCase(Locale.ROOT))) {
            return null;
        }
        String cacheId = text(root, "cacheId");
        if (cacheId == null || cacheId.isBlank()) {
            return errorJson("MISSING_CACHE_ID", "cacheId is required when source is cache_id.", null);
        }
        return null;
    }

    /** Resolved tabular source for chart building. */
    public static final class Resolved {
        public final InfoTable table;
        public final String sourceResolved;
        /** Non-null when {@code source} was {@code cache_id} and a cache id was supplied. */
        public final String cacheId;

        Resolved(InfoTable table, String sourceResolved, String cacheId) {
            this.table = table;
            this.sourceResolved = sourceResolved;
            this.cacheId = cacheId;
        }
    }

    /**
     * @param root tool arguments JSON
     * @return resolved table, or {@code null} with {@link #errorJson(String, String, JSONObject)} shape in errOut[0]
     */
    public static Resolved resolveOrError(JsonNode root, String[] errOut) {
        String source = text(root, "source");
        if (source == null || source.isBlank()) {
            errOut[0] = errorJson("MISSING_SOURCE", "source is required (last_invoke or cache_id).", null);
            return null;
        }
        source = source.trim().toLowerCase(Locale.ROOT);
        if (!"last_invoke".equals(source) && !"cache_id".equals(source)) {
            errOut[0] = errorJson("MISSING_SOURCE", "source must be last_invoke or cache_id.", null);
            return null;
        }

        if ("cache_id".equals(source)) {
            String cacheId = text(root, "cacheId");
            if (cacheId == null || cacheId.isBlank()) {
                errOut[0] = errorJson("MISSING_CACHE_ID", "cacheId is required when source is cache_id.", null);
                return null;
            }
            InfoTable table;
            try {
                table = InvokeServiceExecutor.lookupCachedInfotable(cacheId.trim());
            } catch (ArtifactCacheException e) {
                if (e.code() == ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE) {
                    throw e;
                }
                errOut[0] = errorJson(e.code().name(), e.getMessage(), null);
                return null;
            }
            if (table == null) {
                errOut[0] = errorJson("CACHE_MISS",
                        "No cached table for this cacheId in the current conversation. A cacheId only comes from a "
                        + "tabular result envelope that returned one; evidence ids, tool call ids and chart ids are not "
                        + "cache handles. Use only a handle a result actually returned. To chart the most recent "
                        + "qualifying table of this request, use source \"last_invoke\" instead. Do not retry the same id.", null);
                return null;
            }
            if (table.getRowCount() == 0) {
                JSONObject d = new JSONObject();
                d.put("reason", "cache_entry_empty");
                errOut[0] = errorJson("SOURCE_RESULT_NOT_TABULAR", "Cached table has no rows.", d);
                return null;
            }
            return new Resolved(table, "cache_id", cacheId.trim());
        }

        TabularChartRoundState st = AgentToolContext.tabularChartRoundState();
        int n = st.getQualifyingTabularSuccessCount();
        if (n <= 0) {
            JSONObject d = new JSONObject();
            d.put("reason", "no_qualifying_tabular_tool");
            errOut[0] = errorJson("SOURCE_RESULT_NOT_TABULAR",
                    "No qualifying tabular result is available for last_invoke in this turn. Call an existing tool "
                            + "that returns a single table (an INFOTABLE, or a JSON object whose root rows array is a "
                            + "complete small table), then build the chart, or present the values as a text table. Do "
                            + "not guess service names or call platform services to create an InfoTable.", d);
            return null;
        }
        String cid = st.getLastCacheId();
        if (cid != null && !cid.isEmpty()) {
            InfoTable table;
            try {
                table = InvokeServiceExecutor.lookupCachedInfotable(cid);
            } catch (ArtifactCacheException e) {
                if (e.code() == ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE) {
                    throw e;
                }
                errOut[0] = errorJson(e.code().name(), e.getMessage(), null);
                return null;
            }
            if (table == null) {
                errOut[0] = errorJson("CACHE_MISS", "Last tabular result cache is no longer available.", null);
                return null;
            }
            return new Resolved(table, "last_invoke", cid.trim());
        }
        if (st.getLastInlineRows() != null && st.getLastInlineRows().isArray()
                && st.getLastInlineRows().size() > 0) {
            try {
                InfoTable table = ParlerTabularChartBuilder.infoTableFromJsonRows(st.getLastInlineRows());
                return new Resolved(table, "last_invoke", null);
            } catch (ParlerTabularChartBuilder.BuildException e) {
                errOut[0] = errorJson(e.code, e.getMessage(), e.details);
                return null;
            }
        }
        errOut[0] = errorJson("SOURCE_RESULT_NOT_TABULAR",
                "The last qualifying tabular result has neither a cacheId nor inline rows to chart from. Call an "
                        + "existing tool that returns a single table (an INFOTABLE, or a JSON object whose root rows "
                        + "array is a complete small table), then build the chart, or present the values as a text "
                        + "table. Do not guess service names or call platform services to create an InfoTable.", null);
        return null;
    }

    static List<SeriesSpec> parseSeriesSpecs(JsonNode root) {
        List<SeriesSpec> multi = new ArrayList<>();
        JsonNode ser = root.get("series");
        if (ser != null && ser.isArray()) {
            for (JsonNode item : ser) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                String nm = text(item, "name");
                String yc = text(item, "yColumn");
                if (nm != null && yc != null) {
                    multi.add(new SeriesSpec(nm, yc));
                }
            }
        }
        return multi;
    }

    static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isTextual()) {
            String s = n.asText();
            return s != null && !s.isBlank() ? s.trim() : null;
        }
        return n.asText();
    }

    static String errorJson(String code, String message, JSONObject details) {
        JSONObject o = new JSONObject();
        o.put("status", "error");
        o.put("code", code);
        o.put("message", message != null ? message : "");
        if (details != null) {
            o.put("details", details);
        }
        return o.toString();
    }
}
