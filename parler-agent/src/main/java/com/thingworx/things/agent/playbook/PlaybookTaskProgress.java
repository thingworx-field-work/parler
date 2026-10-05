package com.thingworx.things.agent.playbook;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import com.thingworx.things.agent.ParlerReceiveMessageSupport;

/**
 * In-turn playbook node progress for {@code task.state} wire ({@code source=playbook}). Metadata-only.
 */
public final class PlaybookTaskProgress {

    private static final Logger LOG = Logger.getLogger(PlaybookTaskProgress.class.getName());

    static final int MAX_TITLE_CHARS = 200;
    static final int MAX_LABEL_CHARS = 200;
    static final int MAX_SUMMARY_CHARS = 200;
    static final int MAX_ITEMS_PER_FRAME = 30;
    static final int MAX_FRAME_UTF16 = 8000;

    private final String playbookId;
    private final String title;
    private final Map<String, NodeRow> nodes = new LinkedHashMap<>();
    private String turnStatus = "executing";
    private String currentNodeId;

    PlaybookTaskProgress(String playbookId, PlaybookDocument document) {
        this.playbookId = playbookId != null ? playbookId : "";
        String t = document != null ? document.title() : "";
        this.title = capUtf16(t != null ? t : "Playbook", MAX_TITLE_CHARS);
        if (document != null) {
            for (String nodeId : document.nodeIdsInOrder()) {
                JSONObject node = document.nodesById().get(nodeId);
                if (node == null) {
                    continue;
                }
                String label = labelForNode(node);
                String tool = "tool_call".equals(node.optString("kind", ""))
                        ? node.optString("tool", "").trim()
                        : null;
                nodes.put(nodeId, new NodeRow(nodeId, label, tool));
            }
        }
    }

    void onValidated() {
        turnStatus = "executing";
    }

    void onNodeStarted(String nodeId) {
        currentNodeId = nodeId;
        NodeRow row = nodes.get(nodeId);
        if (row != null) {
            row.status = "in-progress";
            row.summary = "";
        }
    }

    void onNodeCompleted(String nodeId, String summary) {
        NodeRow row = nodes.get(nodeId);
        if (row != null) {
            row.status = "satisfied";
            row.summary = capUtf16(summary != null ? summary : "ok", MAX_SUMMARY_CHARS);
        }
        if (nodeId != null && nodeId.equals(currentNodeId)) {
            currentNodeId = null;
        }
    }

    void onNodeFailed(String nodeId, String summary) {
        NodeRow row = nodes.get(nodeId);
        if (row != null) {
            row.status = "failed";
            row.summary = capUtf16(summary != null ? summary : "failed", MAX_SUMMARY_CHARS);
        }
        turnStatus = "failed";
    }

    void onSummarizing() {
        currentNodeId = null;
    }

    void onFanOutProgress(String parentNodeId, int completed, int total) {
        NodeRow row = nodes.get(parentNodeId);
        if (row == null || total <= 0) {
            return;
        }
        row.status = "in-progress";
        row.summary = capUtf16(completed + "/" + total + " items completed", MAX_SUMMARY_CHARS);
    }

    String turnWireStatus() {
        return turnStatus;
    }

    void onTurnEnd(boolean success) {
        turnStatus = success ? "completed" : "failed";
        currentNodeId = null;
        if (success) {
            for (NodeRow row : nodes.values()) {
                if ("pending".equals(row.status) || "in-progress".equals(row.status)) {
                    LOG.warning(String.format(
                            "Playbook %s node %s was %s at successful turn end; marking satisfied",
                            playbookId, row.id, row.status));
                    row.status = "satisfied";
                    if (row.summary.isEmpty()) {
                        row.summary = "ok";
                    }
                }
            }
        }
    }

  /** Production wire path — canonical {@link ParlerReceiveMessageSupport#wireTaskState}. */
    String buildWireJson(String requestId, String conversationId) {
        return ParlerReceiveMessageSupport.wireTaskState(
                requestId,
                conversationId,
                1,
                turnStatus,
                title,
                buildSummaryCounts(),
                buildItemsArray());
    }

    /**
     * Test-only envelope matching {@link ParlerReceiveMessageSupport#wireTaskState} without loading
     * platform send helpers on the unit-test classpath.
     */
    String buildWireJsonForUnitTest(String requestId, String conversationId) {
        JSONObject o = new JSONObject();
        o.put("request_id", requestId != null ? requestId : "");
        o.put("conversation_id", conversationId != null ? conversationId : "");
        o.put("type", "task.state");
        o.put("schemaVersion", 1);
        o.put("status", turnStatus);
        if (title != null && !title.isEmpty()) {
            o.put("title", title);
        }
        o.put("summary", buildSummaryCounts());
        o.put("items", buildItemsArray());
        return o.toString();
    }

    JSONObject buildSummaryCounts() {
        JSONObject s = new JSONObject();
        int total = 0;
        int satisfied = 0;
        int inProgress = 0;
        int failed = 0;
        for (NodeRow row : nodes.values()) {
            total++;
            switch (row.status) {
                case "satisfied":
                    satisfied++;
                    break;
                case "in-progress":
                    inProgress++;
                    break;
                case "failed":
                case "cancelled":
                    failed++;
                    break;
                default:
                    break;
            }
        }
        try {
            s.put("playbookId", playbookId);
            s.put("total", total);
            s.put("satisfied", satisfied);
            s.put("inProgress", inProgress);
            s.put("failed", failed);
            s.put("blocked", 0);
        } catch (Exception ignored) {
            // org.json
        }
        return s;
    }

    JSONArray buildItemsArray() {
        JSONArray arr = new JSONArray();
        int n = 0;
        long now = System.currentTimeMillis();
        for (NodeRow row : nodes.values()) {
            if (n >= MAX_ITEMS_PER_FRAME) {
                break;
            }
            JSONObject o = new JSONObject();
            try {
                o.put("id", row.id);
                o.put("source", "playbook");
                o.put("kind", "evidence");
                o.put("label", row.label);
                o.put("status", row.status);
                if (row.tool != null && !row.tool.isEmpty()) {
                    o.put("tool", row.tool);
                }
                if (row.summary != null && !row.summary.isEmpty()) {
                    o.put("summary", row.summary);
                }
                o.put("updatedAtEpochMillis", now);
            } catch (Exception ignored) {
                continue;
            }
            arr.put(o);
            n++;
        }
        return arr;
    }

    static String labelForNode(JSONObject node) {
        JSONObject ev = node.optJSONObject("evidence");
        if (ev != null) {
            String label = ev.optString("label", "").trim();
            if (!label.isEmpty()) {
                return capUtf16(label, MAX_LABEL_CHARS);
            }
        }
        String id = node.optString("id", "step");
        return capUtf16(id, MAX_LABEL_CHARS);
    }

    static String summaryFromNodeResult(JSONObject node, JSONObject result) {
        if (result == null) {
            return "ok";
        }
        JSONObject evidence = result.optJSONObject("evidence");
        if (evidence != null) {
            String s = evidence.optString("summary", "").trim();
            if (!s.isEmpty()) {
                return capUtf16(s, MAX_SUMMARY_CHARS);
            }
        }
        String st = result.optString("status", "");
        if ("needs_clarification".equals(st)) {
            return capUtf16("needs clarification", MAX_SUMMARY_CHARS);
        }
        if ("failed".equals(st)) {
            return capUtf16(result.optString("message", "failed"), MAX_SUMMARY_CHARS);
        }
        if ("llm_summary".equals(node.optString("kind", ""))) {
            String text = result.optString("assistantText", "");
            if (!text.isEmpty()) {
                return capUtf16("Final answer ready (" + text.length() + " chars)", MAX_SUMMARY_CHARS);
            }
            return "Final answer ready";
        }
        return compactSummary(node, result);
    }

    private static String compactSummary(JSONObject node, JSONObject result) {
        if ("fan_out".equals(node.optString("kind", ""))) {
            JSONArray ch = result.optJSONArray("children");
            return capUtf16("Completed " + (ch != null ? ch.length() : 0) + " items", MAX_SUMMARY_CHARS);
        }
        JSONObject toolOut = result.optJSONObject("toolOutput");
        if (toolOut != null) {
            int rows = toolOut.optInt("rowCount", toolOut.optInt("totalCount", -1));
            if (rows >= 0) {
                return capUtf16(node.optString("tool", "tool") + " returned " + rows + " rows", MAX_SUMMARY_CHARS);
            }
        }
        return "ok";
    }

    static String capUtf16(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        if (max <= 3) {
            return s.substring(0, max);
        }
        return s.substring(0, max - 3) + "...";
    }

    private static final class NodeRow {
        final String id;
        final String label;
        final String tool;
        String status = "pending";
        String summary = "";

        NodeRow(String id, String label, String tool) {
            this.id = id;
            this.label = label;
            this.tool = tool;
        }
    }
}
