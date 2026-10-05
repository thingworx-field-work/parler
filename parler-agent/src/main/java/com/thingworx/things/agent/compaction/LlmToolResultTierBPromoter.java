package com.thingworx.things.agent.compaction;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * Tier B post-turn replay promotion (Phase 2 v1 subset): after the final assistant message is appended,
 * replaces historic {@code parler.infotable.matrix.v1} tool bodies, cohort-bundle inner matrix results,
 * and {@code get_entity} bodies stamped {@code parler.entity.metadata.v1} (messages strictly before the last user turn) with
 * compact {@code parler.infotable.summary.v1} or {@code parler.entity.metadata.summary.v1} envelopes.
 *
 * <p>Runs only when {@link com.thingworx.things.agent.AgentThing} Phase 2 replay compaction is enabled;
 * see {@code docs/agent/llm-token-budget.md} §Tier B. Evidence ledger is unchanged — this mutates LLM replay
 * copies only.</p>
 */
public final class LlmToolResultTierBPromoter {

    /** Joda-style / RFC 822 numeric offset without colon (e.g. {@code +0800}); not accepted by {@link DateTimeFormatter#ISO_OFFSET_DATE_TIME}. */
    private static final DateTimeFormatter OFFSET_DATE_TIME_RFC822 =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS][.SS][.S]Z");

    public static final String FORMAT_SUMMARY_V1 = "parler.infotable.summary.v1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmToolResultTierBPromoter() {}

    /**
     * @return number of tool-result bodies rewritten
     */
    public static int apply(List<ChatMessage> messages, Logger log) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int lastUser = lastUserMessageIndex(messages);
        if (lastUser < 0) {
            return 0;
        }
        int promoted = 0;
        int entityMetadataPromoted = 0;
        int skippedNoShrink = 0;
        long beforeChars = 0;
        long afterChars = 0;
        for (int i = 0; i < lastUser; i++) {
            ChatMessage m = messages.get(i);
            if (m.getRole() != ChatMessage.Role.TOOL) {
                continue;
            }
            String id = m.getToolCallId();
            String content = m.getContent();
            if (content == null || content.isEmpty()) {
                continue;
            }
            try {
                JsonNode root = MAPPER.readTree(content);
                if (!root.isObject()) {
                    continue;
                }
                ObjectNode obj = (ObjectNode) root;
                String fmt = text(obj, "$format");
                String toolName = findToolNameForCallId(messages, i, id);
                if (fmt != null) {
                    if (fmt.startsWith("parler.infotable.summary") || fmt.startsWith("parler.evidence.stub")
                            || EntityMetadataSummaryCodec.FORMAT_ENTITY_METADATA_SUMMARY_V1.equals(fmt)
                            || LlmToolResultCohortMerger.FORMAT_MEMBER.equals(fmt)) {
                        continue;
                    }
                    if (LlmToolResultCohortMerger.FORMAT_BUNDLE.equals(fmt)) {
                        int blen = content.length();
                        CohortPromoteOutcome cohort = maybePromoteCohortBundle(messages, i, obj, id, log);
                        if (cohort.promoted) {
                            promoted++;
                            beforeChars += blen;
                            afterChars += cohort.afterChars;
                        } else if (cohort.skippedNoShrink) {
                            skippedNoShrink++;
                        }
                        continue;
                    }
                    if (InfoTableMatrixCodec.FORMAT_MATRIX_V1.equals(fmt)) {
                        ShrinkPromoteOutcome matrixOutcome = tryPromoteWithShrinkGuard(messages, i, id, content,
                                toolName, matrixToSummaryJson(obj, toolName, id, log), log);
                        if (matrixOutcome.promoted) {
                            promoted++;
                            beforeChars += matrixOutcome.beforeLen;
                            afterChars += matrixOutcome.afterLen;
                        } else if (matrixOutcome.skippedNoShrink) {
                            skippedNoShrink++;
                        }
                        continue;
                    }
                }
                if (EntityMetadataSummaryCodec.isPromotableEntityMetadata(obj, toolName)) {
                    ShrinkPromoteOutcome entityOutcome = tryPromoteWithShrinkGuard(messages, i, id, content, toolName,
                            EntityMetadataSummaryCodec.toSummaryJson(obj, toolName, id), log);
                    if (entityOutcome.promoted) {
                        promoted++;
                        entityMetadataPromoted++;
                        beforeChars += entityOutcome.beforeLen;
                        afterChars += entityOutcome.afterLen;
                    } else if (entityOutcome.skippedNoShrink) {
                        skippedNoShrink++;
                    }
                }
            } catch (Exception e) {
                if (log != null) {
                    log.warn("LLM_TIER_B_SKIP reason=parse toolCallId={} msg={}", id, e.getMessage());
                }
            }
        }
        if ((promoted > 0 || skippedNoShrink > 0) && log != null && log.isInfoEnabled()) {
            log.info(
                    "LLM_TIER_B_PROMOTED promoted={} entityMetadataPromoted={} beforeChars={} afterChars={} "
                            + "skippedNoShrink={} lastUserIdx={}",
                    promoted, entityMetadataPromoted, beforeChars, afterChars, skippedNoShrink, lastUser);
        }
        return promoted;
    }

    private static final class ShrinkPromoteOutcome {
        final boolean promoted;
        final boolean skippedNoShrink;
        final int beforeLen;
        final int afterLen;

        ShrinkPromoteOutcome(boolean promoted, boolean skippedNoShrink, int beforeLen, int afterLen) {
            this.promoted = promoted;
            this.skippedNoShrink = skippedNoShrink;
            this.beforeLen = beforeLen;
            this.afterLen = afterLen;
        }

        static ShrinkPromoteOutcome notPromoted() {
            return new ShrinkPromoteOutcome(false, false, 0, 0);
        }

        static ShrinkPromoteOutcome skippedNoShrink(int beforeLen, int afterLen) {
            return new ShrinkPromoteOutcome(false, true, beforeLen, afterLen);
        }

        static ShrinkPromoteOutcome promoted(int beforeLen, int afterLen) {
            return new ShrinkPromoteOutcome(true, false, beforeLen, afterLen);
        }
    }

    private static ShrinkPromoteOutcome tryPromoteWithShrinkGuard(List<ChatMessage> messages, int msgIdx,
            String toolCallId, String originalContent, String toolName, String summaryJson, Logger log) {
        if (summaryJson == null) {
            return ShrinkPromoteOutcome.notPromoted();
        }
        int beforeLen = originalContent.length();
        int newLen = summaryJson.length();
        if (newLen >= beforeLen) {
            if (log != null && log.isInfoEnabled()) {
                log.info("LLM_TIER_B_SKIP reason=no_shrink toolCallId={} beforeChars={} afterChars={} toolName={}",
                        toolCallId, beforeLen, newLen, toolName != null ? toolName : "");
            }
            return ShrinkPromoteOutcome.skippedNoShrink(beforeLen, newLen);
        }
        messages.set(msgIdx, ChatMessage.toolResult(toolCallId, summaryJson));
        return ShrinkPromoteOutcome.promoted(beforeLen, newLen);
    }

    private static final class CohortPromoteOutcome {
        final boolean promoted;
        final boolean skippedNoShrink;
        final int afterChars;

        CohortPromoteOutcome(boolean promoted, boolean skippedNoShrink, int afterChars) {
            this.promoted = promoted;
            this.skippedNoShrink = skippedNoShrink;
            this.afterChars = afterChars;
        }

        static CohortPromoteOutcome notPromoted() {
            return new CohortPromoteOutcome(false, false, 0);
        }

        static CohortPromoteOutcome promoted(int afterChars) {
            return new CohortPromoteOutcome(true, false, afterChars);
        }

        static CohortPromoteOutcome skippedNoShrink() {
            return new CohortPromoteOutcome(false, true, 0);
        }
    }

    private static CohortPromoteOutcome maybePromoteCohortBundle(List<ChatMessage> messages, int msgIdx, ObjectNode bundle,
            String toolCallId, Logger log) {
        String originalContent = messages.get(msgIdx).getContent();
        int beforeLen = originalContent != null ? originalContent.length() : 0;
        JsonNode inner = bundle.get("result");
        if (inner == null || !inner.isObject()) {
            return CohortPromoteOutcome.notPromoted();
        }
        ObjectNode innerObj = (ObjectNode) inner;
        if (!InfoTableMatrixCodec.FORMAT_MATRIX_V1.equals(text(innerObj, "$format"))) {
            return CohortPromoteOutcome.notPromoted();
        }
        String toolName = text(bundle, "toolName");
        if (toolName == null || toolName.isEmpty()) {
            toolName = findToolNameForCallId(messages, msgIdx, toolCallId);
        }
        ObjectNode summary = buildSummaryFromMatrix(innerObj, toolName, toolCallId);
        if (summary == null) {
            if (log != null) {
                log.warn("LLM_TIER_B_COHORT_UNCHANGED reason=summary_unsafe toolCallId={}", toolCallId);
            }
            return CohortPromoteOutcome.notPromoted();
        }
        String cohortDim = text(bundle, "cohortDimension");
        if (cohortDim == null || cohortDim.isEmpty()) {
            cohortDim = cohortDimensionFromColumns(innerObj.get("columns"));
        }
        JsonNode members = bundle.get("members");
        boolean omitMemberSummaries = hasNonEmptyCacheId(innerObj);
        if (!omitMemberSummaries && cohortDim != null && members != null && members.isArray() && members.size() > 0) {
            summary.put("cohortDimension", cohortDim);
            ArrayNode ms = memberSummariesFromMatrix(innerObj, cohortDim, (ArrayNode) members);
            if (ms != null && ms.size() > 0) {
                summary.set("memberSummaries", ms);
            }
        } else if (cohortDim != null && !cohortDim.isEmpty()) {
            summary.put("cohortDimension", cohortDim);
        }
        bundle.set("result", summary);
        String newContent;
        try {
            newContent = MAPPER.writeValueAsString(bundle);
        } catch (Exception e) {
            bundle.set("result", innerObj);
            if (log != null) {
                log.warn("LLM_TIER_B_COHORT_UNCHANGED reason=serialize toolCallId={}", toolCallId);
            }
            return CohortPromoteOutcome.notPromoted();
        }
        int newLen = newContent.length();
        if (newLen >= beforeLen) {
            bundle.set("result", innerObj);
            if (log != null && log.isInfoEnabled()) {
                log.info("LLM_TIER_B_SKIP reason=no_shrink toolCallId={} beforeChars={} afterChars={}",
                        toolCallId, beforeLen, newLen);
            }
            return CohortPromoteOutcome.skippedNoShrink();
        }
        messages.set(msgIdx, ChatMessage.toolResult(toolCallId, newContent));
        return CohortPromoteOutcome.promoted(newLen);
    }

    private static String cohortDimensionFromColumns(JsonNode cols) {
        if (cols == null || !cols.isArray() || cols.size() == 0) {
            return null;
        }
        JsonNode c0 = cols.get(0);
        if (c0 != null && c0.isObject()) {
            return text(c0, "name");
        }
        return null;
    }

    /**
     * One summary row per {@code bundle.members[]} entry, in member order, with {@code memberIndex} matching
     * the bundle's stable member index.
     */
    private static ArrayNode memberSummariesFromMatrix(ObjectNode matrix, String dimColName, ArrayNode bundleMembers) {
        String rowKey = InfoTableMatrixCodec.resolvePrimaryTabularRowsKey(matrix);
        JsonNode rows = rowKey != null ? matrix.get(rowKey) : null;
        JsonNode cols = matrix.get("columns");
        if (cols == null || rows == null || !rows.isArray() || !cols.isArray()) {
            return null;
        }
        int dimIdx = -1;
        for (int i = 0; i < cols.size(); i++) {
            JsonNode c = cols.get(i);
            if (c != null && c.isObject() && dimColName.equals(text(c, "name"))) {
                dimIdx = i;
                break;
            }
        }
        if (dimIdx < 0) {
            return null;
        }
        ArrayNode out = MAPPER.createArrayNode();
        for (int mi = 0; mi < bundleMembers.size(); mi++) {
            JsonNode mem = bundleMembers.get(mi);
            if (mem == null || !mem.isObject()) {
                continue;
            }
            int memberIndex = mem.path("memberIndex").asInt(mi);
            JsonNode args = mem.get("args");
            String dimVal = "";
            if (args != null && args.isObject()) {
                String v = text((ObjectNode) args, dimColName);
                if (v != null) {
                    dimVal = v;
                }
            }
            int count = 0;
            if (!dimVal.isEmpty()) {
                for (JsonNode row : rows) {
                    if (!row.isArray() || dimIdx >= row.size()) {
                        continue;
                    }
                    JsonNode cell = row.get(dimIdx);
                    String v = cellScalarLabel(cell);
                    if (!"(complex)".equals(v) && dimVal.equals(v)) {
                        count++;
                    }
                }
            }
            ObjectNode o = MAPPER.createObjectNode();
            o.put("memberIndex", memberIndex);
            o.put("dimensionValue", dimVal);
            o.put("rowCount", count);
            out.add(o);
        }
        return out.size() > 0 ? out : null;
    }

    private static String matrixToSummaryJson(ObjectNode matrix, String toolName, String toolCallId, Logger log) {
        ObjectNode s = buildSummaryFromMatrix(matrix, toolName, toolCallId);
        if (s == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(s);
        } catch (Exception e) {
            if (log != null) {
                log.warn("LLM_TIER_B_SKIP reason=summary_serialize toolCallId={} msg={}", toolCallId, e.getMessage());
            }
            return null;
        }
    }

    private static ObjectNode buildSummaryFromMatrix(ObjectNode matrix, String toolName, String toolCallId) {
        String rowKey = InfoTableMatrixCodec.resolvePrimaryTabularRowsKey(matrix);
        JsonNode rows = rowKey != null ? matrix.get(rowKey) : null;
        JsonNode cols = matrix.get("columns");
        if (rows == null || !rows.isArray() || rows.size() == 0 || cols == null || !cols.isArray() || cols.isEmpty()) {
            return null;
        }
        int rowCount = rows.size();
        ObjectNode out = MAPPER.createObjectNode();
        out.put("$format", FORMAT_SUMMARY_V1);
        out.put("status", "success");
        out.put("originalToolName", toolName != null ? toolName : "unknown_tool");
        out.put("originalToolCallId", toolCallId);
        if (matrix.has("resultKind") && !matrix.get("resultKind").isNull()) {
            out.set("resultKind", matrix.get("resultKind").deepCopy());
        } else {
            out.put("resultKind", "INFOTABLE");
        }
        out.put("rowCount", rowCount);
        JsonNode totalCountNode = pickSummaryTotalCountNode(matrix, rowCount);
        out.set("totalCount", totalCountNode.deepCopy());
        long authoritativeTotal = totalCountNode.isNumber() ? totalCountNode.longValue() : rowCount;
        if (matrix.has("cacheId") && !matrix.get("cacheId").isNull()) {
            out.set("cacheId", matrix.get("cacheId").deepCopy());
        } else {
            out.putNull("cacheId");
        }
        out.putArray("sourceCacheIds");
        boolean srcSample = matrix.path("sampleOnly").asBoolean(false);
        boolean sampleKey = "sampleRows".equals(rowKey) || "sampleRootEntityList".equals(rowKey);
        boolean totalGtRows = authoritativeTotal > rowCount;
        out.put("sampleOnly", srcSample || sampleKey || totalGtRows);
        JsonNode constants = matrix.get("constants");
        if (constants != null && constants.isObject() && constants.size() > 0) {
            out.set("constants", constants.deepCopy());
        }
        if (hasNonEmptyCacheId(matrix)) {
            applyCacheIdOnlyColumns(out, cols);
            return out;
        }
        ColStatsResult stats = buildColumnStats(cols, rows);
        if (stats == null) {
            return null;
        }
        if (stats.columns.isEmpty() && !stats.protectedOmissions) {
            return null;
        }
        out.put("protectedOmissions", stats.protectedOmissions);
        out.set("columns", stats.columns);
        return out;
    }

    private static boolean hasNonEmptyCacheId(ObjectNode matrix) {
        JsonNode c = matrix.get("cacheId");
        if (c == null || c.isNull()) {
            return false;
        }
        String s = c.asText("");
        return !s.isEmpty();
    }

    /**
     * Tier B v1.5: when {@code cacheId} is set, emit schema-only columns (name + baseType) and omit
     * stats so the LLM treats the summary as a re-query pointer.
     */
    private static void applyCacheIdOnlyColumns(ObjectNode out, JsonNode cols) {
        ArrayNode minCols = MAPPER.createArrayNode();
        int pwd = 0;
        for (int ci = 0; ci < cols.size(); ci++) {
            JsonNode cdef = cols.get(ci);
            if (cdef == null || !cdef.isObject()) {
                continue;
            }
            String name = text(cdef, "name");
            String bt = text(cdef, "baseType");
            if (name == null) {
                continue;
            }
            if (bt != null && "PASSWORD".equalsIgnoreCase(bt)) {
                pwd++;
                continue;
            }
            ObjectNode c = MAPPER.createObjectNode();
            c.put("name", name);
            c.put("baseType", bt != null ? bt : "STRING");
            minCols.add(c);
        }
        out.put("protectedOmissions", pwd > 0);
        out.set("columns", minCols);
    }

    /**
     * Precedence: authoritative {@code totalCount} from the matrix envelope (taxonomy / large
     * results), then {@code totalRows}, then in-matrix {@code rowCount}, else the resolved row-array length.
     */
    private static JsonNode pickSummaryTotalCountNode(ObjectNode matrix, int rowArrayLen) {
        if (matrix.has("totalCount") && matrix.get("totalCount").isNumber()) {
            return matrix.get("totalCount");
        }
        if (matrix.has("totalRows") && matrix.get("totalRows").isNumber()) {
            return matrix.get("totalRows");
        }
        if (matrix.has("rowCount") && matrix.get("rowCount").isNumber()) {
            return matrix.get("rowCount");
        }
        return MAPPER.getNodeFactory().numberNode(rowArrayLen);
    }

    private static final class ColStatsResult {
        final ArrayNode columns;
        final boolean protectedOmissions;

        ColStatsResult(ArrayNode columns, boolean protectedOmissions) {
            this.columns = columns;
            this.protectedOmissions = protectedOmissions;
        }
    }

    private static ColStatsResult buildColumnStats(JsonNode cols, JsonNode rows) {
        ArrayNode out = MAPPER.createArrayNode();
        int omittedProtected = 0;
        int n = cols.size();
        for (int ci = 0; ci < n; ci++) {
            JsonNode cdef = cols.get(ci);
            if (cdef == null || !cdef.isObject()) {
                continue;
            }
            String name = text(cdef, "name");
            String bt = text(cdef, "baseType");
            if (name == null) {
                continue;
            }
            if (bt != null && "PASSWORD".equalsIgnoreCase(bt)) {
                omittedProtected++;
                continue;
            }
            ObjectNode col = MAPPER.createObjectNode();
            col.put("name", name);
            col.put("baseType", bt != null ? bt : "STRING");
            if (isDateTimeBaseType(bt)) {
                DateTimeRange dr = summarizeDateTimeColumn(rows, ci);
                if (dr != null) {
                    ArrayNode range = MAPPER.createArrayNode();
                    range.add(dr.minIso);
                    range.add(dr.maxIso);
                    col.set("range", range);
                }
            } else if (isNumericBaseType(bt)) {
                ColNumStats ds = summarizeNumericColumn(rows, ci);
                if (ds != null) {
                    ObjectNode ns = MAPPER.createObjectNode();
                    ns.put("min", ds.min);
                    ns.put("max", ds.max);
                    ns.put("mean", ds.mean);
                    col.set("numericStats", ns);
                }
            } else {
                CardinalityTop ct = summarizeStringColumn(rows, ci, 5);
                col.put("cardinality", ct.cardinality);
                if (!ct.top.isEmpty()) {
                    col.set("top", ct.top);
                }
            }
            out.add(col);
        }
        if (out.isEmpty()) {
            if (omittedProtected > 0) {
                return new ColStatsResult(out, true);
            }
            return null;
        }
        return new ColStatsResult(out, omittedProtected > 0);
    }

    private static boolean isNumericBaseType(String bt) {
        if (bt == null) {
            return false;
        }
        String u = bt.toUpperCase();
        return "NUMBER".equals(u) || "INTEGER".equals(u) || "LONG".equals(u) || "INT".equals(u);
    }

    private static boolean isDateTimeBaseType(String bt) {
        if (bt == null) {
            return false;
        }
        return "DATETIME".equalsIgnoreCase(bt);
    }

    private static final class DateTimeRange {
        final String minIso;
        final String maxIso;

        DateTimeRange(String minIso, String maxIso) {
            this.minIso = minIso;
            this.maxIso = maxIso;
        }
    }

    private static final class ParsedDateTimeCell {
        final String original;
        final Instant instant;

        ParsedDateTimeCell(String original, Instant instant) {
            this.original = original;
            this.instant = instant;
        }
    }

    /**
     * Chronological min/max using {@link Instant} comparison; original wire strings are preserved at the ends.
     * If any non-skipped cell fails parsing, returns {@code null}.
     */
    private static DateTimeRange summarizeDateTimeColumn(JsonNode rows, int colIndex) {
        List<ParsedDateTimeCell> parsed = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!row.isArray() || colIndex >= row.size()) {
                continue;
            }
            JsonNode cell = row.get(colIndex);
            String s = cellScalarLabel(cell);
            if ("(null)".equals(s) || "(complex)".equals(s)) {
                continue;
            }
            Instant inst = parseDateTimeToInstant(s);
            if (inst == null) {
                return null;
            }
            parsed.add(new ParsedDateTimeCell(s, inst));
        }
        if (parsed.isEmpty()) {
            return null;
        }
        Instant minI = null;
        Instant maxI = null;
        for (ParsedDateTimeCell p : parsed) {
            if (minI == null || p.instant.isBefore(minI)) {
                minI = p.instant;
            }
            if (maxI == null || p.instant.isAfter(maxI)) {
                maxI = p.instant;
            }
        }
        if (minI.equals(maxI)) {
            String one = pickLexicographicallyMinimalOriginal(parsed, minI);
            return new DateTimeRange(one, one);
        }
        return new DateTimeRange(pickLexicographicallyMinimalOriginal(parsed, minI),
                pickLexicographicallyMaximalOriginal(parsed, maxI));
    }

    private static String pickLexicographicallyMinimalOriginal(List<ParsedDateTimeCell> parsed, Instant target) {
        String best = null;
        for (ParsedDateTimeCell p : parsed) {
            if (!p.instant.equals(target)) {
                continue;
            }
            if (best == null || p.original.compareTo(best) < 0) {
                best = p.original;
            }
        }
        return best;
    }

    private static String pickLexicographicallyMaximalOriginal(List<ParsedDateTimeCell> parsed, Instant target) {
        String best = null;
        for (ParsedDateTimeCell p : parsed) {
            if (!p.instant.equals(target)) {
                continue;
            }
            if (best == null || p.original.compareTo(best) > 0) {
                best = p.original;
            }
        }
        return best;
    }

    static Instant parseDateTimeToInstant(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(t).toInstant();
        } catch (DateTimeParseException e) {
            // continue
        }
        try {
            return OffsetDateTime.parse(t, OFFSET_DATE_TIME_RFC822).toInstant();
        } catch (DateTimeParseException e) {
            // continue
        }
        try {
            return ZonedDateTime.parse(t).toInstant();
        } catch (DateTimeParseException e) {
            // continue
        }
        try {
            return Instant.parse(t);
        } catch (DateTimeParseException e) {
            // continue
        }
        try {
            return LocalDateTime.parse(t, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atOffset(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            // continue
        }
        try {
            return LocalDate.parse(t, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static final class ColNumStats {
        final double min;
        final double max;
        final double mean;

        ColNumStats(double min, double max, double mean) {
            this.min = min;
            this.max = max;
            this.mean = mean;
        }
    }

    private static ColNumStats summarizeNumericColumn(JsonNode rows, int colIndex) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double sum = 0;
        int cnt = 0;
        for (JsonNode row : rows) {
            if (!row.isArray() || colIndex >= row.size()) {
                continue;
            }
            JsonNode cell = row.get(colIndex);
            if (cell == null || cell.isNull() || !cell.isNumber()) {
                continue;
            }
            double v = cell.asDouble();
            min = Math.min(min, v);
            max = Math.max(max, v);
            sum += v;
            cnt++;
        }
        if (cnt == 0) {
            return null;
        }
        return new ColNumStats(min, max, sum / cnt);
    }

    private static final class CardinalityTop {
        final int cardinality;
        final ArrayNode top;

        CardinalityTop(int cardinality, ArrayNode top) {
            this.cardinality = cardinality;
            this.top = top;
        }
    }

    private static CardinalityTop summarizeStringColumn(JsonNode rows, int colIndex, int topK) {
        Map<String, Integer> freq = new HashMap<>();
        for (JsonNode row : rows) {
            if (!row.isArray() || colIndex >= row.size()) {
                continue;
            }
            JsonNode cell = row.get(colIndex);
            String s = cellScalarLabel(cell);
            freq.merge(s, 1, Integer::sum);
        }
        ArrayNode top = MAPPER.createArrayNode();
        freq.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(topK)
                .forEach(e -> {
                    ArrayNode pair = MAPPER.createArrayNode();
                    pair.add(e.getKey());
                    pair.add(e.getValue());
                    top.add(pair);
                });
        return new CardinalityTop(freq.size(), top);
    }

    /**
     * String-bucket label for matrix cells: textual / number / boolean use {@link JsonNode#asText()}; null uses
     * {@code (null)}; arrays / objects use {@code (complex)}.
     */
    static String cellScalarLabel(JsonNode cell) {
        if (cell == null || cell.isNull()) {
            return "(null)";
        }
        if (cell.isContainerNode()) {
            return "(complex)";
        }
        return cell.asText();
    }

    private static int lastUserMessageIndex(List<ChatMessage> messages) {
        int last = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getRole() == ChatMessage.Role.USER) {
                last = i;
            }
        }
        return last;
    }

    private static String findToolNameForCallId(List<ChatMessage> messages, int toolIdx, String toolCallId) {
        for (int j = toolIdx - 1; j >= 0; j--) {
            ChatMessage m = messages.get(j);
            if (m.getRole() == ChatMessage.Role.ASSISTANT && m.hasToolCalls()) {
                for (ToolCall t : m.getToolCalls()) {
                    if (toolCallId.equals(t.getId())) {
                        return t.getFunctionName() != null ? t.getFunctionName() : "unknown_tool";
                    }
                }
            }
        }
        return "unknown_tool";
    }

    private static String text(JsonNode o, String field) {
        JsonNode n = o.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isTextual()) {
            return n.asText();
        }
        if (n.isNumber() || n.isBoolean()) {
            return n.asText();
        }
        return null;
    }
}
