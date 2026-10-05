package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.joda.time.DateTime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.logging.LogUtilities;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.ParlerProtectionAudit;
import com.thingworx.things.agent.cache.ArtifactCacheException;
import com.thingworx.things.agent.cache.ArtifactCacheFaultCode;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.IntegerPrimitive;
import com.thingworx.types.primitives.LongPrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;
import com.thingworx.types.data.filters.IFilter;
import com.thingworx.things.agent.tools.predicate.ParlerQueryFilterParser;
import com.thingworx.things.agent.tools.predicate.PredicateErrorMessages;

import org.slf4j.Logger;

/**
 * Built-in {@code tabulate_cached_result} and {@code summarize_cached_result} over conversation-scoped
 * cached {@link InfoTable} rows (same cache as {@link InvokeServiceExecutor} / {@code fetch_cached_result}).
 *
 * <p><b>Agent-side tool JSON (normative):</b> {@code CONTRACTS/TABULAR_INSIGHT.md} + {@code docs/agent/cached_tabular_tools.md}.
 * Root {@code arguments} parsing: {@link CachedTabularToolArgumentsJson}. Paging bounds ({@code limit}/{@code offset}):
 * {@link CachedTabularTabulatePaging}; {@code sort_topn} {@code direction}: {@link CachedTabularSortTopnDirection}.
 * Optional success {@code insightEnvelope}: {@code docs/agent/tabular_insight_envelope.md}. Open decisions:
 * {@code docs/agent/cached-table-decision-tools.md}.</p>
 */
public final class CachedTabularToolsExecutor {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(CachedTabularToolsExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Max source rows scanned per cached-table **decision** call ({@code filter_*}, {@code group_metric}). */
    static final int MAX_SCANNED_ROWS_DECISION = 100_000;
    /** Max sample rows embedded in decision-mode success JSON. */
    static final int DECISION_SAMPLE_ROWS_CAP = 20;
    /** Max input rows for any transform / summarize (inclusive). */
    static final int MAX_ROWS_FOR_TABULAR_TRANSFORM = 50_000;
    /** Approximate max cells (rows × columns) for transform / summarize input. */
    static final long MAX_CELLS_FOR_TABULAR_TRANSFORM = 2_000_000L;
    /** Max distinct groups before {@code CARDINALITY_TOO_HIGH}. */
    static final int MAX_GROUP_CARDINALITY = 5_000;
    /** Default / max output row limit for {@code sort_topn} (and cap for group modes when specified). */
    static final int TABULATE_DEFAULT_LIMIT = CachedTabularTabulatePaging.SORT_TOPN_DEFAULT_LIMIT;
    static final int TABULATE_MAX_LIMIT = CachedTabularTabulatePaging.MAX_LIMIT;
    /** When {@code percentileColumns} is omitted, only the first K numeric columns (declaration order) get p50/p95. */
    static final int MAX_NUMERIC_COLUMNS_FOR_PERCENTILES_DEFAULT = 8;

    /** P1 {@code insightEnvelope.schemaVersion} on tabulate/summarize success JSON (see {@code docs/agent/tabular_insight_envelope.md}). */
    static final String INSIGHT_ENVELOPE_SCHEMA_VERSION = "1";

    private CachedTabularToolsExecutor() {}

    public static String executeTabulateCachedResult(ToolCall call) {
        try {
            return doTabulate(call);
        } catch (ArtifactCacheException e) {
            return cacheFaultOrThrow(e);
        } catch (CachedTabularDecisionToolException e) {
            return errorJson(e.code, e.getMessage());
        } catch (IllegalArgumentException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("Distinct group count exceeds")) {
                return errorJson("CARDINALITY_TOO_HIGH", m);
            }
            if (m.contains("maxItems must") || m.contains("limit must") || m.contains("offset must")) {
                return errorJson("LIMIT_OUT_OF_RANGE", m);
            }
            if (m.contains("not sortable")) {
                return errorJson("UNSORTABLE_COLUMN", m);
            }
            if (m.contains("aggregateColumn type not supported")) {
                return errorJson("UNSORTABLE_COLUMN", m);
            }
            LOG.warn("tabulate_cached_result: {}", m);
            return errorJson("INVALID_PARAMETERS", m);
        } catch (JsonProcessingException e) {
            String detail = e.getOriginalMessage() != null ? e.getOriginalMessage() : e.getMessage();
            LOG.warn("tabulate_cached_result: invalid JSON arguments: {}", detail);
            return errorJson("INVALID_PARAMETERS", "Tool arguments must be valid JSON: " + detail);
        } catch (Exception e) {
            LOG.warn("tabulate_cached_result failed: {}", e.getMessage(), e);
            return errorJson("TABULATE_ERROR", e.getMessage());
        }
    }

    public static String executeSummarizeCachedResult(ToolCall call) {
        try {
            return doSummarize(call);
        } catch (ArtifactCacheException e) {
            return cacheFaultOrThrow(e);
        } catch (IllegalArgumentException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            LOG.warn("summarize_cached_result: {}", m);
            return errorJson("INVALID_PARAMETERS", m);
        } catch (JsonProcessingException e) {
            String detail = e.getOriginalMessage() != null ? e.getOriginalMessage() : e.getMessage();
            LOG.warn("summarize_cached_result: invalid JSON arguments: {}", detail);
            return errorJson("INVALID_PARAMETERS", "Tool arguments must be valid JSON: " + detail);
        } catch (Exception e) {
            LOG.warn("summarize_cached_result failed: {}", e.getMessage(), e);
            return errorJson("SUMMARIZE_ERROR", e.getMessage());
        }
    }

    private static String doTabulate(ToolCall call) throws Exception {
        JsonNode root = CachedTabularToolArgumentsJson.readRoot(call.getArguments());
        if (!root.isObject()) {
            return errorJson("INVALID_PARAMETERS", "Tool arguments must be a JSON object.");
        }
        String mode = text(root, "mode");
        if (mode == null || mode.isEmpty()) {
            return errorJson("MISSING_MODE",
                    "mode is required: filter_count | filter_rows | filter_sort_topn | group_metric "
                            + "| bin_numeric | box_summary | union_rows "
                            + "| exact_join | quality | resample | rolling | rate_of_change | period_compare "
                            + "| counter_delta | rolling_stats | time_weighted | calendar_bucket "
                            + "(replay aliases still accepted: sort_topn → filter_sort_topn; "
                            + "group_count / group_aggregate → group_metric).");
        }
        mode = mode.trim().toLowerCase(Locale.ROOT);
        if (TabulateCachedResultToolSchema.MODE_UNION_ROWS.equals(mode)) {
            return tabulateUnionRows(root);
        }
        String requestedCacheId = text(root, "cacheId");
        if (requestedCacheId == null || requestedCacheId.isEmpty()) {
            return errorJson("MISSING_CACHE_ID", "cacheId is required.");
        }
        // TQJ-5 Option A: governed U4 sub-operations — shared executor/runner paths (no forks).
        if (TabulateCachedResultToolSchema.MODE_EXACT_JOIN.equals(mode)) {
            return dispatchExactJoinMode(root, requestedCacheId);
        }
        if (TabulateCachedResultToolSchema.MODE_QUALITY.equals(mode)) {
            return dispatchU4SeriesMode(root, requestedCacheId, QualityCachedResultExecutor.TOOL_NAME,
                    QualityCachedResultExecutor::execute);
        }
        if (TabulateCachedResultToolSchema.MODE_RESAMPLE.equals(mode)) {
            return dispatchU4SeriesMode(root, requestedCacheId, ResampleCachedResultExecutor.TOOL_NAME,
                    ResampleCachedResultExecutor::execute);
        }
        if (TabulateCachedResultToolSchema.MODE_ROLLING.equals(mode)) {
            return dispatchU4SeriesMode(root, requestedCacheId, RollingCachedResultExecutor.TOOL_NAME,
                    RollingCachedResultExecutor::execute);
        }
        if (TabulateCachedResultToolSchema.MODE_RATE_OF_CHANGE.equals(mode)) {
            return dispatchU4SeriesMode(root, requestedCacheId, RateOfChangeCachedResultExecutor.TOOL_NAME,
                    RateOfChangeCachedResultExecutor::execute);
        }
        if (TabulateCachedResultToolSchema.MODE_ROLLING_STATS.equals(mode)) {
            return dispatchU4SeriesMode(root, requestedCacheId, RollingStatsCachedResultExecutor.TOOL_NAME,
                    RollingStatsCachedResultExecutor::execute);
        }
        if (TabulateCachedResultToolSchema.MODE_TIME_WEIGHTED.equals(mode)) {
            return dispatchU4SeriesMode(root, requestedCacheId, TimeWeightedCachedResultExecutor.TOOL_NAME,
                    TimeWeightedCachedResultExecutor::execute);
        }
        if (TabulateCachedResultToolSchema.MODE_CALENDAR_BUCKET.equals(mode)) {
            return dispatchU4SeriesMode(root, requestedCacheId, CalendarBucketCachedResultExecutor.TOOL_NAME,
                    CalendarBucketCachedResultExecutor::execute);
        }
        if (TabulateCachedResultToolSchema.MODE_COUNTER_DELTA.equals(mode)) {
            return dispatchU4SeriesMode(root, requestedCacheId, CounterDeltaCachedResultExecutor.TOOL_NAME,
                    CounterDeltaCachedResultExecutor::execute);
        }
        if (TabulateCachedResultToolSchema.MODE_PERIOD_COMPARE.equals(mode)) {
            return dispatchU4SeriesMode(root, requestedCacheId, PeriodCompareCachedResultExecutor.TOOL_NAME,
                    PeriodCompareCachedResultExecutor::execute);
        }
        String readCacheId = resolveTabulateSummarizeReadCacheId(requestedCacheId);
        if (readCacheId == null) {
            return lastTabularCacheUnavailableError();
        }
        try {
            CachedTabularFieldsProjection.assertModeAllowsFields(mode, root);
        } catch (CachedTabularDecisionToolException e) {
            return errorJson(e.code, e.getMessage());
        }
        InfoTable src = InvokeServiceExecutor.lookupCachedInfotable(readCacheId);
        if (src == null) {
            TabularCacheHandleMirror.pruneIfPointsTo(readCacheId);
            return com.thingworx.things.agent.recovery.TypedToolErrorJson.cacheMiss(readCacheId,
                    "No cached result for this cacheId in the current conversation (or expired).");
        }
        if (isDecisionTabulateMode(mode)) {
            try {
                assertDecisionInputSize(src);
            } catch (CachedTabularDecisionToolException e) {
                return errorJson(e.code, e.getMessage());
            }
        } else {
            String sizeErr = checkInputSize(src);
            if (sizeErr != null) {
                return errorJson("TABLE_TOO_LARGE_FOR_TRANSFORM", sizeErr);
            }
        }
        TabularPasswordColumnGuard.Violation tv;
        if (isDecisionTabulateMode(mode)) {
            tv = TabularPasswordColumnGuard.tabulateDecision(shapeOf(src), root, mode);
        } else {
            tv = TabularPasswordColumnGuard.tabulate(shapeOf(src), root, mode);
        }
        if (tv != null) {
            return tabularProtectedBlocked("tabulate_cached_result", tv.field, tv.columnName);
        }

        try {
            CachedTabulateLegacyKeyRejector.validate(mode, root);
        } catch (CachedTabularDecisionToolException e) {
            return errorJson(e.code, e.getMessage());
        }

        if (isDecisionTabulateMode(mode)) {
            return tabulateDecisionMode(readCacheId, src, root, mode);
        }

        InfoTable outTable;
        switch (mode) {
            case "sort_topn":
                outTable = tabulateSortTopn(src, root);
                outTable = applyRootFieldsProjection(outTable, root);
                break;
            case "group_count":
                outTable = tabulateGroupCount(src, root);
                break;
            case "group_aggregate":
                outTable = tabulateGroupAggregate(src, root);
                break;
            default:
                return errorJson("UNKNOWN_MODE", "Unknown mode: " + mode + ". Use filter_count, filter_rows, "
                        + "filter_sort_topn, group_metric, bin_numeric, or box_summary "
                        + "(replay aliases: sort_topn, group_count, group_aggregate).");
        }
        if (outTable == null) {
            return errorJson("TABULATE_ERROR", "Internal error: no result table.");
        }
        return formatTabulateJson(outTable, readCacheId);
    }

    @FunctionalInterface
    private interface U4ToolExecutor {
        String execute(ToolCall call) throws Exception;
    }

    /**
     * TQJ-5 Option A: {@code mode=quality}/{@code resample} — resolve left {@code cacheId}, then
     * delegate to the shared series executor (same admission / runner / vocabulary).
     */
    private static String dispatchU4SeriesMode(JsonNode root, String requestedCacheId, String toolName,
            U4ToolExecutor executor) throws Exception {
        String cacheId = resolveTabulateSummarizeReadCacheId(requestedCacheId);
        if (cacheId == null) {
            return lastTabularCacheUnavailableError();
        }
        ObjectNode args = root == null || !root.isObject()
                ? MAPPER.createObjectNode()
                : ((ObjectNode) root).deepCopy();
        args.put("cacheId", cacheId);
        args.remove("mode");
        return executor.execute(new ToolCall("tabulate-" + toolName, toolName, MAPPER.writeValueAsString(args)));
    }

    /**
     * TQJ-5 Option A: {@code mode=exact_join} delegates to {@link ExactJoinCachedResultExecutor}
     * (same admission, runner, envelope, and result vocabulary). {@code cacheId} is the left table.
     */
    private static String dispatchExactJoinMode(JsonNode root, String requestedCacheId) throws Exception {
        String leftId = resolveTabulateSummarizeReadCacheId(requestedCacheId);
        if (leftId == null) {
            return lastTabularCacheUnavailableError();
        }
        String rightId = text(root, "rightCacheId");
        if (rightId != null && CachedTabularLastCacheHandle.isToken(rightId.trim())) {
            ObjectNode err = MAPPER.createObjectNode();
            err.put("status", "ERROR");
            err.put("reason", ExactJoinCachedResultExecutor.ARGUMENT_MISSING);
            err.put("detail", "rightCacheId must be an explicit cache id (not the last-tabular sentinel).");
            err.put("mayPublish", false);
            return MAPPER.writeValueAsString(err);
        }
        ObjectNode joinArgs = MAPPER.createObjectNode();
        joinArgs.put("leftCacheId", leftId);
        if (rightId != null) {
            joinArgs.put("rightCacheId", rightId);
        }
        if (root != null && root.has("joinType") && !root.get("joinType").isNull()) {
            joinArgs.set("joinType", root.get("joinType"));
        }
        ToolCall joinCall = new ToolCall("tabulate-exact-join", ExactJoinCachedResultExecutor.TOOL_NAME,
                MAPPER.writeValueAsString(joinArgs));
        return ExactJoinCachedResultExecutor.execute(joinCall);
    }

    /**
     * Resolves {@code cacheId} for tabulate/summarize: normal ids pass through; P2 sentinel resolves to
     * {@link TabularChartRoundState#getLastCacheId()} when set (same agent turn as {@code build_chart_from_tabular_result}
     * {@code last_invoke} cache arm), else {@link AgentToolContext#getConversationLastQualifyingTabularCacheId()} for the
     * active {@link AgentToolContext#getConversationId()} (separate user messages within the same JVM agent process;
     * not across restarts).
     *
     * @return cache id to pass to {@link InvokeServiceExecutor#lookupCachedInfotable(String)}, or {@code null}
     *         when the argument is the P2 sentinel but no last cache id is available
     */
    private static String resolveTabulateSummarizeReadCacheId(String requestedCacheId) {
        String t = requestedCacheId.trim();
        if (CachedTabularLastCacheHandle.isToken(t)) {
            String id = AgentToolContext.tabularChartRoundState().getLastCacheId();
            if (id != null && !id.isEmpty()) {
                return id;
            }
            id = AgentToolContext.getConversationLastQualifyingTabularCacheId();
            return (id != null && !id.isEmpty()) ? id : null;
        }
        return t;
    }

    private static String doSummarize(ToolCall call) throws Exception {
        JsonNode root = CachedTabularToolArgumentsJson.readRoot(call.getArguments());
        if (!root.isObject()) {
            return errorJson("INVALID_PARAMETERS", "Tool arguments must be a JSON object.");
        }
        String requestedCacheId = text(root, "cacheId");
        if (requestedCacheId == null || requestedCacheId.isEmpty()) {
            return errorJson("MISSING_CACHE_ID", "cacheId is required.");
        }
        String readCacheId = resolveTabulateSummarizeReadCacheId(requestedCacheId);
        if (readCacheId == null) {
            return lastTabularCacheUnavailableError();
        }
        InfoTable src = InvokeServiceExecutor.lookupCachedInfotable(readCacheId);
        if (src == null) {
            TabularCacheHandleMirror.pruneIfPointsTo(readCacheId);
            return com.thingworx.things.agent.recovery.TypedToolErrorJson.cacheMiss(readCacheId,
                    "No cached result for this cacheId in the current conversation (or expired).");
        }
        String sizeErr = checkInputSize(src);
        if (sizeErr != null) {
            return errorJson("TABLE_TOO_LARGE_FOR_TRANSFORM", sizeErr);
        }
        int rows = src.getRowCount();
        List<String> cols = columnNames(src);
        List<String> percentileCols;
        try {
            percentileCols = parsePercentileColumnsStrict(root.get("percentileColumns"));
        } catch (IllegalArgumentException e) {
            return errorJson("INVALID_PARAMETERS", e.getMessage());
        }
        TabularPasswordColumnGuard.Violation pv = TabularPasswordColumnGuard.percentileExplicit(shapeOf(src), percentileCols);
        if (pv != null) {
            return tabularProtectedBlocked("summarize_cached_result", pv.field, pv.columnName);
        }
        String pctErr = validatePercentileColumns(src, cols, percentileCols);
        if (pctErr != null) {
            return errorJson("INVALID_PARAMETERS", pctErr);
        }

        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("sourceCacheId", readCacheId);
        out.put("rowCount", rows);
        if (rows == 0) {
            out.put("resultKind", "CACHED_SUMMARY_EMPTY");
            out.putArray("columns");
            attachInsightEnvelope(out, readCacheId, src);
            attachBp6PublicFields(out, descriptorForScannedSource(readCacheId, src, 0L,
                    "summarize_cached_result"));
            return MAPPER.writeValueAsString(out);
        }
        out.put("resultKind", "CACHED_SUMMARY_INLINE");
        ArrayNode colArr = MAPPER.createArrayNode();
        java.util.Set<String> percentileTargetCols = resolvePercentileTargetColumns(src, cols, percentileCols);
        DataShapeDefinition inputShape = shapeOf(src);
        for (String col : cols) {
            colArr.add(summarizeOneColumn(src, inputShape, col, rows, percentileTargetCols.contains(col)));
        }
        out.set("columns", colArr);
        attachInsightEnvelope(out, readCacheId, src);
        attachBp6PublicFields(out, descriptorForScannedSource(readCacheId, src, rows,
                "summarize_cached_result"));
        LOG.info("summarize_cached_result ok sourceCacheId={} rowCount={} columnCount={}", readCacheId, rows, cols.size());
        return MAPPER.writeValueAsString(out);
    }

    /**
     * P1 {@code insightEnvelope} on success payloads. Uses the same {@code sourceCacheId} string as the success root
     * (the **effective** cache id used for {@link InvokeServiceExecutor#lookupCachedInfotable(String)}, after P2
     * sentinel resolution when applicable). The third argument is the {@link InfoTable} whose **shape**
     * populates {@code rowEstimate} and {@code columns[]}: for {@code summarize_cached_result} pass the
     * **input** table; for {@code tabulate_cached_result} pass the **transformed output** table (see
     * {@code docs/agent/tabular_insight_envelope.md}).
     */
    static void attachInsightEnvelope(ObjectNode root, String sourceCacheId, InfoTable envelopeShapeTable) {
        if (sourceCacheId == null || sourceCacheId.isEmpty()) {
            return;
        }
        ObjectNode env = MAPPER.createObjectNode();
        env.put("schemaVersion", INSIGHT_ENVELOPE_SCHEMA_VERSION);
        env.put("sourceCacheId", sourceCacheId);
        int rowEst = envelopeShapeTable != null ? envelopeShapeTable.getRowCount() : 0;
        env.put("rowEstimate", rowEst);
        ArrayNode colArr = MAPPER.createArrayNode();
        if (envelopeShapeTable != null) {
            for (String name : columnNames(envelopeShapeTable)) {
                ObjectNode c = MAPPER.createObjectNode();
                c.put("name", name);
                BaseTypes bt = columnBaseType(envelopeShapeTable, name);
                c.put("baseType", bt != null ? bt.name() : "UNKNOWN");
                colArr.add(c);
            }
        }
        env.set("columns", colArr);
        root.set("insightEnvelope", env);
    }

    /**
     * BP6 public {@code completeness}/{@code counts} on tabulate/summarize success
     * ({@code CONTRACTS/TABULAR_INSIGHT.md} §5). Package-visible for group_metric.
     */
    static void attachBp6PublicFields(ObjectNode root,
            com.thingworx.things.agent.source.SourceDescriptor descriptor) {
        com.thingworx.things.agent.source.SourceDescriptorSupport.putPublicEnvelopeFields(root, descriptor);
    }

    /** Descriptor for summarize / filter_count over a fully scanned source cache table. */
    static com.thingworx.things.agent.source.SourceDescriptor descriptorForScannedSource(
            String sourceCacheId, InfoTable src, long rowsOutput, String routeId) {
        com.thingworx.things.agent.source.SourceDescriptor parent =
                InvokeServiceExecutor.lookupSourceDescriptor(sourceCacheId);
        long examined = src == null ? 0L : src.getRowCount();
        Long available = parent != null ? parent.rowsAvailable() : null;
        com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus st =
                parent != null && parent.completenessStatus() != null
                        ? parent.completenessStatus()
                        : com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus.UNKNOWN;
        com.thingworx.things.agent.source.SourceDescriptor.Builder b =
                com.thingworx.things.agent.source.SourceDescriptor.builder()
                        .sourceRouteId(routeId)
                        .rowsExamined(examined)
                        .rowsReturned(rowsOutput)
                        .rowsAvailable(available)
                        .completenessStatus(st)
                        .completenessReasons(parent != null ? parent.completenessReasons() : java.util.List.of())
                        .addParentSourceCacheId(sourceCacheId)
                        .requestId(AgentToolContext.getParlerRequestId());
        if (parent != null) {
            com.thingworx.things.agent.source.SourceDescriptor.copySemanticProvenance(b, parent);
        }
        return b.build();
    }

    /** @return error message, or {@code null} if OK */
    private static String validatePercentileColumns(InfoTable src, List<String> cols, List<String> percentileCols) {
        for (String pc : percentileCols) {
            if (!cols.contains(pc)) {
                return "percentileColumns: unknown column \"" + pc + "\".";
            }
            BaseTypes bt = columnBaseType(src, pc);
            if (!isNumericType(bt)) {
                return "percentileColumns: column \"" + pc + "\" is not numeric (NUMBER, INTEGER, or LONG).";
            }
        }
        return null;
    }

    /**
     * {@code percentileColumns} must be absent, null, or a JSON array whose every element is a non-empty string
     * (column name). Non-arrays, null elements, numbers, objects, or empty strings → {@link IllegalArgumentException}
     * for {@code INVALID_PARAMETERS}.
     */
    private static List<String> parsePercentileColumnsStrict(JsonNode node) {
        if (node == null || node.isNull()) {
            return new ArrayList<>();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("percentileColumns must be a JSON array of column name strings.");
        }
        List<String> list = new ArrayList<>();
        int i = 0;
        for (JsonNode n : node) {
            if (n == null || n.isNull()) {
                throw new IllegalArgumentException("percentileColumns[" + i + "] must be a non-null string.");
            }
            if (!n.isTextual()) {
                throw new IllegalArgumentException("percentileColumns[" + i + "] must be a string (column name).");
            }
            String s = n.asText().trim();
            if (s.isEmpty()) {
                throw new IllegalArgumentException("percentileColumns[" + i + "] must not be empty.");
            }
            list.add(s);
            i++;
        }
        return list;
    }

    /** When {@code explicit} is non-empty, only those names get p50/p95; else first K numeric columns in declaration order. */
    private static java.util.Set<String> resolvePercentileTargetColumns(InfoTable src, List<String> cols,
            List<String> explicit) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (!explicit.isEmpty()) {
            set.addAll(explicit);
            return set;
        }
        int k = 0;
        for (String c : cols) {
            if (isNumericType(columnBaseType(src, c))) {
                set.add(c);
                k++;
                if (k >= MAX_NUMERIC_COLUMNS_FOR_PERCENTILES_DEFAULT) {
                    break;
                }
            }
        }
        return set;
    }

    private static String tabularProtectedBlocked(String tool, String field, String columnName) {
        ParlerProtectionAudit.blocked(ProtectedValuePolicy.CODE_TABULAR_PROTECTED_COLUMN, tool,
                "field=" + field + " column=" + columnName);
        return errorJson(ProtectedValuePolicy.CODE_TABULAR_PROTECTED_COLUMN,
                "Cannot use PASSWORD column \"" + columnName + "\" for " + field + ".");
    }

    private static DataShapeDefinition shapeOf(InfoTable src) {
        try {
            return src != null ? src.getDataShape() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static ObjectNode summarizeOneColumn(InfoTable src, DataShapeDefinition inputShape, String col, int rows,
            boolean includePercentiles) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("name", col);
        BaseTypes bt = columnBaseType(src, col);
        o.put("baseType", bt != null ? bt.name() : "UNKNOWN");
        int nulls = 0;
        for (int i = 0; i < rows; i++) {
            ValueCollection row = src.getRow(i);
            if (row == null || row.getValue(col) == null) {
                nulls++;
            }
        }
        o.put("nullCount", nulls);
        if (nulls < rows) {
            o.put("nullRatio", (double) nulls / (double) rows);
        }

        if (ParlerInfotableJsonUtil.isPasswordColumn(inputShape, col)) {
            o.put("protectedColumnSummary", true);
            o.put("unsupportedStatsReason", "password_column");
            return o;
        }

        if (bt == null) {
            o.put("unsupportedStatsReason", "non-primitive");
            return o;
        }
        if (isNumericType(bt)) {
            ObjectNode num = buildNumericStats(src, col, rows, includePercentiles);
            o.set("numericStats", num);
            return o;
        }
        if (isCategoricalType(bt)) {
            o.set("categoricalStats", buildCategoricalStats(src, col, rows, 20));
            return o;
        }
        if (bt == BaseTypes.DATETIME) {
            ObjectNode dt = buildDatetimeRangeStats(src, col, rows);
            if (dt != null) {
                o.set("datetimeStats", dt);
            } else {
                o.put("unsupportedStatsReason", "non-primitive");
            }
            return o;
        }
        o.put("unsupportedStatsReason", "non-primitive");
        return o;
    }

    private static ObjectNode buildDatetimeRangeStats(InfoTable src, String col, int rows) {
        DateTime min = null;
        DateTime max = null;
        for (int i = 0; i < rows; i++) {
            DateTime dt = extractDateTime(src.getRow(i), col);
            if (dt == null) {
                continue;
            }
            if (min == null || dt.isBefore(min)) {
                min = dt;
            }
            if (max == null || dt.isAfter(max)) {
                max = dt;
            }
        }
        if (min == null) {
            return null;
        }
        ObjectNode o = MAPPER.createObjectNode();
        o.put("min", min.toString());
        o.put("max", max != null ? max.toString() : min.toString());
        return o;
    }

    private static DateTime extractDateTime(ValueCollection row, String col) {
        if (row == null) {
            return null;
        }
        Object v = row.getValue(col);
        if (v == null) {
            return null;
        }
        if (v instanceof DatetimePrimitive) {
            try {
                return ((DatetimePrimitive) v).getValue();
            } catch (Exception ignored) {
                return null;
            }
        }
        if (v instanceof DateTime) {
            return (DateTime) v;
        }
        if (v instanceof IPrimitiveType) {
            try {
                Object inner = ((IPrimitiveType) v).getValue();
                if (inner instanceof DateTime) {
                    return (DateTime) inner;
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        return null;
    }

    private static ObjectNode buildNumericStats(InfoTable src, String col, int rows, boolean includePercentiles) {
        List<Double> vals = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            Double d = extractDouble(src.getRow(i), col);
            if (d != null && !d.isNaN()) {
                vals.add(d);
            }
        }
        ObjectNode o = MAPPER.createObjectNode();
        if (vals.isEmpty()) {
            return o;
        }
        double min = Collections.min(vals);
        double max = Collections.max(vals);
        double sum = 0;
        for (double v : vals) {
            sum += v;
        }
        double mean = sum / vals.size();
        o.put("min", min);
        o.put("max", max);
        o.put("mean", mean);
        if (includePercentiles && vals.size() >= 1) {
            double[] arr = new double[vals.size()];
            for (int i = 0; i < vals.size(); i++) {
                arr[i] = vals.get(i);
            }
            Arrays.sort(arr);
            o.put("p50", percentileSorted(arr, 0.50));
            o.put("p95", percentileSorted(arr, 0.95));
        }
        return o;
    }

    /** Linear interpolation on sorted array; {@code p} in [0,1]. */
    private static double percentileSorted(double[] sorted, double p) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        double rank = (sorted.length - 1) * p;
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        lo = Math.max(0, Math.min(sorted.length - 1, lo));
        hi = Math.max(0, Math.min(sorted.length - 1, hi));
        if (lo == hi) {
            return sorted[lo];
        }
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo);
    }

    private static ObjectNode buildCategoricalStats(InfoTable src, String col, int rows, int topLimit) {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < rows; i++) {
            String k = cellToSortKey(src.getRow(i), col);
            counts.merge(k, 1, Integer::sum);
        }
        int card = counts.size();
        ObjectNode o = MAPPER.createObjectNode();
        o.put("cardinality", card);
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> {
            int c = Integer.compare(b.getValue(), a.getValue());
            if (c != 0) {
                return c;
            }
            return a.getKey().compareTo(b.getKey());
        });
        ArrayNode top = MAPPER.createArrayNode();
        for (int i = 0; i < Math.min(topLimit, entries.size()); i++) {
            ObjectNode t = MAPPER.createObjectNode();
            t.put("value", entries.get(i).getKey());
            t.put("count", entries.get(i).getValue());
            top.add(t);
        }
        o.set("top", top);
        return o;
    }

    private static IFilter parseAndResolveFilter(JsonNode filterJson, DataShapeDefinition shape)
            throws CachedTabularDecisionToolException {
        IFilter f = ParlerQueryFilterParser.parse(filterJson, shape);
        f.resolveFields(shape);
        return f;
    }

    private static InfoTable tabulateSortTopn(InfoTable src, JsonNode root) throws Exception {
        JsonNode sorts = root.get("sorts");
        if (sorts == null || !sorts.isArray() || sorts.size() < 1) {
            throw new IllegalArgumentException("sorts is required for mode sort_topn (1–3 keys).");
        }
        List<SortKeySpec> keys = parseSortsArrayForTabulate(sorts, src);
        int[] limitOffset = CachedTabularTabulatePaging.parseMaxItemsAndOffset(root,
                CachedTabularTabulatePaging.SORT_TOPN_DEFAULT_LIMIT);
        int limit = limitOffset[0];
        int offset = limitOffset[1];

        int n = src.getRowCount();
        if (n == 0) {
            return new InfoTable(src.getDataShape());
        }
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        Comparator<Integer> cmp = (a, b) -> {
            for (SortKeySpec sk : keys) {
                int c = compareRowsByColumn(src, sk.column, a, b, sk.descending, sk.stringSortCaseSensitive);
                if (c != 0) {
                    return c;
                }
            }
            return Integer.compare(a, b);
        };
        Arrays.sort(idx, cmp);

        int from = Math.min(offset, n);
        int to = Math.min(from + limit, n);
        InfoTable out = new InfoTable(src.getDataShape());
        for (int i = from; i < to; i++) {
            out.addRow(src.getRow(idx[i]));
        }
        return out;
    }

    private static InfoTable tabulateGroupCount(InfoTable src, JsonNode root) throws Exception {
        String groupBy = text(root, "groupBy");
        if (groupBy == null || groupBy.isEmpty()) {
            throw new IllegalArgumentException("groupBy is required for mode group_count.");
        }
        List<String> cols = columnNames(src);
        if (!cols.contains(groupBy)) {
            throw new IllegalArgumentException("Unknown column: " + groupBy);
        }
        int outLimit = CachedTabularTabulatePaging.parseGroupOutputMaxItems(root);

        Map<String, Long> counts = new LinkedHashMap<>();
        int n = src.getRowCount();
        for (int i = 0; i < n; i++) {
            String key = cellToSortKey(src.getRow(i), groupBy);
            if (!counts.containsKey(key) && counts.size() >= MAX_GROUP_CARDINALITY) {
                throw new IllegalArgumentException("Distinct group count exceeds " + MAX_GROUP_CARDINALITY + ".");
            }
            counts.merge(key, 1L, Long::sum);
        }
        List<Map.Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> {
            int c = Long.compare(b.getValue(), a.getValue());
            if (c != 0) {
                return c;
            }
            return a.getKey().compareTo(b.getKey());
        });
        List<Map.Entry<String, Long>> slice = entries.subList(0, Math.min(entries.size(), outLimit));

        DataShapeDefinition dsd = new DataShapeDefinition();
        addField(dsd, "group_key", BaseTypes.STRING, 0);
        addField(dsd, "count", BaseTypes.NUMBER, 1);
        InfoTable out = new InfoTable(dsd);
        for (Map.Entry<String, Long> e : slice) {
            ValueCollection vc = new ValueCollection();
            vc.put("group_key", new StringPrimitive(e.getKey()));
            vc.put("count", new NumberPrimitive(e.getValue().doubleValue()));
            out.addRow(vc);
        }
        return out;
    }

    private static InfoTable tabulateGroupAggregate(InfoTable src, JsonNode root) throws Exception {
        String groupBy = text(root, "groupBy");
        if (groupBy == null || groupBy.isEmpty()) {
            throw new IllegalArgumentException("groupBy is required for mode group_aggregate.");
        }
        String aggCol = text(root, "aggregateColumn");
        String fn = text(root, "fn");
        if (fn == null || fn.isBlank()) {
            throw new IllegalArgumentException("fn is required for mode group_aggregate (count|sum|avg|min|max).");
        }
        fn = fn.trim().toLowerCase(Locale.ROOT);
        List<String> cols = columnNames(src);
        if (!cols.contains(groupBy)) {
            throw new IllegalArgumentException("Unknown column: " + groupBy);
        }
        if (!"count".equals(fn) && (aggCol == null || aggCol.isEmpty() || !cols.contains(aggCol))) {
            throw new IllegalArgumentException("aggregateColumn is required and must name an existing column (except fn=count).");
        }
        int outLimit = CachedTabularTabulatePaging.parseGroupOutputMaxItems(root);

        BaseTypes aggBt = aggCol != null ? columnBaseType(src, aggCol) : null;
        if (!"count".equals(fn) && !isAggregateableType(aggBt)) {
            throw new IllegalArgumentException("aggregateColumn type not supported for " + fn + ".");
        }

        Map<String, AggAcc> acc = new LinkedHashMap<>();
        int n = src.getRowCount();
        for (int i = 0; i < n; i++) {
            ValueCollection row = src.getRow(i);
            String g = cellToSortKey(row, groupBy);
            if (!acc.containsKey(g) && acc.size() >= MAX_GROUP_CARDINALITY) {
                throw new IllegalArgumentException("Distinct group count exceeds " + MAX_GROUP_CARDINALITY + ".");
            }
            acc.computeIfAbsent(g, k -> new AggAcc());
            AggAcc a = acc.get(g);
            a.rowCount++;
            if (!"count".equals(fn) && aggCol != null) {
                a.accumulate(row, aggCol, fn, aggBt);
            }
        }
        for (AggAcc a : acc.values()) {
            a.finish(fn);
        }

        List<Map.Entry<String, AggAcc>> entries = new ArrayList<>(acc.entrySet());
        entries.sort(Comparator.comparing(e -> e.getKey()));

        DataShapeDefinition dsd = new DataShapeDefinition();
        addField(dsd, "group_key", BaseTypes.STRING, 0);
        addField(dsd, "agg_value", BaseTypes.NUMBER, 1);
        InfoTable out = new InfoTable(dsd);
        int added = 0;
        for (Map.Entry<String, AggAcc> e : entries) {
            if (added >= outLimit) {
                break;
            }
            ValueCollection vc = new ValueCollection();
            vc.put("group_key", new StringPrimitive(e.getKey()));
            double v = "count".equals(fn) ? e.getValue().rowCount : e.getValue().result;
            if (Double.isNaN(v)) {
                vc.put("agg_value", new NumberPrimitive(0.0));
            } else {
                vc.put("agg_value", new NumberPrimitive(v));
            }
            out.addRow(vc);
            added++;
        }
        return out;
    }

    private static final class AggAcc {
        long rowCount;
        double sum;
        long nNum;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        boolean hasNum;

        void accumulate(ValueCollection row, String aggCol, String fn, BaseTypes aggBt) {
            if ("count".equals(fn)) {
                return;
            }
            Double d = extractDouble(row, aggCol);
            if (d == null || d.isNaN()) {
                return;
            }
            hasNum = true;
            nNum++;
            sum += d;
            min = Math.min(min, d);
            max = Math.max(max, d);
        }

        void finish(String fn) {
            if ("count".equals(fn)) {
                return;
            }
            if (!hasNum) {
                result = Double.NaN;
                return;
            }
            switch (fn) {
                case "sum":
                    result = sum;
                    break;
                case "avg":
                    result = nNum > 0 ? sum / nNum : Double.NaN;
                    break;
                case "min":
                    result = min;
                    break;
                case "max":
                    result = max;
                    break;
                default:
                    result = Double.NaN;
            }
        }

        double result;
    }

    private static void addField(DataShapeDefinition dsd, String name, BaseTypes bt, int ordinal) {
        FieldDefinition fd = new FieldDefinition();
        fd.setName(name);
        fd.setBaseType(bt);
        fd.setOrdinal(ordinal);
        dsd.addFieldDefinition(fd);
    }

    private static String formatTabulateJson(InfoTable out, String sourceCacheId) throws Exception {
        return formatTabulateJson(out, sourceCacheId, null, false);
    }

    private static String formatTabulateJson(InfoTable out, String sourceCacheId,
            com.thingworx.things.agent.source.SourceDescriptor derivedOverride,
            boolean unionPackaging) throws Exception {
        int threshold = InvokeServiceExecutor.largeTableRowThreshold();
        List<String> cols = columnNames(out);
        int rc = out.getRowCount();
        ObjectNode root = MAPPER.createObjectNode();
        root.put("status", "success");
        root.put("sourceCacheId", sourceCacheId);
        com.thingworx.things.agent.source.SourceDescriptor derived = derivedOverride;
        if (derived == null) {
            com.thingworx.things.agent.source.SourceDescriptor parent =
                    InvokeServiceExecutor.lookupSourceDescriptor(sourceCacheId);
            derived = com.thingworx.things.agent.source.SourceDescriptorSupport.forDerivedStore(
                    parent, sourceCacheId, out, "tabulate_cached_result");
        }
        if (rc == 0) {
            root.put("resultKind", "CACHED_TABULATE_EMPTY");
            root.put("totalRows", 0);
            root.putArray("rows");
            ArrayNode colMeta = MAPPER.createArrayNode();
            for (String c : cols) {
                ObjectNode cn = MAPPER.createObjectNode();
                cn.put("name", c);
                colMeta.add(cn);
            }
            root.set("columns", colMeta);
            attachInsightEnvelope(root, sourceCacheId, out);
            attachBp6PublicFields(root, derived);
            return MAPPER.writeValueAsString(root);
        }
        if (rc <= threshold) {
            root.put("resultKind", "CACHED_TABULATE_INLINE");
            root.put("totalRows", rc);
            if (unionPackaging) {
                // union_rows only: the appended table is a new table, so even a small one needs its own handle
                // for the next analysis step. Stored before the envelope is built; a store failure fails the call.
                root.put("cacheId", InvokeServiceExecutor.storeInfotableInConversationCache(out, derived));
            }
            ArrayNode rows = MAPPER.createArrayNode();
            for (int i = 0; i < rc; i++) {
                rows.add(rowToObject(out, i, cols));
            }
            root.set("rows", rows);
            ArrayNode colMeta = MAPPER.createArrayNode();
            for (String c : cols) {
                ObjectNode cn = MAPPER.createObjectNode();
                cn.put("name", c);
                colMeta.add(cn);
            }
            root.set("columns", colMeta);
            attachInsightEnvelope(root, sourceCacheId, out);
            attachBp6PublicFields(root, derived);
        } else {
            String newCacheId = InvokeServiceExecutor.storeInfotableInConversationCache(out, derived);
            root.put("resultKind", "CACHED_TABULATE_LARGE");
            root.put("totalRows", rc);
            if (unionPackaging) {
                // union_rows only: say plainly that the model holds a sample. Every other mode keeps the LARGE
                // envelope it had before TR-2.
                root.put("returnedRows", threshold);
                root.put("sampleOnly", true);
                root.put("rowsOmitted", true);
            }
            root.put("cacheId", newCacheId);
            ArrayNode sample = MAPPER.createArrayNode();
            for (int i = 0; i < threshold; i++) {
                sample.add(rowToObject(out, i, cols));
            }
            root.set("sampleRows", sample);
            ArrayNode colMeta = MAPPER.createArrayNode();
            for (String c : cols) {
                ObjectNode cn = MAPPER.createObjectNode();
                cn.put("name", c);
                colMeta.add(cn);
            }
            root.set("columns", colMeta);
            root.put("hint", InvokeServiceExecutor.FETCH_CACHED_LARGE_TABLE_HINT);
            attachInsightEnvelope(root, sourceCacheId, out);
            attachBp6PublicFields(root, derived);
        }
        LOG.info("tabulate_cached_result ok sourceCacheId={} resultKind={} totalRows={}", sourceCacheId,
                root.get("resultKind").asText(), rc);
        return MAPPER.writeValueAsString(root);
    }

    private static String checkInputSize(InfoTable src) {
        int rows = src.getRowCount();
        if (rows > MAX_ROWS_FOR_TABULAR_TRANSFORM) {
            return "Input exceeds MAX_ROWS_FOR_TABULAR_TRANSFORM (" + MAX_ROWS_FOR_TABULAR_TRANSFORM + ").";
        }
        int c = columnNames(src).size();
        if (c < 1) {
            c = 1;
        }
        long cells = (long) rows * (long) c;
        if (cells > MAX_CELLS_FOR_TABULAR_TRANSFORM) {
            return "Input exceeds MAX_CELLS_FOR_TABULAR_TRANSFORM (" + MAX_CELLS_FOR_TABULAR_TRANSFORM + ").";
        }
        return null;
    }

    private static boolean isDecisionTabulateMode(String mode) {
        return "filter_count".equals(mode) || "filter_rows".equals(mode) || "filter_sort_topn".equals(mode)
                || "group_metric".equals(mode) || CachedTabularDistributionExecutor.MODE_BIN_NUMERIC.equals(mode)
                || CachedTabularDistributionExecutor.MODE_BOX_SUMMARY.equals(mode)
                || TabulateCachedResultToolSchema.MODE_UNION_ROWS.equals(mode);
    }

    static final int MAX_UNION_INPUTS = 31;

    /** Test seam: told each cacheId just before its InfoTable is read, so tests can prove what ran before any read. */
    static volatile java.util.function.Consumer<String> unionTableReadObserverForTests;

    private static String tabulateUnionRows(JsonNode root) throws Exception {
        JsonNode idsNode = root.get("sourceCacheIds");
        if (idsNode == null || !idsNode.isArray() || idsNode.size() < 2) {
            return errorJson("MISSING_SOURCE_CACHE_IDS",
                    "sourceCacheIds must be a JSON array of at least two cache id strings for mode union_rows.");
        }
        if (idsNode.size() > MAX_UNION_INPUTS) {
            return errorJson("TOO_MANY_UNION_INPUTS",
                    "At most " + MAX_UNION_INPUTS + " tables per union_rows call.");
        }
        List<String> cacheIds = new ArrayList<>();
        for (JsonNode n : idsNode) {
            if (n == null || !n.isTextual() || n.asText().isBlank()) {
                return errorJson("INVALID_SOURCE_CACHE_IDS", "Every sourceCacheIds entry must be a non-empty string.");
            }
            cacheIds.add(n.asText().trim());
        }
        String labelColumn = text(root, "labelColumn");
        if (labelColumn == null || labelColumn.isEmpty()) {
            return errorJson("MISSING_LABEL_COLUMN", "labelColumn is required for mode union_rows.");
        }
        List<String> labelValues = new ArrayList<>();
        if (root.has("labelValues")) {
            JsonNode lv = root.get("labelValues");
            if (lv == null || !lv.isArray()) {
                return errorJson("INVALID_LABEL_VALUES", "labelValues must be a JSON array of strings when provided.");
            }
            if (lv.size() != cacheIds.size()) {
                return errorJson("INVALID_LABEL_VALUES",
                        "labelValues must have the same length as sourceCacheIds when provided.");
            }
            for (JsonNode n : lv) {
                if (n == null || !n.isTextual()) {
                    return errorJson("INVALID_LABEL_VALUES", "Every labelValues entry must be a string.");
                }
                labelValues.add(n.asText());
            }
        } else {
            for (int i = 0; i < cacheIds.size(); i++) {
                labelValues.add("");
            }
        }
        TabularExpansionBudget budget = TabularExpansionBudget.start();
        budget.add(TabularExpansionBudget.labelFieldHeaderBytes(labelColumn));
        long totalRows = 0;
        int totalCols = -1;
        long totalCells = 0;
        List<com.thingworx.things.agent.source.SourceDescriptor> parents = new ArrayList<>();
        for (String cacheId : cacheIds) {
            com.thingworx.things.agent.source.SourceDescriptor desc =
                    InvokeServiceExecutor.lookupSourceDescriptor(cacheId);
            if (desc == null) {
                TabularCacheHandleMirror.pruneIfPointsTo(cacheId);
                return com.thingworx.things.agent.recovery.TypedToolErrorJson.cacheMiss(cacheId,
                        "No cached result for this cacheId in the current conversation (or expired).");
            }
            parents.add(desc);
            Long rows = desc.rowsReturned();
            if (rows == null) {
                return errorJson("SOURCE_ROW_COUNT_UNKNOWN",
                        "Cannot union cacheId \"" + cacheId + "\": row count is not available on the descriptor.");
            }
            totalRows += rows;
            if (totalRows > MAX_SCANNED_ROWS_DECISION) {
                return errorJson("SOURCE_TOO_LARGE",
                        "Union would exceed MAX_SCANNED_ROWS_DECISION (" + MAX_SCANNED_ROWS_DECISION + ").");
            }
        }
        DataShapeDefinition expectedShape = null;
        List<String> expectedCols = null;
        InfoTable out = null;
        for (int i = 0; i < cacheIds.size(); i++) {
            String cacheId = cacheIds.get(i);
            // The deadline belongs to the whole call: it is checked before every read, not only while measuring.
            if (budget.timeExceeded()) {
                return unionTimeBudgetError();
            }
            java.util.function.Consumer<String> readObserver = unionTableReadObserverForTests;
            if (readObserver != null) {
                readObserver.accept(cacheId);
            }
            InfoTable src = InvokeServiceExecutor.lookupCachedInfotable(cacheId);
            if (src == null) {
                TabularCacheHandleMirror.pruneIfPointsTo(cacheId);
                return com.thingworx.things.agent.recovery.TypedToolErrorJson.cacheMiss(cacheId,
                        "No cached result for this cacheId in the current conversation (or expired).");
            }
            DataShapeDefinition shape = shapeOf(src);
            List<String> cols = columnNames(src);
            if (cols.contains(labelColumn)) {
                return errorJson("LABEL_COLUMN_COLLISION",
                        "labelColumn \"" + labelColumn + "\" already exists in the input schema.");
            }
            int colCount = cols.size();
            if (i == 0) {
                expectedShape = shape;
                expectedCols = cols;
                totalCols = colCount;
                totalCells = (long) src.getRowCount() * (long) (colCount + 1);
            } else {
                if (!unionSchemasMatch(expectedCols, expectedShape, cols, shape, cacheId)) {
                    return errorJson("UNION_COLUMN_MISMATCH",
                            "Column name or base type mismatch in cacheId \"" + cacheId + "\".");
                }
                totalCells += (long) src.getRowCount() * (long) (colCount + 1);
            }
            if (totalCells > MAX_CELLS_FOR_TABULAR_TRANSFORM) {
                return errorJson("TABLE_TOO_LARGE_FOR_TRANSFORM",
                        "Union exceeds MAX_CELLS_FOR_TABULAR_TRANSFORM (" + MAX_CELLS_FOR_TABULAR_TRANSFORM + ").");
            }
            // Bytes and time are charged for this input before any of its rows is appended. The bound covers what
            // the cache codec will write: escaped column names and the escaped label on every row, null for a
            // missing value, the escaped text of every cell, nested tables included.
            long labelBytesPerRow = TabularExpansionBudget.labelCellBytes(labelColumn, labelValues.get(i));
            if (!budget.chargeInfoTable(src, labelBytesPerRow)) {
                if (budget.timeExceeded()) {
                    return unionTimeBudgetError();
                }
                return errorJson("TABLE_TOO_LARGE_FOR_TRANSFORM",
                        "Union would expand beyond the cache storage byte budget (" + budget.maxBytes()
                                + " bytes); nothing was cached.");
            }
            if (out == null) {
                out = new InfoTable(buildUnionOutputShape(expectedShape, labelColumn));
            }
            if (!appendUnionRows(out, src, expectedCols, labelColumn, labelValues.get(i), budget)) {
                return unionTimeBudgetError();
            }
        }
        // Last boundary before the appended table is copied, encoded and published.
        if (budget.timeExceeded()) {
            return unionTimeBudgetError();
        }
        if (out == null) {
            out = new InfoTable(buildUnionOutputShape(new DataShapeDefinition(), labelColumn));
        }
        ObjectNode meta = MAPPER.createObjectNode();
        meta.put("unionRowsNotDeduped", true);
        meta.put("inputCount", cacheIds.size());
        String primaryId = cacheIds.get(0);
        com.thingworx.things.agent.source.SourceDescriptor derived =
                derivedDescriptorForUnion(parents, cacheIds, out);
        String json = formatTabulateJson(out, primaryId, derived, true);
        ObjectNode envelope = (ObjectNode) MAPPER.readTree(json);
        envelope.set("unionMeta", meta);
        envelope.set("sourceCacheIds", MAPPER.valueToTree(cacheIds));
        return MAPPER.writeValueAsString(envelope);
    }

    private static String unionTimeBudgetError() {
        return errorJson("UNION_TIME_BUDGET_EXCEEDED",
                "Union exceeded the shared wall-time budget of this call; nothing was cached.");
    }

    static com.thingworx.things.agent.source.SourceDescriptor derivedDescriptorForUnion(
            List<com.thingworx.things.agent.source.SourceDescriptor> parents, List<String> cacheIds,
            InfoTable out) {
        com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus merged =
                com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus.COMPLETE;
        long examined = 0;
        boolean hasExamined = false;
        for (com.thingworx.things.agent.source.SourceDescriptor parent : parents) {
            com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus ps =
                    parent == null || parent.completenessStatus() == null
                            ? com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus.UNKNOWN
                            : parent.completenessStatus();
            merged = mergeUnionCompleteness(merged, ps);
            Long rows = parent != null && parent.rowsReturned() != null
                    ? parent.rowsReturned()
                    : (parent != null ? parent.rowsExamined() : null);
            if (rows != null) {
                examined += rows;
                hasExamined = true;
            }
        }
        com.thingworx.things.agent.source.SourceDescriptor base = parents.isEmpty() ? null : parents.get(0);
        com.thingworx.things.agent.source.SourceDescriptor derived = base == null
                ? com.thingworx.things.agent.source.SourceDescriptorSupport.forPrimaryStore(out,
                        "tabulate_cached_result")
                : com.thingworx.things.agent.source.SourceDescriptorSupport.forDerivedStore(base, cacheIds.get(0),
                        out, "tabulate_cached_result");
        com.thingworx.things.agent.source.SourceDescriptor.Builder b =
                com.thingworx.things.agent.source.SourceDescriptor.builder()
                        .sourceRouteId(derived.sourceRouteId())
                        .thingOrServiceKind(derived.thingOrServiceKind())
                        .sanitizedParamDigest(derived.sanitizedParamDigest())
                        .rowsExamined(hasExamined ? examined : derived.rowsExamined())
                        .rowsReturned(derived.rowsReturned())
                        .rowsAvailable(null)
                        .completenessStatus(merged)
                        .completenessReasons(unionCompletenessReasons(parents))
                        .principalBinding(derived.principalBinding())
                        .executionScopeId(derived.executionScopeId())
                        .producerToolCallId(derived.producerToolCallId())
                        .requestId(derived.requestId() != null
                                ? derived.requestId()
                                : AgentToolContext.getParlerRequestId());
        for (String cacheId : cacheIds) {
            b.addParentSourceCacheId(cacheId);
        }
        com.thingworx.things.agent.source.SourceDescriptor.copySemanticProvenance(b, derived);
        return b.build();
    }

    /**
     * Reasons of every input, in input order, without duplicates. Taking only the first input's reasons would drop
     * a mark such as {@code READ_LIMIT_REACHED} exactly where several reads are combined.
     */
    static List<String> unionCompletenessReasons(
            List<com.thingworx.things.agent.source.SourceDescriptor> parents) {
        java.util.LinkedHashSet<String> reasons = new java.util.LinkedHashSet<>();
        for (com.thingworx.things.agent.source.SourceDescriptor parent : parents) {
            if (parent != null) {
                reasons.addAll(parent.completenessReasons());
            }
        }
        return new ArrayList<>(reasons);
    }

    private static com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus mergeUnionCompleteness(
            com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus left,
            com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus right) {
        if (left == com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus.PARTIAL
                || right == com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus.PARTIAL) {
            return com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus.PARTIAL;
        }
        if (left == com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus.UNKNOWN
                || right == com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus.UNKNOWN) {
            return com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus.UNKNOWN;
        }
        return com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus.COMPLETE;
    }

    private static boolean unionSchemasMatch(List<String> expectedCols, DataShapeDefinition expectedShape,
            List<String> cols, DataShapeDefinition shape, String cacheId) {
        if (expectedCols.size() != cols.size()) {
            return false;
        }
        for (int i = 0; i < expectedCols.size(); i++) {
            String name = expectedCols.get(i);
            if (!name.equals(cols.get(i))) {
                return false;
            }
            BaseTypes a = fieldBaseType(expectedShape, name);
            BaseTypes b = fieldBaseType(shape, name);
            if (a != b) {
                return false;
            }
            // The output column takes the first input's nested shape, so every later input must have the same one.
            if (a == BaseTypes.INFOTABLE
                    && !sameLocalShape(localShapeOf(expectedShape, name), localShapeOf(shape, name), 1)) {
                return false;
            }
        }
        return true;
    }

    private static DataShapeDefinition localShapeOf(DataShapeDefinition shape, String fieldName) {
        FieldDefinition fd = shape == null || shape.getFields() == null ? null : shape.getFields().get(fieldName);
        return fd == null ? null : fd.getLocalDataShape();
    }

    /** Same field names in the same order with the same base types, recursively; two absent shapes are equal. */
    private static boolean sameLocalShape(DataShapeDefinition left, DataShapeDefinition right, int depth) {
        if (left == null || right == null) {
            return left == right;
        }
        if (depth > 4) {
            return false;
        }
        List<FieldDefinition> l = orderedFields(left);
        List<FieldDefinition> r = orderedFields(right);
        if (l.size() != r.size()) {
            return false;
        }
        for (int i = 0; i < l.size(); i++) {
            FieldDefinition a = l.get(i);
            FieldDefinition b = r.get(i);
            if (!java.util.Objects.equals(a.getName(), b.getName()) || a.getBaseType() != b.getBaseType()
                    || !sameLocalShape(a.getLocalDataShape(), b.getLocalDataShape(), depth + 1)) {
                return false;
            }
        }
        return true;
    }

    private static List<FieldDefinition> orderedFields(DataShapeDefinition shape) {
        List<FieldDefinition> out = new ArrayList<>();
        if (shape.getFields() != null) {
            out.addAll(shape.getFields().getOrderedFieldsByOrdinal());
        }
        return out;
    }

    private static DataShapeDefinition buildUnionOutputShape(DataShapeDefinition input, String labelColumn) {
        DataShapeDefinition out = new DataShapeDefinition();
        int ord = 0;
        if (input.getFields() != null) {
            for (FieldDefinition fd : input.getFields().values()) {
                FieldDefinition copy = new FieldDefinition();
                copy.setName(fd.getName());
                copy.setBaseType(fd.getBaseType());
                copy.setOrdinal(ord++);
                // A nested INFOTABLE column keeps its local shape: without it the cache cannot prove that the
                // nested table has no PASSWORD column and rightly refuses to store the union.
                if (fd.getLocalDataShape() != null) {
                    copy.setLocalDataShape(fd.getLocalDataShape());
                }
                out.addFieldDefinition(copy);
            }
        }
        FieldDefinition label = new FieldDefinition();
        label.setName(labelColumn);
        label.setBaseType(BaseTypes.STRING);
        label.setOrdinal(ord);
        out.addFieldDefinition(label);
        return out;
    }

    /** @return {@code false} when the call's shared deadline passed while copying; the caller caches nothing */
    private static boolean appendUnionRows(InfoTable out, InfoTable src, List<String> cols, String labelColumn,
            String labelValue, TabularExpansionBudget budget) throws Exception {
        int n = src.getRowCount();
        for (int i = 0; i < n; i++) {
            if (i % TabularExpansionBudget.TIME_CHECK_ROW_INTERVAL == 0 && budget.timeExceeded()) {
                return false;
            }
            ValueCollection row = new ValueCollection();
            ValueCollection srcRow = src.getRow(i);
            for (String col : cols) {
                if (srcRow != null && srcRow.containsKey(col)) {
                    row.put(col, srcRow.getPrimitive(col));
                }
            }
            row.put(labelColumn, new StringPrimitive(labelValue == null ? "" : labelValue));
            out.addRow(row);
        }
        return true;
    }

    /** Decision-mode input budget (rows, then cells); package-visible so the D1 fixtures can assert the cell cap directly. */
    static void assertDecisionInputSize(InfoTable src) throws CachedTabularDecisionToolException {
        int rows = src.getRowCount();
        if (rows > MAX_SCANNED_ROWS_DECISION) {
            throw new CachedTabularDecisionToolException("SOURCE_TOO_LARGE",
                    "Source table row count exceeds MAX_SCANNED_ROWS (" + MAX_SCANNED_ROWS_DECISION + ").");
        }
        int c = columnNames(src).size();
        if (c < 1) {
            c = 1;
        }
        long cells = (long) rows * (long) c;
        if (cells > MAX_CELLS_FOR_TABULAR_TRANSFORM) {
            throw new CachedTabularDecisionToolException("TABLE_TOO_LARGE_FOR_TRANSFORM",
                    "Input exceeds MAX_CELLS_FOR_TABULAR_TRANSFORM (" + MAX_CELLS_FOR_TABULAR_TRANSFORM + ").");
        }
    }

    private static String tabulateDecisionMode(String readCacheId, InfoTable src, JsonNode root, String mode)
            throws Exception {
        if ("filter_count".equals(mode)) {
            return tabulateFilterCount(readCacheId, src, shapeOf(src), root);
        }
        if ("filter_rows".equals(mode)) {
            return tabulateFilterRows(readCacheId, src, shapeOf(src), root);
        }
        if ("filter_sort_topn".equals(mode)) {
            return tabulateFilterSortTopn(readCacheId, src, shapeOf(src), root);
        }
        if ("group_metric".equals(mode)) {
            return CachedTabularGroupMetricExecutor.tabulate(readCacheId, src, shapeOf(src), root);
        }
        // D1 distribution operators (chart-enhancement design §7.4): same decision budgets and filters.
        if (CachedTabularDistributionExecutor.MODE_BIN_NUMERIC.equals(mode)) {
            return CachedTabularDistributionExecutor.binNumeric(readCacheId, src, shapeOf(src), root);
        }
        if (CachedTabularDistributionExecutor.MODE_BOX_SUMMARY.equals(mode)) {
            return CachedTabularDistributionExecutor.boxSummary(readCacheId, src, shapeOf(src), root);
        }
        return errorJson("UNKNOWN_MODE", "Unknown decision mode: " + mode);
    }

    private static String tabulateFilterCount(String readCacheId, InfoTable src, DataShapeDefinition shape, JsonNode root)
            throws Exception {
        JsonNode filters = root.get("filters");
        if (filters == null || filters.isNull()) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "filters is required for mode filter_count.");
        }
        IFilter filter = parseAndResolveFilter(filters, shape);
        java.util.Map<String, Byte> coercion = CachedTabularNumericStringCoercion.policiesForPredicate(src, shape, filters);
        CachedTabularDecisionPredicate.setStringNumericCoercion(coercion);
        String groupCol = null;
        int n;
        int matchCount;
        List<Integer> sampleIdx;
        Map<String, Long> groupMatches;
        try {
            CachedTabularDecisionPredicate.checkAmbiguousPercentScale(src, shape, filters, coercion);
            JsonNode gb = root.get("groupBy");
            if (gb != null && gb.isTextual()) {
                String g = gb.asText().trim();
                if (!g.isEmpty()) {
                    groupCol = g;
                }
            }
            if (groupCol != null && !columnNames(src).contains(groupCol)) {
                throw new CachedTabularDecisionToolException("INVALID_COLUMN", "Unknown column: " + groupCol);
            }
            n = src.getRowCount();
            matchCount = 0;
            sampleIdx = new ArrayList<>();
            groupMatches = groupCol != null ? new LinkedHashMap<>() : null;
            for (int i = 0; i < n; i++) {
                if (!CachedTabularDecisionPredicate.evaluateIfilter(filter, src.getRow(i))) {
                    continue;
                }
                matchCount++;
                if (groupCol == null && sampleIdx.size() < DECISION_SAMPLE_ROWS_CAP) {
                    sampleIdx.add(i);
                }
                if (groupMatches != null) {
                    String key = cellToSortKey(src.getRow(i), groupCol);
                    groupMatches.merge(key, 1L, Long::sum);
                }
            }
        } finally {
            CachedTabularDecisionPredicate.clearStringNumericCoercion();
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("sourceCacheId", readCacheId);
        out.put("rowCount", n);
        out.put("matchCount", matchCount);
        out.set("predicate", MAPPER.readTree(MAPPER.writeValueAsString(filters)));
        if (groupCol != null) {
            out.put("resultKind", "CACHED_FILTER_GROUP_COUNT");
            out.put("groupBy", groupCol);
            ArrayNode groups = MAPPER.createArrayNode();
            List<Map.Entry<String, Long>> entries = new ArrayList<>(groupMatches.entrySet());
            entries.sort((a, b) -> {
                int c = Long.compare(b.getValue(), a.getValue());
                if (c != 0) {
                    return c;
                }
                return a.getKey().compareTo(b.getKey());
            });
            int cap = Math.min(entries.size(), TABULATE_MAX_LIMIT);
            for (int i = 0; i < cap; i++) {
                Map.Entry<String, Long> e = entries.get(i);
                ObjectNode g = MAPPER.createObjectNode();
                g.put("key", e.getKey());
                g.put("matchCount", e.getValue().intValue());
                groups.add(g);
            }
            out.set("groups", groups);
        } else {
            out.put("resultKind", "CACHED_FILTER_COUNT");
            ArrayNode samples = MAPPER.createArrayNode();
            List<String> cols = columnNames(src);
            for (int idx : sampleIdx) {
                samples.add(rowToObject(src, idx, cols));
            }
            out.set("sampleRows", samples);
        }
        attachInsightEnvelope(out, readCacheId, src);
        attachBp6PublicFields(out, descriptorForScannedSource(readCacheId, src, matchCount,
                "tabulate_cached_result.filter_count"));
        LOG.info("tabulate_cached_result filter_count ok sourceCacheId={} matchCount={}", readCacheId, matchCount);
        return MAPPER.writeValueAsString(out);
    }

    private static String tabulateFilterRows(String readCacheId, InfoTable src, DataShapeDefinition shape, JsonNode root)
            throws Exception {
        JsonNode filters = root.get("filters");
        if (filters == null || filters.isNull()) {
            throw new CachedTabularDecisionToolException("INVALID_PREDICATE", "filters is required for mode filter_rows.");
        }
        IFilter filter = parseAndResolveFilter(filters, shape);
        java.util.Map<String, Byte> coercion = CachedTabularNumericStringCoercion.policiesForPredicate(src, shape, filters);
        CachedTabularDecisionPredicate.setStringNumericCoercion(coercion);
        int[] limOff = CachedTabularTabulatePaging.parseMaxItemsAndOffset(root, CachedTabularTabulatePaging.SORT_TOPN_DEFAULT_LIMIT);
        int limit = limOff[0];
        int offset = limOff[1];
        int n;
        List<Integer> matching;
        try {
            CachedTabularDecisionPredicate.checkAmbiguousPercentScale(src, shape, filters, coercion);
            n = src.getRowCount();
            matching = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (CachedTabularDecisionPredicate.evaluateIfilter(filter, src.getRow(i))) {
                    matching.add(i);
                }
            }
        } finally {
            CachedTabularDecisionPredicate.clearStringNumericCoercion();
        }
        JsonNode sorts = root.get("sorts");
        if (sorts != null && sorts.isArray() && sorts.size() > 0) {
            List<SortKeySpec> sortKeys = parseSortsArrayForDecision(sorts, src, false, false);
            matching.sort((ia, ib) -> {
                for (SortKeySpec sk : sortKeys) {
                    int c = compareRowsByColumn(src, sk.column, ia, ib, sk.descending, sk.stringSortCaseSensitive);
                    if (c != 0) {
                        return c;
                    }
                }
                return Integer.compare(ia, ib);
            });
        }
        int fullMatch = matching.size();
        int from = Math.min(offset, matching.size());
        int to = Math.min(from + limit, matching.size());
        InfoTable out = new InfoTable(src.getDataShape());
        for (int j = from; j < to; j++) {
            out.addRow(src.getRow(matching.get(j)));
        }
        out = applyRootFieldsProjection(out, root);
        return formatDecisionTableJson(out, readCacheId, n, fullMatch, "CACHED_FILTER_ROWS_EMPTY",
                "CACHED_FILTER_ROWS_INLINE", "CACHED_FILTER_ROWS_LARGE");
    }

    private static String tabulateFilterSortTopn(String readCacheId, InfoTable src, DataShapeDefinition shape,
            JsonNode root) throws Exception {
        JsonNode filtersNode = root.get("filters");
        IFilter pred = null;
        java.util.Map<String, Byte> coercion = java.util.Collections.emptyMap();
        if (filtersNode != null && !filtersNode.isNull()) {
            pred = parseAndResolveFilter(filtersNode, shape);
            coercion = CachedTabularNumericStringCoercion.policiesForPredicate(src, shape, filtersNode);
        }
        CachedTabularDecisionPredicate.setStringNumericCoercion(coercion);
        try {
            if (filtersNode != null && !filtersNode.isNull()) {
                CachedTabularDecisionPredicate.checkAmbiguousPercentScale(src, shape, filtersNode, coercion);
            }
            int[] limOff = CachedTabularTabulatePaging.parseMaxItemsAndOffset(root, CachedTabularTabulatePaging.SORT_TOPN_DEFAULT_LIMIT);
            int limit = limOff[0];
            int offset = limOff[1];
            List<SortKeySpec> keys = parseSortsArrayForDecision(root.get("sorts"), src, true, false);
            int n = src.getRowCount();
            List<Integer> matching = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (pred == null || CachedTabularDecisionPredicate.evaluateIfilter(pred, src.getRow(i))) {
                    matching.add(i);
                }
            }
            int fullMatch = matching.size();
            Integer[] idx = matching.toArray(new Integer[0]);
            Comparator<Integer> cmp = (ia, ib) -> {
                for (SortKeySpec sk : keys) {
                    int c = compareRowsByColumn(src, sk.column, ia, ib, sk.descending, sk.stringSortCaseSensitive);
                    if (c != 0) {
                        return c;
                    }
                }
                return Integer.compare(ia, ib);
            };
            Arrays.sort(idx, cmp);
            int from = Math.min(offset, idx.length);
            int to = Math.min(from + limit, idx.length);
            InfoTable out = new InfoTable(src.getDataShape());
            for (int j = from; j < to; j++) {
                out.addRow(src.getRow(idx[j]));
            }
            InfoTable projected = applyRootFieldsProjection(out, root);
            return formatDecisionTableJson(projected, readCacheId, n, fullMatch, "CACHED_FILTER_SORT_TOPN_EMPTY",
                    "CACHED_FILTER_SORT_TOPN_INLINE", "CACHED_FILTER_SORT_TOPN_LARGE");
        } finally {
            CachedTabularDecisionPredicate.clearStringNumericCoercion();
        }
    }

    private static InfoTable applyRootFieldsProjection(InfoTable out, JsonNode root) throws Exception {
        List<String> cols = columnNames(out);
        List<String> proj = CachedTabularFieldsProjection.parseFieldsOrNull(root.get("fields"), cols);
        if (proj == null) {
            return out;
        }
        return CachedTabularFieldsProjection.project(out, proj);
    }

    private static final class SortKeySpec {
        final String column;
        final boolean descending;
        final boolean stringSortCaseSensitive;

        SortKeySpec(String column, boolean descending, boolean stringSortCaseSensitive) {
            this.column = column;
            this.descending = descending;
            this.stringSortCaseSensitive = stringSortCaseSensitive;
        }
    }

    private static List<SortKeySpec> parseSortsArrayForTabulate(JsonNode sorts, InfoTable src) throws Exception {
        try {
            return parseSortsArray(sorts, src, true, true);
        } catch (CachedTabularDecisionToolException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    /**
     * @param requireNonEmpty when {@code true}, empty or missing {@code sorts} is an error
     * @param sortTopnRules when {@code true}, use {@link #isSortableColumn}; otherwise decision-mode sortability
     */
    private static List<SortKeySpec> parseSortsArrayForDecision(JsonNode sorts, InfoTable src, boolean requireNonEmpty,
            boolean sortTopnRules) throws CachedTabularDecisionToolException {
        return parseSortsArray(sorts, src, requireNonEmpty, sortTopnRules);
    }

    private static List<SortKeySpec> parseSortsArray(JsonNode sorts, InfoTable src, boolean requireNonEmpty,
            boolean sortTopnRules) throws CachedTabularDecisionToolException {
        if (sorts == null || !sorts.isArray() || sorts.size() == 0) {
            if (requireNonEmpty) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                        PredicateErrorMessages.sortEntryRequiresFieldName(sorts));
            }
            return Collections.emptyList();
        }
        if (sorts.size() > 3) {
            throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "sorts accepts at most 3 keys.");
        }
        List<String> cols = columnNames(src);
        List<SortKeySpec> out = new ArrayList<>();
        Set<String> seenSortFields = new HashSet<>();
        for (int i = 0; i < sorts.size(); i++) {
            JsonNode el = sorts.get(i);
            if (el == null || !el.isObject()) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS", "sorts[" + i + "] must be an object.");
            }
            JsonNode fn = el.get("fieldName");
            if (fn == null || !fn.isTextual() || fn.asText().trim().isEmpty()) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                        PredicateErrorMessages.sortEntryRequiresFieldName(el));
            }
            String col = fn.asText().trim();
            if (!seenSortFields.add(col)) {
                throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                        "sorts contains duplicate fieldName \"" + col + "\".");
            }
            if (!cols.contains(col)) {
                throw new CachedTabularDecisionToolException("INVALID_COLUMN", "Unknown column: " + col);
            }
            if (sortTopnRules) {
                if (!isSortableColumn(src, col)) {
                    throw new CachedTabularDecisionToolException("UNSORTABLE_COLUMN",
                            "Column \"" + col + "\" is not sortable for sort_topn.");
                }
            } else {
                if (!isSortableColumnForDecisionModes(src, col)) {
                    throw new CachedTabularDecisionToolException("UNSORTABLE_COLUMN",
                            "Column \"" + col + "\" is not sortable for sorts[].");
                }
            }
            boolean ascending = true;
            if (el.has("isAscending")) {
                JsonNode ascN = el.get("isAscending");
                if (ascN == null || ascN.isNull() || !ascN.isBoolean()) {
                    throw new CachedTabularDecisionToolException("INVALID_PARAMETERS",
                            "sorts[" + i + "].isAscending must be a boolean when present.");
                }
                ascending = ascN.booleanValue();
            }
            boolean caseSens = el.path("isCaseSensitive").asBoolean(true);
            out.add(new SortKeySpec(col, !ascending, caseSens));
        }
        return out;
    }

    private static String formatDecisionTableJson(InfoTable out, String sourceCacheId, int sourceRowCount,
            int fullMatchCount, String kindEmpty, String kindInline, String kindLarge) throws Exception {
        int threshold = InvokeServiceExecutor.largeTableRowThreshold();
        List<String> cols = columnNames(out);
        int rc = out.getRowCount();
        ObjectNode root = MAPPER.createObjectNode();
        root.put("status", "success");
        root.put("sourceCacheId", sourceCacheId);
        root.put("rowCount", sourceRowCount);
        root.put("matchCount", fullMatchCount);
        com.thingworx.things.agent.source.SourceDescriptor parent =
                InvokeServiceExecutor.lookupSourceDescriptor(sourceCacheId);
        com.thingworx.things.agent.source.SourceDescriptor derived =
                com.thingworx.things.agent.source.SourceDescriptorSupport.forDerivedStore(
                        parent, sourceCacheId, out, "tabulate_cached_result");
        if (rc == 0) {
            root.put("resultKind", kindEmpty);
            root.put("totalRows", 0);
            root.putArray("rows");
            ArrayNode colMeta = MAPPER.createArrayNode();
            for (String c : cols) {
                ObjectNode cn = MAPPER.createObjectNode();
                cn.put("name", c);
                colMeta.add(cn);
            }
            root.set("columns", colMeta);
            attachInsightEnvelope(root, sourceCacheId, out);
            attachBp6PublicFields(root, derived);
            return MAPPER.writeValueAsString(root);
        }
        if (rc <= threshold) {
            root.put("resultKind", kindInline);
            root.put("totalRows", rc);
            ArrayNode rows = MAPPER.createArrayNode();
            for (int i = 0; i < rc; i++) {
                rows.add(rowToObject(out, i, cols));
            }
            root.set("rows", rows);
            ArrayNode colMeta = MAPPER.createArrayNode();
            for (String c : cols) {
                ObjectNode cn = MAPPER.createObjectNode();
                cn.put("name", c);
                colMeta.add(cn);
            }
            root.set("columns", colMeta);
            attachInsightEnvelope(root, sourceCacheId, out);
            attachBp6PublicFields(root, derived);
        } else {
            String newCacheId = InvokeServiceExecutor.storeInfotableInConversationCache(out, derived);
            root.put("resultKind", kindLarge);
            root.put("totalRows", rc);
            root.put("cacheId", newCacheId);
            ArrayNode sample = MAPPER.createArrayNode();
            for (int i = 0; i < Math.min(threshold, rc); i++) {
                sample.add(rowToObject(out, i, cols));
            }
            root.set("sampleRows", sample);
            ArrayNode colMeta = MAPPER.createArrayNode();
            for (String c : cols) {
                ObjectNode cn = MAPPER.createObjectNode();
                cn.put("name", c);
                colMeta.add(cn);
            }
            root.set("columns", colMeta);
            root.put("hint", InvokeServiceExecutor.FETCH_CACHED_LARGE_TABLE_HINT);
            attachInsightEnvelope(root, sourceCacheId, out);
            attachBp6PublicFields(root, derived);
        }
        LOG.info("tabulate_cached_result decision table ok sourceCacheId={} resultKind={} totalRows={}", sourceCacheId,
                root.get("resultKind").asText(), rc);
        return MAPPER.writeValueAsString(root);
    }

    private static int compareRowsByColumn(InfoTable src, String col, int ia, int ib, boolean desc) {
        return compareRowsByColumn(src, col, ia, ib, desc, true);
    }

    private static int compareRowsByColumn(InfoTable src, String col, int ia, int ib, boolean desc,
            boolean stringSortCaseSensitive) {
        ValueCollection ra = src.getRow(ia);
        ValueCollection rb = src.getRow(ib);
        BaseTypes bt = columnBaseType(src, col);
        int c = compareCellValues(ra, rb, col, bt, stringSortCaseSensitive);
        if (desc) {
            c = -c;
        }
        return c;
    }

    private static int compareCellValues(ValueCollection ra, ValueCollection rb, String col, BaseTypes bt,
            boolean stringSortCaseSensitive) {
        if (bt == BaseTypes.NUMBER || bt == BaseTypes.INTEGER || bt == BaseTypes.LONG) {
            Double da = ra != null ? extractDouble(ra, col) : null;
            Double db = rb != null ? extractDouble(rb, col) : null;
            if (da == null && db == null) {
                return 0;
            }
            if (da == null) {
                return 1;
            }
            if (db == null) {
                return -1;
            }
            return Double.compare(da, db);
        }
        if (bt == BaseTypes.DATETIME) {
            DateTime ta = ra != null ? extractDateTime(ra, col) : null;
            DateTime tb = rb != null ? extractDateTime(rb, col) : null;
            if (ta == null && tb == null) {
                return 0;
            }
            if (ta == null) {
                return 1;
            }
            if (tb == null) {
                return -1;
            }
            return Long.compare(ta.getMillis(), tb.getMillis());
        }
        String sa = ra != null ? cellToSortKey(ra, col) : "";
        String sb = rb != null ? cellToSortKey(rb, col) : "";
        if (!stringSortCaseSensitive && isStringLikeBaseType(bt)) {
            return sa.compareToIgnoreCase(sb);
        }
        return sa.compareTo(sb);
    }

    private static boolean isStringLikeBaseType(BaseTypes bt) {
        if (bt == null) {
            return false;
        }
        return bt == BaseTypes.STRING || bt == BaseTypes.TEXT || bt == BaseTypes.GUID || bt == BaseTypes.HTML
                || bt == BaseTypes.XML || bt.name().endsWith("NAME");
    }

    private static boolean isSortableColumnForDecisionModes(InfoTable src, String col) {
        BaseTypes bt = columnBaseType(src, col);
        if (bt == null) {
            return false;
        }
        if (bt == BaseTypes.JSON || bt == BaseTypes.TAGS || bt == BaseTypes.INFOTABLE) {
            return false;
        }
        return bt == BaseTypes.NUMBER || bt == BaseTypes.INTEGER || bt == BaseTypes.LONG || bt == BaseTypes.DATETIME
                || bt == BaseTypes.STRING || bt == BaseTypes.TEXT || bt == BaseTypes.GUID || bt == BaseTypes.HTML
                || bt == BaseTypes.XML || bt == BaseTypes.BOOLEAN;
    }

    private static boolean isSortableColumn(InfoTable src, String col) {
        BaseTypes bt = columnBaseType(src, col);
        if (bt == null) {
            return false;
        }
        return bt == BaseTypes.NUMBER || bt == BaseTypes.INTEGER || bt == BaseTypes.LONG || bt == BaseTypes.DATETIME
                || bt == BaseTypes.STRING || bt == BaseTypes.TEXT || bt == BaseTypes.GUID || bt == BaseTypes.HTML
                || bt == BaseTypes.XML || bt == BaseTypes.JSON || bt == BaseTypes.BOOLEAN;
    }

    private static boolean isAggregateableType(BaseTypes bt) {
        return bt == BaseTypes.NUMBER || bt == BaseTypes.INTEGER || bt == BaseTypes.LONG;
    }

    private static boolean isNumericType(BaseTypes bt) {
        return bt == BaseTypes.NUMBER || bt == BaseTypes.INTEGER || bt == BaseTypes.LONG;
    }

    private static boolean isCategoricalType(BaseTypes bt) {
        return bt == BaseTypes.STRING || bt == BaseTypes.TEXT || bt == BaseTypes.GUID || bt == BaseTypes.HTML
                || bt == BaseTypes.BOOLEAN;
    }

    private static BaseTypes fieldBaseType(DataShapeDefinition shape, String col) {
        if (shape == null || shape.getFields() == null) {
            return null;
        }
        FieldDefinition fd = shape.getFields().get(col);
        return fd != null ? fd.getBaseType() : null;
    }

    private static BaseTypes columnBaseType(InfoTable it, String col) {
        try {
            DataShapeDefinition ds = it.getDataShape();
            if (ds != null && ds.getFields() != null) {
                for (FieldDefinition f : ds.getFields().values()) {
                    if (col.equals(f.getName())) {
                        return f.getBaseType();
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private static String cellToSortKey(ValueCollection row, String col) {
        if (row == null) {
            return "";
        }
        Object v = row.getValue(col);
        if (v == null) {
            return "";
        }
        if (v instanceof IPrimitiveType) {
            try {
                Object inner = ((IPrimitiveType) v).getValue();
                return inner == null ? "" : String.valueOf(inner);
            } catch (Exception e) {
                return v.toString();
            }
        }
        return String.valueOf(v);
    }

    private static Double extractDouble(ValueCollection row, String col) {
        if (row == null) {
            return null;
        }
        Object v = row.getValue(col);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof IPrimitiveType) {
            try {
                if (v instanceof NumberPrimitive) {
                    return ((NumberPrimitive) v).getValue();
                }
                if (v instanceof IntegerPrimitive) {
                    return (double) ((IntegerPrimitive) v).getValue();
                }
                if (v instanceof LongPrimitive) {
                    return (double) ((LongPrimitive) v).getValue();
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static ObjectNode rowToObject(InfoTable it, int rowIndex, List<String> colNames) {
        ObjectNode o = MAPPER.createObjectNode();
        ValueCollection row = it.getRow(rowIndex);
        if (row == null) {
            return o;
        }
        for (String col : colNames) {
            if (ParlerInfotableJsonUtil.isPasswordColumn(it, col)) {
                o.put(col, ParlerInfotableJsonUtil.passwordColumnLlmPlaceholder());
            } else {
                o.set(col, valueToJson(row.getValue(col)));
            }
        }
        return o;
    }

    private static com.fasterxml.jackson.databind.JsonNode valueToJson(Object v) {
        if (v == null) {
            return MAPPER.getNodeFactory().nullNode();
        }
        if (v instanceof IPrimitiveType) {
            try {
                return primitiveValueToJson(((IPrimitiveType) v).getValue());
            } catch (Exception e) {
                return MAPPER.getNodeFactory().textNode(v.toString());
            }
        }
        return primitiveValueToJson(v);
    }

    private static com.fasterxml.jackson.databind.JsonNode primitiveValueToJson(Object v) {
        if (v == null) {
            return MAPPER.getNodeFactory().nullNode();
        }
        if (v instanceof String) {
            return MAPPER.getNodeFactory().textNode((String) v);
        }
        if (v instanceof Number) {
            double dv = ((Number) v).doubleValue();
            if (Double.isNaN(dv) || Double.isInfinite(dv)) {
                return MAPPER.getNodeFactory().nullNode();
            }
            if (v instanceof Integer || v instanceof Long) {
                return MAPPER.getNodeFactory().numberNode(((Number) v).longValue());
            }
            return MAPPER.getNodeFactory().numberNode(dv);
        }
        if (v instanceof Boolean) {
            return MAPPER.getNodeFactory().booleanNode((Boolean) v);
        }
        if (v instanceof DateTime) {
            return MAPPER.getNodeFactory().textNode(v.toString());
        }
        return MAPPER.getNodeFactory().textNode(String.valueOf(v));
    }

    static List<String> columnNames(InfoTable it) {
        List<String> names = new ArrayList<>();
        try {
            DataShapeDefinition ds = it.getDataShape();
            if (ds != null && ds.getFields() != null) {
                for (FieldDefinition f : ds.getFields().values()) {
                    if (f.getName() != null) {
                        names.add(f.getName());
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        if (names.isEmpty() && it.getRowCount() > 0) {
            ValueCollection row = it.getRow(0);
            if (row != null) {
                try {
                    for (String k : row.keySet()) {
                        names.add(k);
                    }
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
        return names;
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        return n.asText();
    }

    /**
     * B11: TOKEN sentinel unresolved — distinct code from generic {@code CACHE_MISS}, with
     * targeted recovery (retry using an explicit {@code cacheId} from a prior producer).
     */
    private static String lastTabularCacheUnavailableError() {
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", "LAST_TABULAR_CACHE_UNAVAILABLE");
            o.put("message",
                    "cacheId is the last-tabular sentinel but no qualifying tabular tool with a cacheId is available "
                            + "in this agent turn or recorded for this conversation (or the last qualifying tabular was "
                            + "inline-only). Use an explicit cacheId from a prior tool output.");
            ObjectNode rh = MAPPER.createObjectNode();
            rh.put("action", "retry_with_explicit_cacheId");
            rh.put("hint",
                    "Run a qualifying tabular producer that returns cacheId, then pass that cacheId literally "
                            + "(do not reuse the TOKEN sentinel until a new qualifying result is recorded).");
            o.set("recoveryHint", rh);
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return errorJson("LAST_TABULAR_CACHE_UNAVAILABLE",
                    "Last-tabular TOKEN unavailable; use an explicit cacheId.");
        }
    }

    private static String errorJson(String code, String message) {
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", code);
            o.put("message", message == null ? "" : message);
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"" + code + "\",\"message\":\"serialization failed\"}";
        }
    }

    private static String cacheFaultOrThrow(ArtifactCacheException e) {
        if (e.code() == ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE) {
            throw e;
        }
        return errorJson(e.code().name(), e.getMessage());
    }
}
