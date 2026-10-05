package com.thingworx.things.agent.taskstate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.json.JSONObject;

import com.thingworx.things.agent.llm.ToolCall;

/**
 * v1b per-turn progress items (skill checklist + ad-hoc tool rows). Server-authored only.
 */
public final class TaskProgressV1b {

    public static final int MAX_TITLE_CHARS = 200;
    public static final int MAX_LABEL_CHARS = 200;
    public static final int MAX_SUMMARY_CHARS = 200;
    public static final int MAX_ITEMS_PER_FRAME = 30;
    public static final int MAX_FRAME_UTF16 = 8000;

    private String wireTitle;
    /** Wire snapshot status: executing, completed, failed, blocked-by-approval. */
    private String turnWireStatus = "executing";
    private final List<TaskProgressItem> skillItems = new ArrayList<>();
    private final List<TaskProgressItem> adHocItems = new ArrayList<>();
    private int adHocSeq;
    private boolean dirty;

    /**
     * When multiple evidence rows match the same tool and structural {@code match} fields but differ on
     * {@code match.resultKind}, resolution waits until {@link #onAfterTool} has evidence or JSON.
     */
    private String deferredStructuralMatchToolCallId;

    private final List<TaskProgressItem> deferredResultKindCandidates = new ArrayList<>();

    public static TaskProgressV1b empty() {
        return new TaskProgressV1b();
    }

    public static TaskProgressV1b fromChecklist(JSONObject checklistRoot) {
        TaskProgressV1b out = new TaskProgressV1b();
        if (checklistRoot == null) {
            return out;
        }
        if (checklistRoot.has("title") && !checklistRoot.isNull("title")) {
            out.wireTitle = capUtf16(checklistRoot.optString("title", ""), MAX_TITLE_CHARS);
        }
        org.json.JSONArray rawArr = checklistRoot.optJSONArray("requiredEvidence");
        if (rawArr == null) {
            return out;
        }
        JSONArrayWrapper arr = new JSONArrayWrapper(rawArr);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject raw = arr.getJSONObject(i);
            String id = raw.optString("id", "").trim();
            String desc = raw.optString("description", "").trim();
            String kind = raw.optString("kind", "").trim();
            if (kind.isEmpty()) {
                kind = "guidance";
            }
            String tool = raw.has("tool") && !raw.isNull("tool") ? raw.optString("tool", "").trim() : null;
            JSONObject match = raw.has("match") && !raw.isNull("match") ? raw.optJSONObject("match") : null;
            String label = capUtf16(desc, MAX_LABEL_CHARS);
            String card = "one";
            if (raw.has("cardinality") && !raw.isNull("cardinality")) {
                String cx = raw.optString("cardinality", "").trim();
                if ("one_or_more".equals(cx)) {
                    card = "one_or_more";
                }
            }
            String initialStatus;
            if ("guidance".equals(kind)) {
                initialStatus = "not-applicable";
            } else if ("synthesis".equals(kind)) {
                initialStatus = "pending";
            } else {
                initialStatus = "pending";
            }
            TaskProgressItem item = new TaskProgressItem(
                    id,
                    "skill",
                    kind,
                    label,
                    initialStatus,
                    tool,
                    cloneMatch(match),
                    card,
                    System.currentTimeMillis());
            out.skillItems.add(item);
        }
        out.markDirty();
        return out;
    }

    private static JSONObject cloneMatch(JSONObject match) {
        if (match == null) {
            return null;
        }
        try {
            return new JSONObject(match.toString());
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isDirty() {
        return dirty;
    }

    public void clearDirty() {
        dirty = false;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public String getTurnWireStatus() {
        return turnWireStatus;
    }

    public void setTurnWireStatus(String turnWireStatus) {
        this.turnWireStatus = turnWireStatus != null ? turnWireStatus : "executing";
        markDirty();
    }

    /**
     * Called once before terminal wire emission (before {@link com.thingworx.things.agent.tools.AgentToolContext#clear()}).
     */
    public void applyTurnEnd(boolean success, boolean awaitingApproval) {
        if (awaitingApproval) {
            turnWireStatus = "blocked-by-approval";
        } else if (success) {
            turnWireStatus = "completed";
            for (TaskProgressItem p : skillItems) {
                if ("synthesis".equals(p.kind)) {
                    p.status = "satisfied";
                    p.updatedAtEpochMillis = System.currentTimeMillis();
                }
            }
        } else {
            turnWireStatus = "failed";
            for (TaskProgressItem p : skillItems) {
                if ("synthesis".equals(p.kind)) {
                    p.status = "failed";
                    p.updatedAtEpochMillis = System.currentTimeMillis();
                }
            }
        }
        deferredStructuralMatchToolCallId = null;
        deferredResultKindCandidates.clear();
        markDirty();
    }

    public void onBeforeTool(ToolCall tc) {
        if (tc == null) {
            return;
        }
        String fn = tc.getFunctionName() != null ? tc.getFunctionName() : "";
        TaskProgressItem skillMatch = findFirstMatchableSkillItem(tc, fn);
        if (skillMatch != null) {
            skillMatch.status = "in-progress";
            skillMatch.updatedAtEpochMillis = System.currentTimeMillis();
            markDirty();
            return;
        }
        List<TaskProgressItem> structural = collectStructuralMatches(tc, fn);
        if (structural.size() > 1) {
            LinkedHashSet<String> rk = new LinkedHashSet<>();
            for (TaskProgressItem p : structural) {
                rk.add(normalizedResultKindKey(p.match));
            }
            if (rk.size() > 1) {
                beginDeferredResultKindMatch(tc, structural);
                return;
            }
        }
        adHocSeq++;
        String id = "tool-" + adHocSeq;
        TaskProgressItem ad = new TaskProgressItem(
                id,
                "tool",
                "tool",
                capUtf16(fn, MAX_LABEL_CHARS),
                "in-progress",
                fn.isEmpty() ? null : fn,
                null,
                "one",
                System.currentTimeMillis());
        ad.activeToolCallId = tc.getId();
        adHocItems.add(ad);
        markDirty();
    }

    public void onBlockedTool(ToolCall tc) {
        TaskProgressItem hit = findActiveItemForToolCall(tc);
        if (hit != null) {
            hit.status = "blocked-by-approval";
            hit.summary = capUtf16("approval required", MAX_SUMMARY_CHARS);
            hit.updatedAtEpochMillis = System.currentTimeMillis();
            markDirty();
        }
    }

    public void onAfterTool(ToolCall tc, String resultJson, AgentTaskEvidence evidenceOrNull) {
        if (tc == null) {
            return;
        }
        if (handleDeferredResultKindResolution(tc, resultJson, evidenceOrNull)) {
            return;
        }
        TaskProgressItem item = findActiveItemForToolCall(tc);
        if (item == null) {
            return;
        }
        applyOutcomeToItem(item, resultJson, evidenceOrNull);
    }

    private void beginDeferredResultKindMatch(ToolCall tc, List<TaskProgressItem> candidates) {
        deferredStructuralMatchToolCallId = tc.getId();
        deferredResultKindCandidates.clear();
        deferredResultKindCandidates.addAll(candidates);
        long now = System.currentTimeMillis();
        for (TaskProgressItem p : candidates) {
            p.status = "in-progress";
            p.updatedAtEpochMillis = now;
        }
        markDirty();
    }

    /**
     * Resolves multiple checklist rows that matched structurally but differed on {@code match.resultKind}.
     *
     * @return true if this tool call was handled as deferred resolution
     */
    private boolean handleDeferredResultKindResolution(
            ToolCall tc, String resultJson, AgentTaskEvidence evidenceOrNull) {
        if (deferredStructuralMatchToolCallId == null || deferredResultKindCandidates.isEmpty()) {
            return false;
        }
        if (tc.getId() == null || !deferredStructuralMatchToolCallId.equals(tc.getId())) {
            return false;
        }
        List<TaskProgressItem> candidates = new ArrayList<>(deferredResultKindCandidates);
        deferredStructuralMatchToolCallId = null;
        deferredResultKindCandidates.clear();

        TaskProgressItem winner = null;
        if (evidenceOrNull != null && "ok".equals(evidenceOrNull.getStatus())) {
            for (TaskProgressItem p : candidates) {
                if (evidenceMatchesResultKindRequirement(p.match, evidenceOrNull)) {
                    winner = p;
                    break;
                }
            }
            if (winner == null) {
                long now = System.currentTimeMillis();
                for (TaskProgressItem p : candidates) {
                    p.status = "failed";
                    p.summary = capUtf16("resultKind mismatch", MAX_SUMMARY_CHARS);
                    p.updatedAtEpochMillis = now;
                }
                markDirty();
                return true;
            }
        } else if (evidenceOrNull != null
                && ("error".equals(evidenceOrNull.getStatus())
                        || "blocked-by-approval".equals(evidenceOrNull.getStatus()))) {
            winner = candidates.get(0);
            long now = System.currentTimeMillis();
            for (int i = 1; i < candidates.size(); i++) {
                TaskProgressItem p = candidates.get(i);
                p.status = "pending";
                p.activeToolCallId = null;
                p.updatedAtEpochMillis = now;
            }
            applyOutcomeToItem(winner, resultJson, evidenceOrNull);
            markDirty();
            return true;
        } else {
            for (TaskProgressItem p : candidates) {
                if (matchResultKindAbsentOrMetByJson(p.match, resultJson)) {
                    winner = p;
                    break;
                }
            }
            if (winner == null) {
                long now = System.currentTimeMillis();
                for (TaskProgressItem p : candidates) {
                    p.status = "failed";
                    p.summary = capUtf16("resultKind mismatch", MAX_SUMMARY_CHARS);
                    p.updatedAtEpochMillis = now;
                }
                markDirty();
                return true;
            }
        }

        long now = System.currentTimeMillis();
        for (TaskProgressItem p : candidates) {
            if (p != winner) {
                p.status = "pending";
                p.activeToolCallId = null;
                p.updatedAtEpochMillis = now;
            }
        }
        applyOutcomeToItem(winner, resultJson, evidenceOrNull);
        markDirty();
        return true;
    }

    private void applyOutcomeToItem(
            TaskProgressItem item, String resultJson, AgentTaskEvidence evidenceOrNull) {
        if (evidenceOrNull != null) {
            item.summary = capUtf16(summarizeFromEvidence(evidenceOrNull), MAX_SUMMARY_CHARS);
            if ("ok".equals(evidenceOrNull.getStatus())) {
                if (!evidenceMatchesResultKindRequirement(item.match, evidenceOrNull)) {
                    item.status = "failed";
                    item.summary = capUtf16("resultKind mismatch", MAX_SUMMARY_CHARS);
                } else {
                    item.status = "satisfied";
                    String eid = evidenceOrNull.getEvidenceId();
                    if (eid != null && !item.evidenceIds.contains(eid)) {
                        item.evidenceIds.add(eid);
                    }
                    item.observedCount = item.evidenceIds.size();
                }
            } else if ("blocked-by-approval".equals(evidenceOrNull.getStatus())) {
                item.status = "blocked-by-approval";
            } else if ("error".equals(evidenceOrNull.getStatus())) {
                item.status = "failed";
                if (item.summary.isEmpty()) {
                    item.summary = capUtf16(
                            evidenceOrNull.getErrorCodeEnum() != null
                                    ? evidenceOrNull.getErrorCodeEnum().name()
                                    : "error",
                            MAX_SUMMARY_CHARS);
                }
            }
        } else {
            boolean ok = NoEvidenceToolOutcome.isSatisfiedForNoEvidencePath(resultJson);
            item.summary = capUtf16(ok ? "ok" : "error", MAX_SUMMARY_CHARS);
            item.status = ok ? "satisfied" : "failed";
            if (ok && item.match != null && !matchResultKindAbsentOrMetByJson(item.match, resultJson)) {
                item.status = "failed";
                item.summary = capUtf16("resultKind mismatch", MAX_SUMMARY_CHARS);
            }
        }
        item.updatedAtEpochMillis = System.currentTimeMillis();
        item.activeToolCallId = null;
        markDirty();
    }

    /** v1b.2: ids of skill-sourced checklist rows (for duplicate detection). */
    Set<String> collectSkillEvidenceIds() {
        Set<String> out = new HashSet<>();
        for (TaskProgressItem p : skillItems) {
            if (p.id != null && !p.id.isEmpty()) {
                out.add(p.id);
            }
        }
        return out;
    }

    /**
     * v1b.2: append {@code requiredEvidence} rows from a validated single-fence checklist (dynamic {@code get_agent_skill}).
     */
    void appendSkillRowsFromDynamicChecklist(JSONObject checklistRoot) {
        if (checklistRoot == null) {
            return;
        }
        if (checklistRoot.has("title") && !checklistRoot.isNull("title")) {
            String t = checklistRoot.optString("title", "").trim();
            if (!t.isEmpty() && (wireTitle == null || wireTitle.isEmpty())) {
                wireTitle = capUtf16(t, MAX_TITLE_CHARS);
            }
        }
        org.json.JSONArray rawArr = checklistRoot.optJSONArray("requiredEvidence");
        if (rawArr == null) {
            return;
        }
        JSONArrayWrapper arr = new JSONArrayWrapper(rawArr);
        long now = System.currentTimeMillis();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject raw = arr.getJSONObject(i);
            String id = raw.optString("id", "").trim();
            String desc = raw.optString("description", "").trim();
            String kind = raw.optString("kind", "").trim();
            if (kind.isEmpty()) {
                kind = "guidance";
            }
            String tool = raw.has("tool") && !raw.isNull("tool") ? raw.optString("tool", "").trim() : null;
            JSONObject match = raw.has("match") && !raw.isNull("match") ? raw.optJSONObject("match") : null;
            String label = capUtf16(desc, MAX_LABEL_CHARS);
            String card = "one";
            if (raw.has("cardinality") && !raw.isNull("cardinality")) {
                String cx = raw.optString("cardinality", "").trim();
                if ("one_or_more".equals(cx)) {
                    card = "one_or_more";
                }
            }
            String initialStatus;
            if ("guidance".equals(kind)) {
                initialStatus = "not-applicable";
            } else if ("synthesis".equals(kind)) {
                initialStatus = "pending";
            } else {
                initialStatus = "pending";
            }
            TaskProgressItem item = new TaskProgressItem(
                    id,
                    "skill",
                    kind,
                    label,
                    initialStatus,
                    tool,
                    cloneMatch(match),
                    card,
                    now);
            skillItems.add(item);
        }
        markDirty();
    }

    /** v1b.2: compact metadata-only summary on the latest ad-hoc row for a tool name (after {@code activeToolCallId} clear). */
    void patchLatestAdHocToolSummary(String toolName, String summaryCode) {
        if (toolName == null || toolName.isEmpty()) {
            return;
        }
        for (int i = adHocItems.size() - 1; i >= 0; i--) {
            TaskProgressItem p = adHocItems.get(i);
            if (toolName.equals(p.tool)) {
                p.summary = capUtf16(summaryCode, MAX_SUMMARY_CHARS);
                p.updatedAtEpochMillis = System.currentTimeMillis();
                markDirty();
                return;
            }
        }
    }

    private static boolean evidenceMatchesResultKindRequirement(JSONObject match, AgentTaskEvidence ev) {
        if (match == null || ev == null) {
            return true;
        }
        if (!match.has("resultKind") || match.isNull("resultKind")) {
            return true;
        }
        String want = match.optString("resultKind", "").trim();
        if (want.isEmpty()) {
            return true;
        }
        String got = ev.getResultKind() != null ? ev.getResultKind().trim() : "";
        return want.equals(got);
    }

    /** When there is no v1a evidence row, allow JSON tool body to satisfy {@code match.resultKind} if present. */
    private static boolean matchResultKindAbsentOrMetByJson(JSONObject match, String resultJson) {
        if (match == null || !match.has("resultKind") || match.isNull("resultKind")) {
            return true;
        }
        String want = match.optString("resultKind", "").trim();
        if (want.isEmpty()) {
            return true;
        }
        if (resultJson == null || resultJson.isEmpty()) {
            return false;
        }
        try {
            JSONObject o = new JSONObject(resultJson);
            String rk = o.optString("resultKind", "").trim();
            return want.equals(rk);
        } catch (Exception e) {
            return false;
        }
    }

    private TaskProgressItem findActiveItemForToolCall(ToolCall tc) {
        String cid = tc.getId();
        if (cid != null && !cid.isEmpty()) {
            for (TaskProgressItem p : skillItems) {
                if (cid.equals(p.activeToolCallId)) {
                    return p;
                }
            }
            for (TaskProgressItem p : adHocItems) {
                if (cid.equals(p.activeToolCallId)) {
                    return p;
                }
            }
        }
        String fn = tc.getFunctionName();
        for (int i = adHocItems.size() - 1; i >= 0; i--) {
            TaskProgressItem p = adHocItems.get(i);
            if ("in-progress".equals(p.status) && fn != null && fn.equals(p.tool)) {
                return p;
            }
        }
        return null;
    }

    private TaskProgressItem findFirstMatchableSkillItem(ToolCall tc, String fn) {
        List<TaskProgressItem> candidates = collectStructuralMatches(tc, fn);
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() > 1) {
            LinkedHashSet<String> rk = new LinkedHashSet<>();
            for (TaskProgressItem p : candidates) {
                rk.add(normalizedResultKindKey(p.match));
            }
            if (rk.size() > 1) {
                return null;
            }
        }
        TaskProgressItem p = candidates.get(0);
        p.activeToolCallId = tc.getId();
        return p;
    }

    private List<TaskProgressItem> collectStructuralMatches(ToolCall tc, String fn) {
        List<TaskProgressItem> out = new ArrayList<>();
        if (fn.isEmpty()) {
            return out;
        }
        for (TaskProgressItem p : skillItems) {
            if (!"evidence".equals(p.kind)) {
                continue;
            }
            if (p.tool == null || !fn.equals(p.tool)) {
                continue;
            }
            boolean canMatch = "pending".equals(p.status) || "in-progress".equals(p.status);
            if (!canMatch && !("satisfied".equals(p.status) && "one_or_more".equals(p.cardinality))) {
                continue;
            }
            if (!matchToolArgs(p.match, tc, fn)) {
                continue;
            }
            out.add(p);
        }
        return out;
    }

    private static String normalizedResultKindKey(JSONObject match) {
        if (match == null || !match.has("resultKind") || match.isNull("resultKind")) {
            return "";
        }
        return match.optString("resultKind", "").trim();
    }

    /** Package-visible for unit tests ({@link TaskProgressV1bMatchToolArgsTest}). */
    static boolean matchToolArgs(JSONObject match, ToolCall tc, String toolName) {
        if (match == null || match.length() == 0) {
            return true;
        }
        String rawArgs = tc.getArguments();
        if (rawArgs == null || rawArgs.isEmpty()) {
            return false;
        }
        try {
            JSONObject args = new JSONObject(rawArgs);
            if (match.has("targetType") && !match.isNull("targetType")) {
                if (!eq(match.optString("targetType"), args.optString("entityType"))) {
                    return false;
                }
            }
            if (match.has("targetName") && !match.isNull("targetName")) {
                String mv = match.optString("targetName");
                if ("fetch_cached_result".equals(toolName)) {
                    if (!eq(mv, args.optString("cacheId"))) {
                        return false;
                    }
                } else {
                    if (!eq(mv, args.optString("entityName"))) {
                        return false;
                    }
                }
            }
            if (match.has("operation") && !match.isNull("operation")) {
                if (!eq(match.optString("operation"), args.optString("serviceName"))) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean eq(String a, String b) {
        String x = a != null ? a.trim() : "";
        String y = b != null ? b.trim() : "";
        return x.equals(y);
    }

    private static String summarizeFromEvidence(AgentTaskEvidence e) {
        if (e == null) {
            return "";
        }
        if ("invoke_service".equals(e.getTool()) || "fetch_cached_result".equals(e.getTool())) {
            int rc = e.getRowCount();
            if (rc >= 0) {
                return rc + " rows";
            }
        }
        if ("ok".equals(e.getStatus())) {
            return "ok";
        }
        return "";
    }

    JSONObject buildSummaryCounts() {
        JSONObject s = new JSONObject();
        int total = 0;
        int satisfied = 0;
        int inProgress = 0;
        int failed = 0;
        int blocked = 0;
        for (TaskProgressItem p : skillItems) {
            if (!"evidence".equals(p.kind)) {
                continue;
            }
            total++;
            switch (p.status) {
                case "satisfied":
                    satisfied++;
                    break;
                case "in-progress":
                    inProgress++;
                    break;
                case "pending":
                    break;
                case "blocked-by-approval":
                    blocked++;
                    break;
                case "failed":
                case "cancelled":
                case "expired":
                    failed++;
                    break;
                default:
                    break;
            }
        }
        try {
            s.put("total", total);
            s.put("satisfied", satisfied);
            s.put("inProgress", inProgress);
            s.put("failed", failed);
            s.put("blocked", blocked);
        } catch (Exception ignored) {
            // org.json
        }
        return s;
    }

    org.json.JSONArray buildItemsArrayForWire() {
        org.json.JSONArray arr = new org.json.JSONArray();
        int skillCount = skillItems.size();
        for (int i = 0; i < skillCount; i++) {
            arr.put(skillItems.get(i).toWireJson());
        }
        int remaining = MAX_ITEMS_PER_FRAME - skillCount;
        if (remaining <= 0) {
            return arr;
        }
        int adhocTotal = adHocItems.size();
        if (adhocTotal <= remaining) {
            for (TaskProgressItem p : adHocItems) {
                arr.put(p.toWireJson());
            }
            return arr;
        }
        int showAdhoc = remaining - 1;
        if (showAdhoc < 0) {
            showAdhoc = 0;
        }
        int start = Math.max(0, adhocTotal - showAdhoc);
        int omitted = start;
        for (int i = start; i < adhocTotal; i++) {
            arr.put(adHocItems.get(i).toWireJson());
        }
        if (omitted > 0) {
            TaskProgressItem omission = new TaskProgressItem(
                    "__omitted_tool_items__",
                    "tool",
                    "tool",
                    "Older tool progress omitted",
                    "not-applicable",
                    null,
                    null,
                    "one",
                    System.currentTimeMillis());
            omission.summary = capUtf16(omitted + " items omitted", MAX_SUMMARY_CHARS);
            arr.put(omission.toWireJson());
        }
        return arr;
    }

    String getWireTitle() {
        return wireTitle;
    }

    private static String capUtf16(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        int cut = Math.max(0, max - "... truncated".length());
        return s.substring(0, cut) + "... truncated";
    }

    /** Thin wrapper so we compile against minimal JSONObject surface. */
    private static final class JSONArrayWrapper {
        private final org.json.JSONArray delegate;

        JSONArrayWrapper(org.json.JSONArray delegate) {
            this.delegate = delegate;
        }

        int length() {
            return delegate != null ? delegate.length() : 0;
        }

        JSONObject getJSONObject(int i) {
            return delegate != null ? delegate.getJSONObject(i) : new JSONObject();
        }
    }

    static final class TaskProgressItem {
        final String id;
        final String source;
        final String kind;
        final String label;
        String status;
        final String tool;
        final JSONObject match;
        /** Skill checklist cardinality: {@code one} or {@code one_or_more}. */
        final String cardinality;
        final List<String> evidenceIds = new ArrayList<>();
        int observedCount;
        String summary = "";
        long updatedAtEpochMillis;
        /** Latest bound tool call id for correlation (internal). */
        String activeToolCallId;

        TaskProgressItem(
                String id,
                String source,
                String kind,
                String label,
                String status,
                String tool,
                JSONObject match,
                String cardinality,
                long updatedAtEpochMillis) {
            this.id = id;
            this.source = source;
            this.kind = kind;
            this.label = label;
            this.status = status;
            this.tool = tool;
            this.match = match;
            this.cardinality = cardinality != null && !cardinality.isEmpty() ? cardinality : "one";
            this.updatedAtEpochMillis = updatedAtEpochMillis;
        }

        JSONObject toWireJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("source", source);
                o.put("kind", kind);
                o.put("label", label);
                o.put("status", status);
                if (tool != null && !tool.isEmpty()) {
                    o.put("tool", tool);
                }
                if (match != null && match.length() > 0) {
                    JSONObject m = new JSONObject(match.toString());
                    if (m.has("targetName")) {
                        m.remove("targetName");
                    }
                    if (m.length() > 0) {
                        o.put("match", m);
                    }
                }
                if (!evidenceIds.isEmpty()) {
                    org.json.JSONArray ids = new org.json.JSONArray();
                    for (String e : evidenceIds) {
                        ids.put(e);
                    }
                    o.put("evidenceIds", ids);
                }
                if (observedCount > 0) {
                    o.put("observedCount", observedCount);
                }
                if (!summary.isEmpty()) {
                    o.put("summary", summary);
                }
                o.put("updatedAtEpochMillis", updatedAtEpochMillis);
            } catch (Exception ignored) {
                // defensive
            }
            return o;
        }
    }
}
