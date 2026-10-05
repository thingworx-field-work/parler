package com.thingworx.things.agent.compaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * Tier 0 cohort compaction: merge homogeneous fan-out tool results within one assistant batch
 * ({@code docs/agent/llm-token-budget.md}). Runs after Tier A matrix sealing. Fail-soft: never throws to callers.
 */
public final class LlmToolResultCohortMerger {

    static final String FORMAT_BUNDLE = "parler.cohort.bundle.v1";
    static final String FORMAT_MEMBER = "parler.cohort.member.v1";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOOL_GPVS = "get_property_values";
    private static final String TOOL_ALERT_SUMMARY = "query_alert_summary";
    private static final String TOOL_ALERT_HISTORY = "query_alert_history";

    private LlmToolResultCohortMerger() {}

    public static void apply(List<ChatMessage> messages, int assistantToolCallsIndex, Logger log) {
        if (messages == null || assistantToolCallsIndex < 0 || assistantToolCallsIndex >= messages.size()) {
            return;
        }
        try {
            tryMergeGetPropertyValues(messages, assistantToolCallsIndex, log);
            tryMergeAlertFanout(messages, assistantToolCallsIndex, log, TOOL_ALERT_SUMMARY);
            tryMergeAlertFanout(messages, assistantToolCallsIndex, log, TOOL_ALERT_HISTORY);
        } catch (Exception e) {
            if (log != null) {
                log.warn("LLM_COHORT_MERGE_FAILED reason={}", e.getMessage());
            }
        }
    }

    private static void tryMergeGetPropertyValues(List<ChatMessage> messages, int assistantIdx, Logger log)
            throws Exception {
        Map<String, ToolCall> byId = toolCallsById(messages.get(assistantIdx));
        Map<String, List<GpvsCand>> groups = new LinkedHashMap<>();
        for (int i = assistantIdx + 1; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m.getRole() != ChatMessage.Role.TOOL) {
                break;
            }
            ToolCall tc = byId.get(m.getToolCallId());
            if (tc == null || !TOOL_GPVS.equals(tc.getFunctionName())) {
                continue;
            }
            GpvsCand c = parseGpvsCandidate(i, tc, m.getContent());
            if (c != null) {
                groups.computeIfAbsent(c.schemaKey, k -> new ArrayList<>()).add(c);
            }
        }
        for (List<GpvsCand> g : groups.values()) {
            if (g.size() < 3) {
                continue;
            }
            g.sort(Comparator.comparingInt(a -> a.msgIdx));
            if (!allGpvsMembersEveryPropertyOk(g)) {
                if (log != null) {
                    log.info("LLM_COHORT_SKIP tool={} reason=non_ok_rows members={}", TOOL_GPVS, g.size());
                }
                continue;
            }
            if (!allGpvsOkPropertyBaseTypesMatchAcrossMembers(g)) {
                if (log != null) {
                    log.info("LLM_COHORT_SKIP tool={} reason=mixed_base_type members={}", TOOL_GPVS, g.size());
                }
                continue;
            }
            writeGpvsCohort(messages, g, log);
        }
    }

    private static void tryMergeAlertFanout(List<ChatMessage> messages, int assistantIdx, Logger log, String toolName)
            throws Exception {
        Map<String, ToolCall> byId = toolCallsById(messages.get(assistantIdx));
        Map<String, List<AlertCand>> groups = new LinkedHashMap<>();
        for (int i = assistantIdx + 1; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m.getRole() != ChatMessage.Role.TOOL) {
                break;
            }
            ToolCall tc = byId.get(m.getToolCallId());
            if (tc == null || !toolName.equals(tc.getFunctionName())) {
                continue;
            }
            AlertCand c = parseAlertSummaryCandidate(i, tc, m.getContent());
            if (c != null) {
                groups.computeIfAbsent(c.cohortKey, k -> new ArrayList<>()).add(c);
            }
        }
        for (List<AlertCand> g : groups.values()) {
            if (g.size() < 3) {
                continue;
            }
            g.sort(Comparator.comparingInt(a -> a.msgIdx));
            if (anyAlertBodyHasNonEmptyConstants(g)) {
                if (log != null) {
                    log.info("LLM_COHORT_SKIP tool={} reason=matrix_constants members={}", toolName, g.size());
                }
                continue;
            }
            writeAlertCohort(messages, g, log, toolName);
        }
    }

    private static Map<String, ToolCall> toolCallsById(ChatMessage asst) {
        Map<String, ToolCall> m = new HashMap<>();
        for (ToolCall t : asst.getToolCalls()) {
            m.put(t.getId(), t);
        }
        return m;
    }

    private static final class GpvsCand {
        final int msgIdx;
        final String toolCallId;
        final String thingName;
        final String schemaKey;
        final JsonNode body;

        GpvsCand(int msgIdx, String toolCallId, String thingName, String schemaKey, JsonNode body) {
            this.msgIdx = msgIdx;
            this.toolCallId = toolCallId;
            this.thingName = thingName;
            this.schemaKey = schemaKey;
            this.body = body;
        }
    }

    private static GpvsCand parseGpvsCandidate(int msgIdx, ToolCall tc, String content) {
        try {
            JsonNode args = MAPPER.readTree(tc.getArguments() == null ? "{}" : tc.getArguments());
            JsonNode pn = args.get("propertyNames");
            if (pn == null || !pn.isArray() || pn.size() == 0) {
                return null;
            }
            List<String> names = new ArrayList<>();
            for (JsonNode n : pn) {
                if (n != null && n.isTextual()) {
                    String s = n.asText().trim();
                    if (!s.isEmpty()) {
                        names.add(s);
                    }
                }
            }
            if (names.isEmpty()) {
                return null;
            }
            Collections.sort(names);
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    key.append('\u0001');
                }
                key.append(names.get(i));
            }
            JsonNode root = MAPPER.readTree(content == null ? "{}" : content);
            if (!"success".equalsIgnoreCase(text(root, "status"))) {
                return null;
            }
            JsonNode props = root.get("properties");
            if (props == null || !props.isArray()) {
                return null;
            }
            String thingName = text(root, "thingName");
            if (thingName == null || thingName.isEmpty()) {
                return null;
            }
            return new GpvsCand(msgIdx, tc.getId(), thingName, key.toString(), root);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeGpvsCohort(List<ChatMessage> messages, List<GpvsCand> group, Logger log) throws Exception {
        LinkedHashSet<String> union = new LinkedHashSet<>();
        for (GpvsCand c : group) {
            for (JsonNode p : c.body.get("properties")) {
                if (p != null && p.isObject() && p.has("name") && p.get("name").isTextual()) {
                    union.add(p.get("name").asText());
                }
            }
        }
        List<String> ordered = new ArrayList<>(union);
        Collections.sort(ordered);
        int before = 0;
        for (GpvsCand c : group) {
            before += messages.get(c.msgIdx).getContent() != null ? messages.get(c.msgIdx).getContent().length() : 0;
        }
        ArrayNode columns = MAPPER.createArrayNode();
        ObjectNode c0 = MAPPER.createObjectNode();
        c0.put("name", "thingName");
        c0.put("baseType", "THINGNAME");
        columns.add(c0);
        for (String pn : ordered) {
            ObjectNode col = MAPPER.createObjectNode();
            col.put("name", pn);
            col.put("baseType", inferGpvsPropertyBaseType(group, pn));
            columns.add(col);
        }
        ArrayNode rows = MAPPER.createArrayNode();
        for (GpvsCand c : group) {
            Map<String, JsonNode> pmap = indexGpvsProperties(c.body.get("properties"));
            ArrayNode row = MAPPER.createArrayNode();
            row.add(MAPPER.getNodeFactory().textNode(c.thingName));
            for (String pn : ordered) {
                row.add(pmap.getOrDefault(pn, MAPPER.nullNode()));
            }
            rows.add(row);
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.put("status", "success");
        result.put("$format", InfoTableMatrixCodec.FORMAT_MATRIX_V1);
        result.set("columns", columns);
        result.set("rows", rows);
        String cohortId = "cohort-" + UUID.randomUUID();
        ArrayNode members = MAPPER.createArrayNode();
        int mi = 0;
        for (GpvsCand c : group) {
            ObjectNode mem = MAPPER.createObjectNode();
            mem.put("memberIndex", mi++);
            mem.put("toolCallId", c.toolCallId);
            ObjectNode a = MAPPER.createObjectNode();
            a.put("thingName", c.thingName);
            mem.set("args", a);
            members.add(mem);
        }
        ObjectNode bundle = MAPPER.createObjectNode();
        bundle.put("$format", FORMAT_BUNDLE);
        bundle.put("cohortId", cohortId);
        bundle.put("toolName", TOOL_GPVS);
        bundle.put("cohortDimension", "thingName");
        bundle.put("memberCount", group.size());
        bundle.set("members", members);
        bundle.set("result", result);
        String bundleJson = MAPPER.writeValueAsString(bundle);
        int after = bundleJson.length();
        GpvsCand first = group.get(0);
        for (int i = 1; i < group.size(); i++) {
            after += buildMemberRef(cohortId, i, first.toolCallId, TOOL_GPVS, group.get(i).thingName).length();
        }
        if (after >= before) {
            if (log != null) {
                log.info("LLM_COHORT_SKIP tool={} reason=no_char_savings before={} after={} members={}",
                        TOOL_GPVS, before, after, group.size());
            }
            return;
        }
        messages.set(first.msgIdx, ChatMessage.toolResult(first.toolCallId, bundleJson));
        for (int i = 1; i < group.size(); i++) {
            GpvsCand c = group.get(i);
            messages.set(c.msgIdx, ChatMessage.toolResult(c.toolCallId,
                    buildMemberRef(cohortId, i, first.toolCallId, TOOL_GPVS, c.thingName)));
        }
        if (log != null) {
            log.info("LLM_COHORT_MERGED tool={} members={} beforeChars={} afterChars={}",
                    TOOL_GPVS, group.size(), before, after);
        }
    }

    private static Map<String, JsonNode> indexGpvsProperties(JsonNode props) {
        Map<String, JsonNode> m = new HashMap<>();
        if (props == null || !props.isArray()) {
            return m;
        }
        for (JsonNode p : props) {
            if (p == null || !p.isObject() || !p.has("name") || !p.get("name").isTextual()) {
                continue;
            }
            String name = p.get("name").asText();
            if (p.has("ok") && p.get("ok").asBoolean(false) && p.has("value")) {
                m.put(name, p.get("value"));
            } else {
                m.put(name, MAPPER.nullNode());
            }
        }
        return m;
    }

    private static String inferGpvsPropertyBaseType(List<GpvsCand> group, String propName) {
        for (GpvsCand c : group) {
            for (JsonNode p : c.body.get("properties")) {
                if (p != null && p.isObject() && propName.equals(text(p, "name"))) {
                    String bt = text(p, "baseType");
                    if (bt != null && !bt.isEmpty()) {
                        return bt;
                    }
                }
            }
        }
        return "STRING";
    }

    private static final class AlertCand {
        final int msgIdx;
        final String toolCallId;
        final String thingName;
        /** True when the tool call used live {@code thingNames:[name]} (S16); false for scalar {@code thingName}. */
        final boolean thingNamesArrayWire;
        final String cohortKey;
        final JsonNode root;

        AlertCand(int msgIdx, String toolCallId, String thingName, boolean thingNamesArrayWire, String cohortKey,
                JsonNode root) {
            this.msgIdx = msgIdx;
            this.toolCallId = toolCallId;
            this.thingName = thingName;
            this.thingNamesArrayWire = thingNamesArrayWire;
            this.cohortKey = cohortKey;
            this.root = root;
        }
    }

    private static AlertCand parseAlertSummaryCandidate(int msgIdx, ToolCall tc, String content) {
        try {
            JsonNode root = MAPPER.readTree(content == null ? "{}" : content);
            if (!"success".equalsIgnoreCase(text(root, "status"))) {
                return null;
            }
            if (root.has("resultKind")) {
                String rk = root.get("resultKind").asText();
                if ("INFOTABLE_LARGE".equalsIgnoreCase(rk) || "ALERT_SUMMARY_MULTI".equalsIgnoreCase(rk)) {
                    return null;
                }
            }
            String fmt = text(root, "$format");
            if (fmt != null && fmt.startsWith("parler.cohort.")) {
                return null;
            }
            JsonNode cols = root.get("columns");
            if (cols == null || !cols.isArray() || cols.isEmpty()) {
                return null;
            }
            String rowsKey = resolveRowsKey(root);
            if (rowsKey == null) {
                return null;
            }
            JsonNode rows = root.get(rowsKey);
            if (rows == null || !rows.isArray() || rows.isEmpty()) {
                return null;
            }
            JsonNode args = MAPPER.readTree(tc.getArguments() == null ? "{}" : tc.getArguments());
            ThingFanoutRef fanout = resolveThingFanoutRef(args, text(root, "thingName"));
            if (fanout == null) {
                return null;
            }
            String filterKey = stableArgsOmitThingIdentity(args);
            String colKey = canonicalColumnsKey(cols);
            return new AlertCand(msgIdx, tc.getId(), fanout.thingName, fanout.arrayWire,
                    filterKey + "\u0002" + colKey, root);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * S16: fan-out cohort members are N=1 only — live {@code thingNames:[canonical]} or legacy
     * scalar {@code thingName}. Multi-element {@code thingNames[]} (incl. {@code ALERT_SUMMARY_MULTI})
     * is not Tier-0 cohort-eligible.
     */
    private static final class ThingFanoutRef {
        final String thingName;
        final boolean arrayWire;

        ThingFanoutRef(String thingName, boolean arrayWire) {
            this.thingName = thingName;
            this.arrayWire = arrayWire;
        }
    }

    private static ThingFanoutRef resolveThingFanoutRef(JsonNode args, String bodyThingName) {
        if (args != null && args.has("thingNames") && args.get("thingNames").isArray()) {
            JsonNode arr = args.get("thingNames");
            if (arr.size() != 1) {
                return null;
            }
            JsonNode el = arr.get(0);
            if (el == null || !el.isTextual()) {
                return null;
            }
            String n = el.asText().trim();
            if (n.isEmpty()) {
                return null;
            }
            return new ThingFanoutRef(n, true);
        }
        String scalar = args != null ? text(args, "thingName") : null;
        if (scalar != null && !scalar.isBlank()) {
            return new ThingFanoutRef(scalar.trim(), false);
        }
        if (bodyThingName != null && !bodyThingName.isBlank()) {
            return new ThingFanoutRef(bodyThingName.trim(), false);
        }
        return null;
    }

    private static String resolveRowsKey(JsonNode root) {
        if (root.has("rows") && root.get("rows").isArray()) {
            return "rows";
        }
        if (root.has("sampleRows") && root.get("sampleRows").isArray()) {
            return "sampleRows";
        }
        return null;
    }

    private static void writeAlertCohort(List<ChatMessage> messages, List<AlertCand> group, Logger log,
            String toolName)
            throws Exception {
        AlertCand head = group.get(0);
        ArrayNode innerCols = (ArrayNode) head.root.get("columns").deepCopy();
        String rowsKey = resolveRowsKey(head.root);
        if (rowsKey == null) {
            return;
        }
        boolean headMatrix = InfoTableMatrixCodec.FORMAT_MATRIX_V1.equals(text(head.root, "$format"));
        ArrayNode mergedRows = MAPPER.createArrayNode();
        ArrayNode outCols = MAPPER.createArrayNode();
        ObjectNode dim = MAPPER.createObjectNode();
        dim.put("name", "thingName");
        dim.put("baseType", "THINGNAME");
        outCols.add(dim);
        for (JsonNode c : innerCols) {
            outCols.add(c.deepCopy());
        }
        for (AlertCand ac : group) {
            String rk = resolveRowsKey(ac.root);
            if (rk == null) {
                return;
            }
            JsonNode rrows = ac.root.get(rk);
            boolean acMatrix = InfoTableMatrixCodec.FORMAT_MATRIX_V1.equals(text(ac.root, "$format"));
            if (!columnsStructurallyEqual(innerCols, ac.root.get("columns"))) {
                if (log != null) {
                    log.info("LLM_COHORT_SKIP tool={} reason=columns_mismatch", toolName);
                }
                return;
            }
            for (JsonNode row : rrows) {
                ArrayNode outRow = MAPPER.createArrayNode();
                outRow.add(MAPPER.getNodeFactory().textNode(ac.thingName));
                if (acMatrix && row.isArray()) {
                    for (JsonNode cell : row) {
                        outRow.add(cell.deepCopy());
                    }
                } else if (row.isObject()) {
                    for (JsonNode colDef : innerCols) {
                        if (!colDef.isObject()) {
                            return;
                        }
                        String nm = text(colDef, "name");
                        JsonNode cell = nm != null ? row.get(nm) : null;
                        outRow.add(cell == null ? MAPPER.nullNode() : cell.deepCopy());
                    }
                } else {
                    return;
                }
                mergedRows.add(outRow);
            }
        }
        int before = 0;
        for (AlertCand c : group) {
            before += messages.get(c.msgIdx).getContent() != null ? messages.get(c.msgIdx).getContent().length() : 0;
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.put("status", "success");
        result.put("$format", InfoTableMatrixCodec.FORMAT_MATRIX_V1);
        result.set("columns", outCols);
        result.set("rows", mergedRows);
        String cohortId = "cohort-" + UUID.randomUUID();
        ArrayNode members = MAPPER.createArrayNode();
        int mi = 0;
        for (AlertCand c : group) {
            ObjectNode mem = MAPPER.createObjectNode();
            mem.put("memberIndex", mi++);
            mem.put("toolCallId", c.toolCallId);
            mem.set("args", thingIdentityArgs(c.thingName, c.thingNamesArrayWire));
            members.add(mem);
        }
        ObjectNode bundle = MAPPER.createObjectNode();
        bundle.put("$format", FORMAT_BUNDLE);
        bundle.put("cohortId", cohortId);
        bundle.put("toolName", toolName);
        bundle.put("cohortDimension", "thingName");
        bundle.put("memberCount", group.size());
        bundle.set("members", members);
        bundle.set("result", result);
        String bundleJson = MAPPER.writeValueAsString(bundle);
        int after = bundleJson.length();
        AlertCand first = group.get(0);
        for (int i = 1; i < group.size(); i++) {
            AlertCand c = group.get(i);
            after += buildMemberRef(cohortId, i, first.toolCallId, toolName, c.thingName, c.thingNamesArrayWire)
                    .length();
        }
        if (after >= before) {
            if (log != null) {
                log.info("LLM_COHORT_SKIP tool={} reason=no_char_savings before={} after={} members={}",
                        toolName, before, after, group.size());
            }
            return;
        }
        messages.set(first.msgIdx, ChatMessage.toolResult(first.toolCallId, bundleJson));
        for (int i = 1; i < group.size(); i++) {
            AlertCand c = group.get(i);
            messages.set(c.msgIdx, ChatMessage.toolResult(c.toolCallId,
                    buildMemberRef(cohortId, i, first.toolCallId, toolName, c.thingName, c.thingNamesArrayWire)));
        }
        if (log != null) {
            log.info("LLM_COHORT_MERGED tool={} members={} beforeChars={} afterChars={} headMatrix={}",
                    toolName, group.size(), before, after, headMatrix);
        }
    }

    private static boolean columnsStructurallyEqual(ArrayNode a, JsonNode b) {
        if (a == null || b == null || !b.isArray() || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static String canonicalColumnsKey(JsonNode cols) {
        TreeMap<String, String> m = new TreeMap<>();
        for (JsonNode c : cols) {
            if (c != null && c.isObject()) {
                String n = text(c, "name");
                String bt = text(c, "baseType");
                if (n != null && !n.isEmpty()) {
                    m.put(n, bt != null ? bt : "STRING");
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    /** Cohort filter key: omit fan-out identity keys ({@code thingName} and {@code thingNames}). */
    private static String stableArgsOmitThingIdentity(JsonNode a) throws Exception {
        ObjectNode out = MAPPER.createObjectNode();
        if (a == null || !a.isObject()) {
            return MAPPER.writeValueAsString(out);
        }
        List<String> keys = new ArrayList<>();
        a.fieldNames().forEachRemaining(keys::add);
        Collections.sort(keys);
        for (String k : keys) {
            if ("thingName".equals(k) || "thingNames".equals(k)) {
                continue;
            }
            out.set(k, a.get(k));
        }
        return MAPPER.writeValueAsString(out);
    }

    private static ObjectNode thingIdentityArgs(String thingName, boolean arrayWire) {
        ObjectNode args = MAPPER.createObjectNode();
        if (arrayWire) {
            ArrayNode names = MAPPER.createArrayNode();
            names.add(thingName);
            args.set("thingNames", names);
        } else {
            args.put("thingName", thingName);
        }
        return args;
    }

    private static String buildMemberRef(String cohortId, int memberIndex, String bundleToolCallId, String toolName,
            String thingName) throws Exception {
        return buildMemberRef(cohortId, memberIndex, bundleToolCallId, toolName, thingName, false);
    }

    private static String buildMemberRef(String cohortId, int memberIndex, String bundleToolCallId, String toolName,
            String thingName, boolean thingNamesArrayWire) throws Exception {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("$format", FORMAT_MEMBER);
        o.put("cohortId", cohortId);
        o.put("memberIndex", memberIndex);
        o.put("bundleToolCallId", bundleToolCallId);
        o.put("toolName", toolName);
        o.set("args", thingIdentityArgs(thingName, thingNamesArrayWire));
        return MAPPER.writeValueAsString(o);
    }

    private static boolean allGpvsMembersEveryPropertyOk(List<GpvsCand> group) {
        for (GpvsCand c : group) {
            JsonNode props = c.body.get("properties");
            if (props == null || !props.isArray() || props.isEmpty()) {
                return false;
            }
            for (JsonNode p : props) {
                if (p == null || !p.isObject() || !p.has("ok") || !p.get("ok").asBoolean(false)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean anyAlertBodyHasNonEmptyConstants(List<AlertCand> group) {
        for (AlertCand ac : group) {
            JsonNode cn = ac.root.get("constants");
            if (cn != null && cn.isObject() && cn.size() > 0) {
                return true;
            }
        }
        return false;
    }

    /** Per-property baseType for ok rows only; missing baseType normalized to empty string. */
    private static Map<String, String> gpvsOkPropertyBaseTypesByName(JsonNode propsArray) {
        Map<String, String> m = new LinkedHashMap<>();
        if (propsArray == null || !propsArray.isArray()) {
            return m;
        }
        for (JsonNode p : propsArray) {
            if (p == null || !p.isObject() || !p.has("name") || !p.get("name").isTextual()) {
                continue;
            }
            if (!p.has("ok") || !p.get("ok").asBoolean(false)) {
                continue;
            }
            String name = p.get("name").asText();
            String bt = text(p, "baseType");
            m.put(name, bt == null ? "" : bt);
        }
        return m;
    }

    private static boolean allGpvsOkPropertyBaseTypesMatchAcrossMembers(List<GpvsCand> group) {
        Map<String, String> ref = gpvsOkPropertyBaseTypesByName(group.get(0).body.get("properties"));
        for (int i = 1; i < group.size(); i++) {
            Map<String, String> other = gpvsOkPropertyBaseTypesByName(group.get(i).body.get("properties"));
            if (!ref.equals(other)) {
                return false;
            }
        }
        return true;
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
