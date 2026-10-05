package com.thingworx.things.agent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.source.ReadLimitFact;

/**
 * First-pass tool result egress boundary before successful tool JSON is appended to LLM replay.
 */
public final class ToolResultEgressGateway {

    static final int LLM_EVIDENCE_CHARS_SOFT_CAP = 8_192;
    /** Multi-Thing alert summary rollup — preserved by egress (see {@link #compactForLlmAppend}). */
    public static final String RESULT_KIND_ALERT_SUMMARY_MULTI = "ALERT_SUMMARY_MULTI";
    /**
     * Single first-party tabular/list evidence knob: gateway array sampling, inline-vs-large row decisions, and legacy
     * invoke/fetch/cached-tabular compatibility thresholds all derive from this value.
     */
    static final int ARRAY_SAMPLE_LIMIT = 20;
    private static final int LARGE_TEXT_EXCERPT_CHARS = 2_000;
    private static final int HARD_TEXT_EXCERPT_CHARS = 512;
    private static final int LAST_RESORT_TARGET_CHARS = 6_000;

    private static final List<String> PRIORITY_FIELDS = List.of(
            "status", "code", "resultKind", "thingName", "entityName", "propertyName", "serviceName",
            "cacheId", "totalRows", "returnedRows", "sampleOnly", "rowsOmitted", "columns", "counts",
            "aggregates", "warnings", "hint", "message", "completeness", "thingsRequested", "thingsSucceeded",
            "thingsFailedIdentity", "thingsFailedService", "byThing", "identityErrors",
            // U3E G18 additive typed error fields — keep through last-resort compaction (EG6 align).
            "category", "reason", "retryable", "retryBudgetKey", "recoveryActions", "evidenceStillUsable");

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolResultEgressGateway() {}

    /**
     * Longest text value the error-envelope compaction passes through unchanged. Diagnostics that must
     * reach the model as exact identifiers (CM-2 column names) treat longer strings as unrepresentable.
     */
    public static int hardTextExcerptChars() {
        return HARD_TEXT_EXCERPT_CHARS;
    }

    /** Shared LLM evidence sample limit for first-party tabular/list tool results. */
    public static int llmArraySampleLimit() {
        return ARRAY_SAMPLE_LIMIT;
    }

    /** True when a tabular/list result should use cache/sample evidence instead of inline rows. */
    public static boolean isLargeTabularResult(int rowCount) {
        return rowCount > ARRAY_SAMPLE_LIMIT;
    }

    public static TabularEvidencePlan planTabularEvidence(
            int rowCount,
            String inlineResultKind,
            String largeResultKind,
            String inlineRowsKey,
            String sampleRowsKey) {
        boolean large = isLargeTabularResult(rowCount);
        return new TabularEvidencePlan(
                Math.max(0, rowCount),
                large,
                large ? largeResultKind : inlineResultKind,
                large ? sampleRowsKey : inlineRowsKey,
                large ? Math.min(Math.max(0, rowCount), ARRAY_SAMPLE_LIMIT) : Math.max(0, rowCount));
    }

    public static void applyTabularEvidence(
            ObjectNode out,
            TabularEvidencePlan plan,
            ArrayNode rows,
            String cacheId,
            String hint) {
        if (out == null || plan == null) {
            return;
        }
        out.put("resultKind", plan.resultKind());
        out.set(plan.rowsKey(), rows != null ? rows : JSON.createArrayNode());
        if (plan.large()) {
            if (cacheId != null && !cacheId.isBlank()) {
                out.put("cacheId", cacheId);
            }
            if (hint != null && !hint.isBlank()) {
                out.put("hint", hint);
            }
        }
    }

    public static EgressResult compactForLlmAppend(String toolName, String toolCallId, String rawContent, Logger log) {
        return compactForLlmAppend(toolName, toolCallId, rawContent, log, List.of());
    }

    /**
     * Compact tool JSON for LLM replay. When {@code dataClassification} is non-empty (G13 / SPR-5),
     * stamps {@code _egress.dataClassification} onto object results so classification is consumed at
     * the egress boundary (not snapshot-only).
     */
    public static EgressResult compactForLlmAppend(
            String toolName,
            String toolCallId,
            String rawContent,
            Logger log,
            List<String> dataClassification) {
        String raw = rawContent != null ? rawContent : "";
        EgressResult out;
        if (raw.length() <= LLM_EVIDENCE_CHARS_SOFT_CAP && raw.indexOf('[') < 0) {
            out = EgressResult.unchanged(raw);
        } else {
            try {
                JsonNode root = JSON.readTree(raw);
                if (isErrorEnvelope(root)) {
                    out = compactErrorEnvelope(root, raw);
                } else {
                    out = compactJson(root, raw);
                }
            } catch (Exception parseFailure) {
                out = raw.length() > LLM_EVIDENCE_CHARS_SOFT_CAP ? compactText(raw) : EgressResult.unchanged(raw);
            }
        }

        out = stampDataClassification(out, dataClassification);

        if (log != null && out.isCompacted()) {
            log.info("TOOL_RESULT_EGRESS toolName={} callId={} rawReplayChars={} replayChars={} compactRatio={}",
                    safe(toolName), safe(toolCallId), out.getRawChars(), out.getLlmContent().length(),
                    String.format(Locale.ROOT, "%.4f", out.getCompactRatio()));
        }
        return out;
    }

    /** Stamp declared capability classifications onto JSON object egress metadata when present. */
    static EgressResult stampDataClassification(EgressResult out, List<String> dataClassification) {
        if (out == null || dataClassification == null || dataClassification.isEmpty()) {
            return out;
        }
        String content = out.getLlmContent();
        if (content == null || content.isBlank()) {
            return out;
        }
        try {
            JsonNode root = JSON.readTree(content);
            if (root == null || !root.isObject()) {
                return out;
            }
            ObjectNode obj = (ObjectNode) root;
            ObjectNode egress;
            if (obj.has("_egress") && obj.get("_egress").isObject()) {
                egress = (ObjectNode) obj.get("_egress");
            } else {
                egress = JSON.createObjectNode();
            }
            ArrayNode arr = JSON.createArrayNode();
            for (String c : dataClassification) {
                if (c != null && !c.isBlank()) {
                    arr.add(c.trim());
                }
            }
            if (arr.isEmpty()) {
                return out;
            }
            egress.set("dataClassification", arr);
            obj.set("_egress", egress);
            String stamped = JSON.writeValueAsString(obj);
            return out.isCompacted()
                    ? EgressResult.compacted(stamped, out.getRawChars())
                    : EgressResult.classified(stamped, out.getRawChars());
        } catch (Exception ignored) {
            return out;
        }
    }

    private static EgressResult compactJson(JsonNode root, String raw) throws Exception {
        ObjectNode markers = JSON.createObjectNode();
        String rootResultKind = rootResultKind(root);
        JsonNode compactRoot = compactNode(root, null, ARRAY_SAMPLE_LIMIT, LARGE_TEXT_EXCERPT_CHARS, markers,
                rootResultKind);
        String compact = JSON.writeValueAsString(compactRoot);
        if (compact.length() > LLM_EVIDENCE_CHARS_SOFT_CAP) {
            markers.removeAll();
            compactRoot = compactNode(root, null, Math.max(1, ARRAY_SAMPLE_LIMIT / 4), HARD_TEXT_EXCERPT_CHARS,
                    markers, rootResultKind);
            compact = JSON.writeValueAsString(compactRoot);
        }
        if (compact.length() > LLM_EVIDENCE_CHARS_SOFT_CAP) {
            markers.removeAll();
            compactRoot = compactLastResort(root, markers, rootResultKind);
            compact = JSON.writeValueAsString(compactRoot);
        }
        if (compactRoot.isObject() && markers.size() > 0) {
            ObjectNode obj = (ObjectNode) compactRoot;
            applyTopLevelMarkers(obj, markers, raw.length());
            compact = JSON.writeValueAsString(obj);
        } else if (markers.size() > 0) {
            ObjectNode wrapper = JSON.createObjectNode();
            wrapper.put("status", statusText(root));
            wrapper.put("resultKind", "LARGE_JSON_COMPACT");
            wrapper.set("sample", compactRoot);
            applyTopLevelMarkers(wrapper, markers, raw.length());
            compact = JSON.writeValueAsString(wrapper);
        }
        if (compact.length() >= raw.length()) {
            return EgressResult.unchanged(raw);
        }
        return EgressResult.compacted(compact, raw.length());
    }

    private static EgressResult compactErrorEnvelope(JsonNode root, String raw) throws Exception {
        ObjectNode markers = JSON.createObjectNode();
        JsonNode compactRoot = compactNode(root, null, Math.max(1, ARRAY_SAMPLE_LIMIT / 4), HARD_TEXT_EXCERPT_CHARS,
                markers, rootResultKind(root));
        if (compactRoot.isObject() && markers.size() > 0) {
            applyTopLevelMarkers((ObjectNode) compactRoot, markers, raw.length());
        }
        String compact = JSON.writeValueAsString(compactRoot);
        return compact.length() < raw.length()
                ? EgressResult.compacted(compact, raw.length())
                : EgressResult.unchanged(raw);
    }

    private static EgressResult compactText(String raw) {
        ObjectNode root = JSON.createObjectNode();
        root.put("status", "success");
        root.put("resultKind", "LARGE_TEXT_COMPACT");
        root.put("contentExcerpt", excerpt(raw, LARGE_TEXT_EXCERPT_CHARS));
        root.put("truncated", true);
        root.put("rawChars", raw.length());
        try {
            return EgressResult.compacted(JSON.writeValueAsString(root), raw.length());
        } catch (Exception e) {
            return EgressResult.unchanged(raw);
        }
    }

    private static JsonNode compactNode(JsonNode node, String fieldName, int arraySampleLimit, int textLimit,
            ObjectNode markers, String rootResultKind) {
        if (node == null || node.isNull() || node.isMissingNode() || node.isNumber() || node.isBoolean()) {
            return node;
        }
        if (node.isTextual()) {
            String v = node.asText("");
            if (v.length() <= textLimit) {
                return node;
            }
            markers.put("truncatedTextFields", true);
            return JSON.getNodeFactory().textNode(excerpt(v, textLimit));
        }
        if (node.isArray()) {
            ArrayNode out = JSON.createArrayNode();
            boolean sample = shouldSampleArrayField(fieldName, rootResultKind)
                    && !isBoundedReadLimitSeries(fieldName, node);
            int limit = sample ? Math.min(node.size(), arraySampleLimit) : node.size();
            for (int i = 0; i < limit; i++) {
                out.add(compactNode(node.get(i), null, arraySampleLimit, textLimit, markers, rootResultKind));
            }
            return out;
        }
        if (node.isObject()) {
            ObjectNode out = JSON.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                JsonNode child = e.getValue();
                if (child != null && child.isArray() && shouldSampleArrayField(e.getKey(), rootResultKind)
                        && !isBoundedReadLimitSeries(e.getKey(), child) && child.size() > arraySampleLimit) {
                    ObjectNode fieldMarker = JSON.createObjectNode();
                    fieldMarker.put("originalCount", child.size());
                    fieldMarker.put("returnedCount", arraySampleLimit);
                    markers.set(e.getKey(), fieldMarker);
                }
                out.set(e.getKey(), compactNode(child, e.getKey(), arraySampleLimit, textLimit, markers,
                        rootResultKind));
            }
            return out;
        }
        return node;
    }

    private static boolean shouldSampleArrayField(String fieldName, String rootResultKind) {
        if (RESULT_KIND_ALERT_SUMMARY_MULTI.equals(rootResultKind)
                && ("byThing".equals(fieldName) || "identityErrors".equals(fieldName))) {
            return false;
        }
        return !isSchemaOrMetadataArrayField(fieldName);
    }

    /**
     * TR-0 reader-owned read-limit evidence: the only place a result tells the model it may be incomplete, so a
     * wide schema must not push it out of last-resort compaction. Eligibility is by name <b>and</b> value shape,
     * because the shape is what bounds it: a boolean flag, a text note (the text cap applies), and a list of at
     * most {@code HISTORY_OVERLAY_MAX_SERIES} text labels (the text cap applies to each). Any other value under
     * these names is ordinary data and takes the ordinary sampling, budget and omission rules.
     */
    private static boolean isReadLimitEvidence(String fieldName, JsonNode value) {
        if (fieldName == null || value == null) {
            return false;
        }
        if (ReadLimitFact.FIELD_REACHED.equals(fieldName)) {
            return value.isBoolean();
        }
        if (ReadLimitFact.FIELD_NOTE.equals(fieldName)) {
            return value.isTextual();
        }
        return isBoundedReadLimitSeries(fieldName, value);
    }

    private static boolean isBoundedReadLimitSeries(String fieldName, JsonNode value) {
        if (!ReadLimitFact.FIELD_REACHED_SERIES.equals(fieldName) || value == null || !value.isArray()
                || value.size() > HistoryOverlayChartBuilder.HISTORY_OVERLAY_MAX_SERIES) {
            return false;
        }
        for (JsonNode label : value) {
            if (!label.isTextual()) {
                return false;
            }
        }
        return true;
    }

    private static boolean keepsThroughLastResort(String fieldName, JsonNode value) {
        return PRIORITY_FIELDS.contains(fieldName) || isReadLimitEvidence(fieldName, value);
    }

    private static String rootResultKind(JsonNode root) {
        if (root != null && root.isObject() && root.has("resultKind")) {
            return root.path("resultKind").asText("");
        }
        return "";
    }

    private static boolean isSchemaOrMetadataArrayField(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) {
            return false;
        }
        return "columns".equals(fieldName)
                || "dataShape".equals(fieldName)
                || "fieldDefinitions".equals(fieldName);
    }

    private static JsonNode compactLastResort(JsonNode root, ObjectNode markers, String rootResultKind) {
        if (root == null || root.isNull()) {
            return root;
        }
        if (root.isArray()) {
            ObjectNode wrapper = JSON.createObjectNode();
            wrapper.put("status", "success");
            wrapper.put("resultKind", "LARGE_JSON_COMPACT");
            ArrayNode sample = JSON.createArrayNode();
            int limit = Math.min(root.size(), ARRAY_SAMPLE_LIMIT);
            for (int i = 0; i < limit; i++) {
                sample.add(compactNode(root.get(i), null, Math.max(1, ARRAY_SAMPLE_LIMIT / 4), HARD_TEXT_EXCERPT_CHARS,
                        markers, rootResultKind));
            }
            wrapper.set("sample", sample);
            ObjectNode rootMarker = JSON.createObjectNode();
            rootMarker.put("originalCount", root.size());
            rootMarker.put("returnedCount", limit);
            markers.set("$root", rootMarker);
            return wrapper;
        }
        if (!root.isObject()) {
            ObjectNode wrapper = JSON.createObjectNode();
            wrapper.put("status", "success");
            wrapper.put("resultKind", "LARGE_JSON_COMPACT");
            wrapper.set("sample", compactNode(root, null, Math.max(1, ARRAY_SAMPLE_LIMIT / 4), HARD_TEXT_EXCERPT_CHARS,
                    markers, rootResultKind));
            markers.put("truncated", true);
            return wrapper;
        }
        ObjectNode out = JSON.createObjectNode();
        for (String fieldName : orderedFieldNames(root)) {
            JsonNode child = root.get(fieldName);
            // Decided once, on the value as the tool wrote it: sampling or excerpting below can make an
            // ineligible value look like read-limit evidence, and must not earn it the budget exemption.
            boolean keep = keepsThroughLastResort(fieldName, child);
            if (child == null || child.isNull() || child.isNumber() || child.isBoolean() || child.isTextual()) {
                putWithinLastResortBudget(out, fieldName,
                        compactNode(child, fieldName, 0, HARD_TEXT_EXCERPT_CHARS, markers, rootResultKind), markers,
                        keep);
            } else if (keep) {
                putWithinLastResortBudget(out, fieldName,
                        compactNode(child, fieldName, Math.max(1, ARRAY_SAMPLE_LIMIT / 4), HARD_TEXT_EXCERPT_CHARS,
                                markers, rootResultKind), markers, keep);
            } else if (child.isArray() && shouldSampleArrayField(fieldName, rootResultKind)) {
                int sampleLimit = Math.max(1, ARRAY_SAMPLE_LIMIT / 4);
                ObjectNode fieldMarker = JSON.createObjectNode();
                fieldMarker.put("originalCount", child.size());
                fieldMarker.put("returnedCount", Math.min(child.size(), sampleLimit));
                markers.set(fieldName, fieldMarker);
                putWithinLastResortBudget(out, fieldName,
                        compactNode(child, fieldName, sampleLimit, HARD_TEXT_EXCERPT_CHARS, markers, rootResultKind),
                        markers, keep);
            } else if (child.isObject() && estimatedJsonChars(child) <= 1_500) {
                putWithinLastResortBudget(out, fieldName,
                        compactNode(child, fieldName, Math.max(1, ARRAY_SAMPLE_LIMIT / 4), HARD_TEXT_EXCERPT_CHARS,
                                markers, rootResultKind), markers, keep);
            } else {
                ObjectNode fieldMarker = JSON.createObjectNode();
                fieldMarker.put("omitted", true);
                fieldMarker.put("nodeType", child.getNodeType().name());
                if (child.isArray()) {
                    fieldMarker.put("originalCount", child.size());
                    fieldMarker.put("returnedCount", 0);
                }
                markers.set(fieldName, fieldMarker);
            }
        }
        return out;
    }

    private static List<String> orderedFieldNames(JsonNode root) {
        List<String> names = new ArrayList<>();
        for (String priority : PRIORITY_FIELDS) {
            if (root.has(priority)) {
                names.add(priority);
            }
        }
        Iterator<String> it = root.fieldNames();
        while (it.hasNext()) {
            String name = it.next();
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private static void putWithinLastResortBudget(ObjectNode out, String fieldName, JsonNode value, ObjectNode markers,
            boolean keepRegardlessOfBudget) {
        int current = estimatedJsonChars(out);
        int incoming = estimatedJsonChars(value) + fieldName.length() + 8;
        if (current + incoming <= LAST_RESORT_TARGET_CHARS || keepRegardlessOfBudget) {
            out.set(fieldName, value);
            return;
        }
        ObjectNode fieldMarker = JSON.createObjectNode();
        fieldMarker.put("omitted", true);
        fieldMarker.put("nodeType", value != null ? value.getNodeType().name() : "NULL");
        markers.set(fieldName, fieldMarker);
    }

    private static int estimatedJsonChars(JsonNode node) {
        try {
            return JSON.writeValueAsString(node).length();
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private static void applyTopLevelMarkers(ObjectNode obj, ObjectNode reducedFields, int rawChars) {
        if (!obj.has("sampleOnly")) {
            obj.put("sampleOnly", true);
        }
        if (!obj.has("rowsOmitted")) {
            obj.put("rowsOmitted", true);
        }
        obj.put("truncated", true);
        ObjectNode egress = JSON.createObjectNode();
        egress.put("reason", "llm_evidence_cap");
        egress.put("rawChars", rawChars);
        egress.put("sampleLimit", ARRAY_SAMPLE_LIMIT);
        egress.set("reducedFields", compactReducedFieldsMetadata(reducedFields));
        obj.set("_egress", egress);
    }

    private static JsonNode compactReducedFieldsMetadata(ObjectNode reducedFields) {
        if (estimatedJsonChars(reducedFields) <= 2_000) {
            return reducedFields.deepCopy();
        }
        ObjectNode summary = JSON.createObjectNode();
        int count = 0;
        ArrayNode sample = JSON.createArrayNode();
        Iterator<String> it = reducedFields.fieldNames();
        while (it.hasNext()) {
            String name = it.next();
            if (count < ARRAY_SAMPLE_LIMIT) {
                sample.add(name);
            }
            count++;
        }
        summary.put("omittedFieldCount", count);
        summary.set("sampleFieldNames", sample);
        return summary;
    }

    private static boolean isErrorEnvelope(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        String status = root.path("status").asText("");
        return "error".equalsIgnoreCase(status) || root.has("error");
    }

    private static String statusText(JsonNode root) {
        if (root != null && root.isObject() && root.has("status")) {
            return root.path("status").asText("success");
        }
        return "success";
    }

    private static String excerpt(String raw, int maxChars) {
        if (raw == null || raw.length() <= maxChars) {
            return raw != null ? raw : "";
        }
        return raw.substring(0, Math.max(0, maxChars)) + "...";
    }

    private static String safe(String value) {
        return value != null && !value.isEmpty() ? value : "unknown";
    }

    public static final class EgressResult {
        private final String llmContent;
        private final int rawChars;
        private final boolean compacted;

        private EgressResult(String llmContent, int rawChars, boolean compacted) {
            this.llmContent = llmContent != null ? llmContent : "";
            this.rawChars = Math.max(0, rawChars);
            this.compacted = compacted;
        }

        static EgressResult unchanged(String content) {
            return new EgressResult(content, content != null ? content.length() : 0, false);
        }

        /** Classification stamp without compaction (rawChars preserved from pre-stamp content). */
        static EgressResult classified(String content, int rawChars) {
            return new EgressResult(content, Math.max(0, rawChars), false);
        }

        static EgressResult compacted(String content, int rawChars) {
            return new EgressResult(content, rawChars, true);
        }

        public String getLlmContent() {
            return llmContent;
        }

        public int getRawChars() {
            return rawChars;
        }

        public boolean isCompacted() {
            return compacted;
        }

        public double getCompactRatio() {
            if (rawChars <= 0) {
                return 1.0d;
            }
            return (double) llmContent.length() / (double) rawChars;
        }
    }

    public static final class TabularEvidencePlan {
        private final int rowCount;
        private final boolean large;
        private final String resultKind;
        private final String rowsKey;
        private final int rowsToEmit;

        private TabularEvidencePlan(int rowCount, boolean large, String resultKind, String rowsKey, int rowsToEmit) {
            this.rowCount = Math.max(0, rowCount);
            this.large = large;
            this.resultKind = resultKind != null ? resultKind : "";
            this.rowsKey = rowsKey != null ? rowsKey : "rows";
            this.rowsToEmit = Math.max(0, rowsToEmit);
        }

        public int rowCount() {
            return rowCount;
        }

        public boolean large() {
            return large;
        }

        public String resultKind() {
            return resultKind;
        }

        public String rowsKey() {
            return rowsKey;
        }

        public int rowsToEmit() {
            return rowsToEmit;
        }
    }
}
