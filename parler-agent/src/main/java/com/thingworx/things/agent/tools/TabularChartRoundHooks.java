package com.thingworx.things.agent.tools;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;

import com.thingworx.logging.LogUtilities;
import com.thingworx.things.agent.ParlerTabularChartBuilder;
import com.thingworx.types.InfoTable;

/**
 * After tools return tabular JSON: updates {@link TabularChartRoundState} for {@code last_invoke}
 * on qualifying tabular tools, and updates the P2 conversation {@code TOKEN} mirror (including from
 * {@code summarize_cached_result} success {@code sourceCacheId} without touching round state).
 * <p>
 * {@code analyze_entity_set} success updates the conversation last-qualifying cache mirror only inside
 * {@link AnalyzeEntitySetExecutor} — this hook intentionally does <b>not</b> record it for
 * {@code build_chart_from_tabular_result(source=last_invoke)} so charts stay on the tabulate-first path.
 */
public final class TabularChartRoundHooks {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(TabularChartRoundHooks.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TabularChartRoundHooks() {}

    private static void registerPresentationArtifactFromTabulate(JsonNode root, QualifyingSnap snap) {
        try {
            String rk = text(root, "resultKind");
            if (rk == null) {
                rk = "";
            }
            int rowCount = root.path("totalRows").asInt(-1);
            if (rowCount < 0) {
                JsonNode rows = root.get("rows");
                rowCount = rows != null && rows.isArray() ? rows.size() : 0;
            }
            String cols = "[]";
            if (snap.columnsMeta != null && !snap.columnsMeta.isNull()) {
                cols = MAPPER.writeValueAsString(snap.columnsMeta);
            }
            PresentationArtifactRecord rec = new PresentationArtifactRecord(snap.cacheId, "tabulate_cached_result",
                    rk, true, rowCount, cols);
            PresentationArtifactRegistry.registerTabulateArtifact(FetchCachedReplayGuard.resolveCurrentTurnKey(), rec);
        } catch (Exception e) {
            LOG.debug("presentation artifact register skipped: {}", e.getMessage());
        }
    }

    /**
     * @return the tool result body to hand the model: unchanged, except that a qualifying JSON single table
     *         gains a real {@code cacheId} handle on the outer envelope (see {@link #withJsonSourceHandle}).
     */
    public static String afterBuiltInToolResult(String toolName, String jsonBody) {
        if (jsonBody == null || jsonBody.isEmpty()) {
            return jsonBody;
        }
        if ("build_chart_from_tabular_result".equals(toolName)) {
            return jsonBody;
        }
        if ("summarize_cached_result".equals(toolName)) {
            TabularCacheHandleMirror.noteFromSummarizeCachedResultJson(jsonBody);
            return jsonBody;
        }
        if ("analyze_entity_set".equals(toolName)) {
            // Mirror is updated by AnalyzeEntitySetExecutor on success; do not record per-turn qualifying
            // last_invoke state — raw entity-set caches must not be direct chart sources (ENTITY_SET_TOOL.md).
            return jsonBody;
        }
        try {
            JsonNode root = MAPPER.readTree(jsonBody);
            QualifyingSnap snap = parseQualifyingOrGeneric(toolName, root);
            if (snap == null) {
                return jsonBody;
            }
            TabularChartRoundState st = AgentToolContext.tabularChartRoundState();
            if (snap.promotedJsonRootTable) {
                if (!withinPromotionCharBand(jsonBody, root)) {
                    return jsonBody;
                }
                return processPromotedJsonRootTable(toolName, jsonBody, root, snap, st);
            }
            if (snap.clearsChartableTarget) {
                st.recordQualifyingEmptyClearingTarget(snap.columnsMeta);
            } else {
                st.recordQualifyingTabular(snap.hasCacheId, snap.cacheId, snap.inlineRows, snap.columnsMeta,
                        snap.chartRescueDataCompleteEnough);
            }
            if (snap.hasCacheId && snap.cacheId != null && !snap.cacheId.isEmpty()) {
                TabularCacheHandleMirror.recordQualifyingCacheId(snap.cacheId);
            } else if (snap.inlineRows != null && snap.inlineRows.isArray() && snap.inlineRows.size() > 0) {
                TabularCacheHandleMirror.clearInline();
            }
            if ("tabulate_cached_result".equals(toolName) && snap.hasCacheId && snap.cacheId != null
                    && !snap.cacheId.isEmpty() && snap.chartRescueDataCompleteEnough) {
                registerPresentationArtifactFromTabulate(root, snap);
            }
            LOG.debug("tabular chart round: tool={} qualifyingCount={} cacheId={} inlineRows={} completeEnough={}",
                    toolName, st.getQualifyingTabularSuccessCount(), snap.hasCacheId ? snap.cacheId : "none",
                    snap.inlineRows != null ? snap.inlineRows.size() : 0, snap.chartRescueDataCompleteEnough);
            return withJsonSourceHandle(jsonBody, root, snap);
        } catch (Exception e) {
            LOG.warn("tabular chart hook parse failed for {}: {}", toolName, e.getMessage());
            return jsonBody;
        }
    }

    /**
     * Adds the source handle without touching round state, for the approved-HITL path: the gated result is
     * persisted and replayed to the model outside normal dispatch, so it must be augmented once here — before
     * {@code HitlSyntheticToolResultAppender.appendDurable} and before the next model request — or the model
     * never receives a handle for a table it just approved. Round state is still recorded exactly once, later,
     * by {@link #afterBuiltInToolResult} inside the continuation loop; that second pass finds the handle
     * already present and does not store or count anything twice.
     *
     * @return the augmented body, or {@code jsonBody} unchanged when it is not a qualifying JSON table
     */
    public static String augmentJsonSourceHandle(String toolName, String jsonBody) {
        if (jsonBody == null || jsonBody.isEmpty()) {
            return jsonBody;
        }
        try {
            JsonNode root = MAPPER.readTree(jsonBody);
            QualifyingSnap snap = parseQualifyingOrGeneric(toolName, root);
            if (snap == null) {
                return jsonBody;
            }
            if (snap.promotedJsonRootTable) {
                if (root.hasNonNull("cacheId") || !withinPromotionCharBand(jsonBody, root)) {
                    return jsonBody;
                }
                return augmentPromotedJsonRootEnvelope(jsonBody, root, snap);
            }
            return withJsonSourceHandle(jsonBody, root, snap);
        } catch (Exception e) {
            LOG.debug("json chart source handle skipped for approved {}: {}", toolName, e.getMessage());
            return jsonBody;
        }
    }

    private static String processPromotedJsonRootTable(String toolName, String jsonBody, JsonNode root,
            QualifyingSnap snap, TabularChartRoundState st) {
        try {
            // A body that already carries a handle is the second pass over an approved-HITL result
            // (augmentJsonSourceHandle stored it). It registers that handle only while the cache still holds it
            // for this conversation and principal. A handle that expired, was invalidated or belongs to a
            // cleared scope is never re-created from the transcript rows: that would bring back data the cache
            // rules removed, under an id the model never saw. Nothing is registered and the prior target stays.
            String carried = text(root, "cacheId");
            boolean reused = carried != null;
            String cacheId = carried;
            if (reused) {
                if (!com.thingworx.things.agent.cache.ArtifactCacheLiveness.isIndexedForConversation(
                        AgentToolContext.getConversationId(), carried)) {
                    LOG.debug("tabular chart round: tool={} promotedJsonRoot handle {} no longer live; not registered",
                            toolName, carried);
                    return jsonBody;
                }
            } else {
                cacheId = buildAndStorePromotedTable(snap);
                if (cacheId == null) {
                    return jsonBody;
                }
            }
            // Cache-backed registration, like INFOTABLE_LARGE: last_invoke, cache_id and TOKEN all name the
            // same stored table. The inline-rows compatibility of the ≤20-row path is not extended here.
            st.recordQualifyingTabular(true, cacheId, null, null, snap.chartRescueDataCompleteEnough);
            TabularCacheHandleMirror.recordQualifyingCacheId(cacheId);
            if ("tabulate_cached_result".equals(toolName) && snap.chartRescueDataCompleteEnough) {
                registerPresentationArtifactFromTabulate(root,
                        new QualifyingSnap(true, cacheId, snap.inlineRows, snap.columnsMeta,
                                snap.chartRescueDataCompleteEnough));
            }
            LOG.debug("tabular chart round: tool={} promotedJsonRoot cacheId={} rows={} reusedHandle={}",
                    toolName, cacheId, snap.inlineRows != null ? snap.inlineRows.size() : 0, reused);
            if (reused) {
                return jsonBody;
            }
            ObjectNode augmented = ((ObjectNode) root).deepCopy();
            augmented.put("cacheId", cacheId);
            return MAPPER.writeValueAsString(augmented);
        } catch (Exception e) {
            LOG.debug("promoted json root table skipped: {}", e.getMessage());
            return jsonBody;
        }
    }

    /** Test seam: runs between qualification and table construction of a promotion. */
    static volatile Runnable promotionBuildObserverForTests;

    /**
     * The middle band only (design 7.2): a JSON result above the invoke character cap is never promoted. Built-in
     * {@code invoke_service} has already turned such a body into {@code LARGE_JSON}, but extended tools format
     * through the direct-service formatter, which has no such classifier, so the band is enforced here, at the
     * admission of the new path, for every caller. A handle this hook added earlier is not counted, so the second
     * pass over an approved body measures what the first pass measured.
     */
    private static boolean withinPromotionCharBand(String jsonBody, JsonNode root) {
        int chars = jsonBody.length();
        String carried = text(root, "cacheId");
        if (carried != null) {
            chars -= (",\"cacheId\":\"" + carried + "\"").length();
        }
        if (chars <= InvokeServiceExecutor.INVOKE_SERVICE_RESULT_CHAR_CAP) {
            return true;
        }
        LOG.debug("json root rows skipped for promotion: {} chars exceed the middle band", chars);
        return false;
    }

    /**
     * Builds and stores one promoted table under the budget that qualification started: the deadline of the
     * operation is checked before the table is built and again before it is copied, encoded and published.
     *
     * @return the new cacheId, or {@code null} when the deadline passed or the store returned no handle; nothing
     *         is cached or registered in that case
     */
    private static String buildAndStorePromotedTable(QualifyingSnap snap) throws Exception {
        Runnable observer = promotionBuildObserverForTests;
        if (observer != null) {
            observer.run();
        }
        TabularExpansionBudget budget = snap.promotionBudget;
        if (budget == null || budget.timeExceeded()) {
            LOG.debug("json root rows promotion abandoned before build: operation deadline passed");
            return null;
        }
        InfoTable table = ParlerTabularChartBuilder.infoTableFromJsonRows(snap.inlineRows);
        if (budget.timeExceeded()) {
            LOG.debug("json root rows promotion abandoned before store: operation deadline passed");
            return null;
        }
        String cacheId = InvokeServiceExecutor.storeInfotableInConversationCache(table);
        return cacheId == null || cacheId.isEmpty() ? null : cacheId;
    }

    private static String augmentPromotedJsonRootEnvelope(String original, JsonNode root, QualifyingSnap snap) {
        if (!(root instanceof ObjectNode) || snap.inlineRows == null) {
            return original;
        }
        try {
            String cacheId = buildAndStorePromotedTable(snap);
            if (cacheId == null) {
                return original;
            }
            ObjectNode augmented = ((ObjectNode) root).deepCopy();
            augmented.put("cacheId", cacheId);
            return MAPPER.writeValueAsString(augmented);
        } catch (Exception e) {
            LOG.debug("promoted json chart source handle skipped: {}", e.getMessage());
            return original;
        }
    }

    /**
     * Gives a qualifying {@code resultKind: JSON} single table its own cache handle so the model can chart it
     * later by {@code source: "cache_id"}, even after a newer table has taken over {@code last_invoke}.
     * Without this, two qualifying JSON results in one turn leave only the newer one addressable and the older
     * one has to be re-queried.
     * <p>
     * The handle is added to the <b>outer</b> tool-execution envelope only; the decoded business {@code result}
     * object keeps its exact fields and meaning. Round state is deliberately left on the inline path, so
     * {@code last_invoke} keeps resolving from rows and survives cache eviction.
     *
     * @return the augmented body, or {@code original} when this is not a qualifying JSON table or the store fails
     */
    private static String withJsonSourceHandle(String original, JsonNode root, QualifyingSnap snap) {
        if (snap.hasCacheId || snap.inlineRows == null || !"JSON".equals(text(root, "resultKind"))) {
            return original;
        }
        if (!(root instanceof ObjectNode) || root.hasNonNull("cacheId")) {
            return original;
        }
        try {
            InfoTable table = ParlerTabularChartBuilder.infoTableFromJsonRows(snap.inlineRows);
            String cacheId = InvokeServiceExecutor.storeInfotableInConversationCache(table);
            if (cacheId == null || cacheId.isEmpty()) {
                return original;
            }
            ObjectNode augmented = ((ObjectNode) root).deepCopy();
            augmented.put("cacheId", cacheId);
            return MAPPER.writeValueAsString(augmented);
        } catch (Exception e) {
            // A handle is an addition; never fail or reshape a tool result because caching it did not work.
            LOG.debug("json chart source handle skipped: {}", e.getMessage());
            return original;
        }
    }

    /** Tool-name dispatch first; extended tools and other invoke-shaped success envelopes fall through. */
    private static QualifyingSnap parseQualifyingOrGeneric(String toolName, JsonNode root) {
        QualifyingSnap snap = parseQualifying(toolName, root);
        return snap != null ? snap : tryGenericInvokeShapedInfotable(root);
    }

    private static final class QualifyingSnap {
        final boolean hasCacheId;
        final String cacheId;
        final JsonNode inlineRows;
        final JsonNode columnsMeta;
        final boolean chartRescueDataCompleteEnough;
        /** D1 EMPTY kinds: registration clears the chartable last_invoke target instead of keeping it. */
        final boolean clearsChartableTarget;
        /** TR-1: JSON root table above the inline row threshold; store before register. */
        final boolean promotedJsonRootTable;
        /** TR-1: the budget qualification started; its deadline also governs the build and the store. */
        final TabularExpansionBudget promotionBudget;

        QualifyingSnap(boolean hasCacheId, String cacheId, JsonNode inlineRows, JsonNode columnsMeta,
                boolean chartRescueDataCompleteEnough) {
            this(hasCacheId, cacheId, inlineRows, columnsMeta, chartRescueDataCompleteEnough, false, null);
        }

        private QualifyingSnap(boolean hasCacheId, String cacheId, JsonNode inlineRows, JsonNode columnsMeta,
                boolean chartRescueDataCompleteEnough, boolean clearsChartableTarget) {
            this(hasCacheId, cacheId, inlineRows, columnsMeta, chartRescueDataCompleteEnough, clearsChartableTarget,
                    null);
        }

        private QualifyingSnap(boolean hasCacheId, String cacheId, JsonNode inlineRows, JsonNode columnsMeta,
                boolean chartRescueDataCompleteEnough, boolean clearsChartableTarget,
                TabularExpansionBudget promotionBudget) {
            this.hasCacheId = hasCacheId;
            this.cacheId = cacheId;
            this.inlineRows = inlineRows;
            this.columnsMeta = columnsMeta;
            this.chartRescueDataCompleteEnough = chartRescueDataCompleteEnough;
            this.clearsChartableTarget = clearsChartableTarget;
            this.promotedJsonRootTable = promotionBudget != null;
            this.promotionBudget = promotionBudget;
        }

        static QualifyingSnap emptyClearingTarget(JsonNode columnsMeta) {
            return new QualifyingSnap(false, null, MAPPER.createArrayNode(), columnsMeta, false, true);
        }
    }

    /** Extended tools and any caller that emit the same success envelope as {@code invoke_service}. */
    private static QualifyingSnap tryGenericInvokeShapedInfotable(JsonNode root) {
        if (root == null || !root.path("status").asText("").equals("success")) {
            return null;
        }
        return parseInvokeService(root);
    }

    private static QualifyingSnap parseQualifying(String toolName, JsonNode root) {
        if (root == null || !root.path("status").asText("").equals("success")) {
            return null;
        }
        switch (toolName) {
            case "invoke_service":
            case "query_alert_summary":
            case "query_alert_history":
                return parseInvokeService(root);
            case "query_entities":
                return parseQueryEntities(root);
            case "query_entities_by_taxonomy":
                return parseQueryEntitiesByTaxonomy(root);
            case "list_entities_by_type":
                return parseListEntities(root);
            case "fetch_cached_result":
                return parseFetchCached(root);
            case "tabulate_cached_result":
                return parseTabulateCached(root);
            default:
                return null;
        }
    }

    private static QualifyingSnap parseInvokeService(JsonNode root) {
        String rk = text(root, "resultKind");
        if ("INFOTABLE_LARGE".equals(rk)) {
            String cid = text(root, "cacheId");
            if (cid != null && !cid.isEmpty()) {
                return new QualifyingSnap(true, cid, null, root.get("columns"), true);
            }
        }
        if ("INFOTABLE".equals(rk)) {
            JsonNode rows = root.get("rows");
            if (rows != null && rows.isArray() && rows.size() > 0) {
                return new QualifyingSnap(false, null, rows, root.get("columns"),
                        infotableJsonLooksCompleteForChartRescue(root));
            }
        }
        if ("JSON".equals(rk)) {
            JsonRootTableQualification qual = jsonRootTableQualification(root.get("result"));
            if (qual != null) {
                return new QualifyingSnap(false, null, qual.rows, null, true, false, qual.budget);
            }
        }
        return null;
    }

    /** TR-1: INLINE (≤20 rows) or PROMOTED (>20 rows with positive paging evidence and cell budget). */
    static final class JsonRootTableQualification {
        enum Tier {
            INLINE, PROMOTED
        }

        final Tier tier;
        final JsonNode rows;
        /** PROMOTED only: the budget charged during qualification, still running. */
        final TabularExpansionBudget budget;

        JsonRootTableQualification(Tier tier, JsonNode rows) {
            this(tier, rows, null);
        }

        JsonRootTableQualification(Tier tier, JsonNode rows, TabularExpansionBudget budget) {
            this.budget = budget;
            this.tier = tier;
            this.rows = rows;
        }
    }

    /**
     * Business JSON single table carried by a {@code resultKind: JSON} envelope. Returns {@code null} when the
     * decoded business object is not a qualifying root {@code rows} table. For ≤
     * {@link InvokeServiceExecutor#LARGE_TABLE_ROW_THRESHOLD} rows, behaviour matches the historical
     * {@link #jsonRootRowsForChart} path ({@link JsonRootTableQualification.Tier#INLINE}). Above the threshold,
     * {@link JsonRootTableQualification.Tier#PROMOTED} requires positive paging evidence and passes the cell budget.
     */
    static JsonRootTableQualification jsonRootTableQualification(JsonNode result) {
        JsonNode business = decodeBusinessObject(result);
        if (business == null) {
            return null;
        }
        JsonNode status = business.get("status");
        if (status != null && !(status.isTextual() && "success".equals(status.asText()))) {
            return null;
        }
        JsonNode rows = business.get("rows");
        if (rows == null || !rows.isArray() || rows.size() == 0) {
            return null;
        }
        for (JsonNode row : rows) {
            if (row == null || !row.isObject()) {
                return null;
            }
        }
        if (hasExplicitPartialResultSignal(business)) {
            return null;
        }
        int rowCount = rows.size();
        int threshold = InvokeServiceExecutor.LARGE_TABLE_ROW_THRESHOLD;
        if (rowCount <= threshold) {
            return new JsonRootTableQualification(JsonRootTableQualification.Tier.INLINE, rows);
        }
        if (!pagingFieldsWellTypedAndConsistent(business, rowCount)) {
            LOG.debug("json root rows skipped for promotion: rows={} paging fields mistyped or contradictory",
                    rowCount);
            return null;
        }
        if (!hasPositivePagingEvidence(business)) {
            LOG.debug("json root rows skipped for promotion: rows={} lack positive paging evidence", rowCount);
            return null;
        }
        TabularExpansionBudget budget = chargePromotionBudget(rows);
        if (budget == null) {
            LOG.debug("json root rows skipped for promotion: rows={} exceed cell, byte or time budget", rowCount);
            return null;
        }
        return new JsonRootTableQualification(JsonRootTableQualification.Tier.PROMOTED, rows, budget);
    }

    /**
     * Historical helper: returns root {@code rows} only for the INLINE tier (≤20 rows). PROMOTED tables and
     * non-qualifying payloads return {@code null}.
     */
    static JsonNode jsonRootRowsForChart(JsonNode result) {
        JsonRootTableQualification qual = jsonRootTableQualification(result);
        if (qual != null && qual.tier == JsonRootTableQualification.Tier.INLINE) {
            return qual.rows;
        }
        return null;
    }

    private static final String[] PAGING_BOOLEAN_FIELDS = {"hasMore", "truncated"};
    private static final String[] PAGING_NUMBER_FIELDS = {"totalRows", "returnedRows", "offset"};

    /**
     * Promotion only. Every paging field that is present must have its declared type, and every count that is
     * present must agree with the rows actually delivered. A well-typed positive field never outweighs a
     * mistyped or contradictory one. An explicit JSON {@code null} counts as absent.
     */
    private static boolean pagingFieldsWellTypedAndConsistent(JsonNode business, int rowCount) {
        for (String f : PAGING_BOOLEAN_FIELDS) {
            JsonNode n = business.get(f);
            if (n != null && !n.isNull() && !n.isBoolean()) {
                return false;
            }
        }
        for (String f : PAGING_NUMBER_FIELDS) {
            JsonNode n = business.get(f);
            if (n != null && !n.isNull() && !n.isNumber()) {
                return false;
            }
        }
        BigDecimal delivered = BigDecimal.valueOf(rowCount);
        for (String f : new String[] {"returnedRows", "totalRows"}) {
            JsonNode n = business.get(f);
            if (n != null && n.isNumber() && n.decimalValue().compareTo(delivered) != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Promotion only, after {@link #pagingFieldsWellTypedAndConsistent}. {@code returnedRows} equal to the
     * delivered row count is a consistency condition, never evidence: every page reports its own length.
     */
    private static boolean hasPositivePagingEvidence(JsonNode business) {
        if (Boolean.FALSE.equals(readOptionalBool(business, "hasMore"))
                || Boolean.FALSE.equals(readOptionalBool(business, "truncated"))) {
            return true;
        }
        JsonNode totalRows = business.get("totalRows");
        JsonNode returnedRows = business.get("returnedRows");
        return totalRows != null && totalRows.isNumber() && returnedRows != null && returnedRows.isNumber()
                && totalRows.decimalValue().compareTo(returnedRows.decimalValue()) == 0;
    }

    /**
     * Cells first (cheap), then the bytes the cache codec would write and the shared wall time, all before any
     * InfoTable is built. Columns come from row 0, exactly as {@code infoTableFromJsonRows} takes them.
     */
    static boolean withinPromotionExpansionBudget(JsonNode rows) {
        return chargePromotionBudget(rows) != null;
    }

    /** @return the charged, still running budget of this promotion, or {@code null} when a limit is exceeded */
    private static TabularExpansionBudget chargePromotionBudget(JsonNode rows) {
        int rowCount = rows.size();
        if (rowCount == 0) {
            return null;
        }
        JsonNode first = rows.get(0);
        if (first == null || !first.isObject()) {
            return null;
        }
        long cells = (long) rowCount * (long) first.size();
        if (cells > CachedTabularToolsExecutor.MAX_CELLS_FOR_TABULAR_TRANSFORM) {
            return null;
        }
        List<String> columns = new ArrayList<>();
        first.fieldNames().forEachRemaining(columns::add);
        TabularExpansionBudget budget = TabularExpansionBudget.start();
        return budget.chargeJsonRows(rows, columns) ? budget : null;
    }

    /** Accepts the business object directly or as a JSON string that parses to an object in one pass. */
    private static JsonNode decodeBusinessObject(JsonNode result) {
        if (result == null || result.isNull()) {
            return null;
        }
        if (result.isObject()) {
            return result;
        }
        if (result.isTextual()) {
            try {
                JsonNode parsed = MAPPER.readTree(result.asText());
                return parsed != null && parsed.isObject() ? parsed : null;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Only the paging fields named by the JSON return conventions, with their declared JSON types:
     * boolean {@code hasMore}/{@code truncated} true, numeric {@code offset} above zero, or numeric
     * {@code totalRows} and {@code returnedRows} that differ. Strings are never coerced. Numbers are
     * compared by magnitude ({@link JsonNode#decimalValue()}) so fractional or out-of-{@code long} values
     * are neither truncated nor wrapped, and equal magnitudes in different representations stay equal.
     */
    private static boolean hasExplicitPartialResultSignal(JsonNode business) {
        if (Boolean.TRUE.equals(readOptionalBool(business, "hasMore"))
                || Boolean.TRUE.equals(readOptionalBool(business, "truncated"))) {
            return true;
        }
        JsonNode offset = business.get("offset");
        if (offset != null && offset.isNumber() && offset.decimalValue().signum() > 0) {
            return true;
        }
        JsonNode totalRows = business.get("totalRows");
        JsonNode returnedRows = business.get("returnedRows");
        return totalRows != null && totalRows.isNumber() && returnedRows != null && returnedRows.isNumber()
                && totalRows.decimalValue().compareTo(returnedRows.decimalValue()) != 0;
    }

    /**
     * When {@code sampleOnly}/{@code rowsOmitted}/{@code totalRows} are absent, treat as a plain small INFOTABLE
     * (extended-tool rows). When present, require non-sample, non-omitted, and matching row counts.
     */
    static boolean infotableJsonLooksCompleteForChartRescue(JsonNode root) {
        if (root == null) {
            return false;
        }
        if (!root.has("sampleOnly") && !root.has("rowsOmitted") && !root.has("totalRows")) {
            return true;
        }
        Boolean sampleOnly = readOptionalBool(root, "sampleOnly");
        if (Boolean.TRUE.equals(sampleOnly)) {
            return false;
        }
        Boolean rowsOmitted = readOptionalBool(root, "rowsOmitted");
        if (Boolean.TRUE.equals(rowsOmitted)) {
            return false;
        }
        JsonNode totalRows = root.get("totalRows");
        JsonNode returnedRows = root.get("returnedRows");
        if (totalRows != null && totalRows.isNumber() && returnedRows != null && returnedRows.isNumber()) {
            return totalRows.asInt() == returnedRows.asInt();
        }
        return true;
    }

    private static Boolean readOptionalBool(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull() || !n.isBoolean()) {
            return null;
        }
        return n.asBoolean();
    }

    private static QualifyingSnap parseQueryEntities(JsonNode root) {
        String rk = text(root, "resultKind");
        if ("ENTITY_QUERY_LARGE".equals(rk)) {
            String cid = text(root, "cacheId");
            if (cid != null && !cid.isEmpty()) {
                return new QualifyingSnap(true, cid, null, null, true);
            }
        }
        if ("ENTITY_QUERY_INLINE".equals(rk)) {
            JsonNode rows = root.get("rows");
            if (rows != null && rows.isArray() && rows.size() > 0) {
                return new QualifyingSnap(false, null, rows, null, infotableJsonLooksCompleteForChartRescue(root));
            }
        }
        return null;
    }

    /** Same tabular round semantics as {@link #parseQueryEntities}; rows live under {@code rootEntityList}. */
    private static QualifyingSnap parseQueryEntitiesByTaxonomy(JsonNode root) {
        String rk = text(root, "resultKind");
        if ("ENTITY_TAXONOMY_QUERY_LARGE".equals(rk)) {
            String cid = text(root, "cacheId");
            if (cid != null && !cid.isEmpty()) {
                return new QualifyingSnap(true, cid, null, null, true);
            }
        }
        if ("ENTITY_TAXONOMY_QUERY_INLINE".equals(rk)) {
            JsonNode rows = root.get("rootEntityList");
            if (rows != null && rows.isArray() && rows.size() > 0) {
                return new QualifyingSnap(false, null, rows, null, infotableJsonLooksCompleteForChartRescue(root));
            }
        }
        return null;
    }

    private static QualifyingSnap parseListEntities(JsonNode root) {
        String rk = text(root, "resultKind");
        if ("ENTITY_LIST_LARGE".equals(rk)) {
            String cid = text(root, "cacheId");
            if (cid != null && !cid.isEmpty()) {
                return new QualifyingSnap(true, cid, null, null, true);
            }
        }
        if ("ENTITY_LIST_INLINE".equals(rk)) {
            JsonNode rows = root.get("rows");
            if (rows != null && rows.isArray() && rows.size() > 0) {
                return new QualifyingSnap(false, null, rows, null, infotableJsonLooksCompleteForChartRescue(root));
            }
        }
        return null;
    }

    private static QualifyingSnap parseFetchCached(JsonNode root) {
        String cid = text(root, "cacheId");
        JsonNode rows = root.get("rows");
        if (cid != null && !cid.isEmpty()
                && rows != null && rows.isArray() && rows.size() > 0) {
            return new QualifyingSnap(true, cid, null, null, true);
        }
        return null;
    }

    private static QualifyingSnap parseTabulateCached(JsonNode root) {
        if (root.has("unionMeta")) {
            return parseUnionRows(root);
        }
        boolean tabComplete = TabularCompleteAnswerSetDetector.tabulateSuccessJsonCompleteEnough(root);
        String rk = text(root, "resultKind");
        if ("CACHED_TABULATE_LARGE".equals(rk) || "CACHED_FILTER_ROWS_LARGE".equals(rk)
                || "CACHED_FILTER_SORT_TOPN_LARGE".equals(rk) || "CACHED_GROUP_METRIC_LARGE".equals(rk)) {
            String cid = text(root, "cacheId");
            if (cid != null && !cid.isEmpty()) {
                return new QualifyingSnap(true, cid, null, root.get("columns"), tabComplete);
            }
        }
        if ("CACHED_TABULATE_INLINE".equals(rk) || "CACHED_FILTER_ROWS_INLINE".equals(rk)
                || "CACHED_FILTER_SORT_TOPN_INLINE".equals(rk) || "CACHED_GROUP_METRIC_INLINE".equals(rk)
                || CachedTabularDistributionExecutor.RESULT_BIN_INLINE.equals(rk)
                || CachedTabularDistributionExecutor.RESULT_BOX_INLINE.equals(rk)) {
            String cid = text(root, "cacheId");
            if (cid != null && !cid.isEmpty()) {
                return new QualifyingSnap(true, cid, null, root.get("columns"), tabComplete);
            }
            JsonNode rows = root.get("rows");
            if (rows != null && rows.isArray() && rows.size() > 0) {
                return new QualifyingSnap(false, null, rows, root.get("columns"), tabComplete);
            }
        }
        if ("CACHED_TABULATE_EMPTY".equals(rk) || "CACHED_FILTER_ROWS_EMPTY".equals(rk)
                || "CACHED_FILTER_SORT_TOPN_EMPTY".equals(rk) || "CACHED_GROUP_METRIC_EMPTY".equals(rk)) {
            return new QualifyingSnap(false, null, MAPPER.createArrayNode(), root.get("columns"), false);
        }
        // D1 (design §7.4): an empty distribution result must not let last_invoke chart the previous table.
        // Only these two kinds clear the chartable target; the existing EMPTY semantics above are unchanged.
        if (CachedTabularDistributionExecutor.RESULT_BIN_EMPTY.equals(rk)
                || CachedTabularDistributionExecutor.RESULT_BOX_EMPTY.equals(rk)) {
            return QualifyingSnap.emptyClearingTarget(root.get("columns"));
        }
        return null;
    }

    /**
     * {@code union_rows}: every non-empty result carries a derived {@code cacheId} for the whole appended table,
     * so it registers cache-backed and as a presentation artifact whether the model saw all rows (INLINE) or a
     * sample (LARGE). Sample truncation towards the model and source completeness are separate facts and are
     * not touched. An empty union clears the chartable target so {@code last_invoke} cannot chart an older,
     * unrelated table.
     */
    private static QualifyingSnap parseUnionRows(JsonNode root) {
        String rk = text(root, "resultKind");
        if ("CACHED_TABULATE_EMPTY".equals(rk)) {
            return QualifyingSnap.emptyClearingTarget(root.get("columns"));
        }
        String cid = text(root, "cacheId");
        if (cid == null || cid.isEmpty()) {
            return null;
        }
        return new QualifyingSnap(true, cid, null, root.get("columns"), true);
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull() || !n.isTextual()) {
            return null;
        }
        String s = n.asText();
        return s == null || s.isBlank() ? null : s.trim();
    }
}
