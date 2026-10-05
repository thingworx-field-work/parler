package com.thingworx.things.agent.tools;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.json.JSONArray;
import org.json.JSONObject;

import com.thingworx.things.Thing;
import com.thingworx.things.agent.ParlerChartWireSupport;
import com.thingworx.things.agent.ParlerTableExportSidecar;
import com.thingworx.things.agent.ParlerToolStreamTableExportSidecar;
import com.thingworx.things.agent.llm.ChatMessage;

/**
 * Splits compact {@code fetch_cached_result} LLM replay JSON (stream/history) from full-page JSON for live Parler table
 * downlinks ({@code AgentToolContext#peekFetchCachedStreamJsonForToolCall}). Generic tool-result egress full bodies use
 * a separate slot and are resolved only for live downlinks. Runs CSV export at most once on the fetch-cached full-page
 * body and copies {@value ParlerTableExportSidecar#JSON_KEY} onto the compact persist lane.
 */
public final class FetchCachedStreamLaneHelper {

    static final int NUMERIC_CHARTBLOCK_PERSIST_MAX_POINTS = 5000;
    static final int NUMERIC_CHARTBLOCK_PERSIST_MAX_BYTES = 256 * 1024;

    private FetchCachedStreamLaneHelper() {}

    /** Rebuilds a tool-result message while preserving {@link ChatMessage#getExecutedToolName()} when present. */
    static ChatMessage toolResultPreservingExecuted(String toolCallId, String content, ChatMessage source) {
        if (source == null || source.getExecutedToolName() == null) {
            return ChatMessage.toolResult(toolCallId, content);
        }
        return ChatMessage.toolResult(toolCallId, content, source.getExecutedToolName());
    }

    /**
     * Merges export sidecar from an augmented full tool JSON onto compact tool JSON (same tool call id).
     * Package-private for unit tests.
     */
    static String mergeExportSidecarOntoCompactToolJson(String compactToolJson, String fullAugmentedToolJson) {
        if (compactToolJson == null || compactToolJson.isEmpty() || fullAugmentedToolJson == null
                || fullAugmentedToolJson.isEmpty()) {
            return compactToolJson;
        }
        try {
            JSONObject compactRoot = new JSONObject(compactToolJson);
            JSONObject fullRoot = new JSONObject(fullAugmentedToolJson);
            if (!fullRoot.has(ParlerTableExportSidecar.JSON_KEY) || fullRoot.isNull(ParlerTableExportSidecar.JSON_KEY)) {
                return compactToolJson;
            }
            Object raw = fullRoot.get(ParlerTableExportSidecar.JSON_KEY);
            if (!(raw instanceof JSONObject)) {
                return compactToolJson;
            }
            compactRoot.put(ParlerTableExportSidecar.JSON_KEY, raw);
            return compactRoot.toString();
        } catch (Exception e) {
            return compactToolJson;
        }
    }

    /**
     * Sidecar + export hook for JSON persisted on {@link com.thingworx.things.agent.AgentMessageStream} tool rows.
     * <p>Compact-fetch {@code $format} stamping ({@link FetchCachedCompactPersistFormat}) runs only on the
     * fetch-cached split-lane branch where {@link AgentToolContext#peekFetchCachedStreamJsonForToolCall} is
     * non-empty — not on generic tool rows.
     */
    public static ChatMessage augmentToolForParlerStreamPersist(
            ChatMessage toolMessage,
            Thing remoteConversation,
            String wireRequestId,
            String wireConversationId) {
        if (toolMessage == null || toolMessage.getRole() != ChatMessage.Role.TOOL) {
            return toolMessage;
        }
        String toolCallId = toolMessage.getToolCallId();
        String full = AgentToolContext.peekFetchCachedStreamJsonForToolCall(toolCallId);
        if (full == null || full.isEmpty()) {
            ChatMessage augmented = ParlerToolStreamTableExportSidecar.augmentToolMessageForStreamAppend(
                    toolMessage, remoteConversation, wireRequestId, wireConversationId);
            String withNumericChart = mergeNumericChartBlockOntoCompactToolJson(
                    augmented.getContent(), AgentToolContext.peekToolEgressFullJsonForToolCall(toolCallId));
            if (Objects.equals(withNumericChart, augmented.getContent())) {
                return augmented;
            }
            return toolResultPreservingExecuted(toolCallId, withNumericChart, augmented);
        }
        ChatMessage fullTool = toolResultPreservingExecuted(toolCallId, full, toolMessage);
        ChatMessage fullAug = ParlerToolStreamTableExportSidecar.augmentToolMessageForStreamAppend(
                fullTool, remoteConversation, wireRequestId, wireConversationId);
        AgentToolContext.setFetchCachedFullAugmentedForDownlink(toolCallId, fullAug.getContent());
        String compactBody = toolMessage.getContent();
        if (compactBody == null) {
            return stampPersistBodyIfNeeded(toolMessage);
        }
        String merged = mergeExportSidecarOntoCompactToolJson(compactBody, fullAug.getContent());
        return stampPersistBodyIfNeeded(
                merged.equals(compactBody) ? toolMessage : toolResultPreservingExecuted(toolCallId, merged, toolMessage));
    }

    private static ChatMessage stampPersistBodyIfNeeded(ChatMessage toolMsg) {
        if (toolMsg == null || toolMsg.getRole() != ChatMessage.Role.TOOL) {
            return toolMsg;
        }
        String executed = toolMsg.getExecutedToolName();
        if (executed != null && !"fetch_cached_result".equals(executed)) {
            return toolMsg;
        }
        String content = toolMsg.getContent();
        String stamped = FetchCachedCompactPersistFormat.stampWhenApplicable(content);
        if (Objects.equals(stamped, content)) {
            return toolMsg;
        }
        return toolResultPreservingExecuted(toolMsg.getToolCallId(), stamped, toolMsg);
    }

    /**
     * Tool message whose JSON drives live table/chart downlinks: full fetch body when registered for the tool call
     * id, otherwise the same augmented payload used for stream persist.
     */
    public static ChatMessage resolveToolMessageForParlerTableDownlinks(
            ChatMessage compactToolResult,
            ChatMessage augmentedPersist,
            Thing remoteConversation,
            String wireRequestId,
            String wireConversationId) {
        if (compactToolResult == null || compactToolResult.getRole() != ChatMessage.Role.TOOL) {
            return augmentedPersist;
        }
        String cached = AgentToolContext.takeFetchCachedFullAugmentedForDownlink(compactToolResult.getToolCallId());
        if (cached != null && !cached.isEmpty()) {
            return toolResultPreservingExecuted(compactToolResult.getToolCallId(), cached, compactToolResult);
        }
        String egressFull = AgentToolContext.takeToolEgressFullJsonForToolCall(compactToolResult.getToolCallId());
        if (egressFull != null && !egressFull.isEmpty()) {
            if (isNumericHistoryChartSuppressed(compactToolResult.getContent())) {
                return augmentedPersist;
            }
            return toolResultPreservingExecuted(compactToolResult.getToolCallId(), egressFull, compactToolResult);
        }
        return augmentedPersist;
    }

    static String mergeNumericChartBlockOntoCompactToolJson(String compactToolJson, String fullToolJson) {
        if (compactToolJson == null || compactToolJson.isEmpty() || fullToolJson == null || fullToolJson.isEmpty()) {
            return compactToolJson;
        }
        try {
            JSONObject compactRoot = new JSONObject(compactToolJson);
            if (!isNumericHistoryCompactRoot(compactRoot)) {
                return compactToolJson;
            }
            if (compactRoot.has("chartBlock")) {
                return compactToolJson;
            }
            return ParlerChartWireSupport.chartBlockFromNumericHistoryToolResult(fullToolJson, false)
                    .map(chart -> {
                        int pointCount = chartPointCount(chart);
                        int byteCount = chart.toString().getBytes(StandardCharsets.UTF_8).length;
                        if (pointCount > NUMERIC_CHARTBLOCK_PERSIST_MAX_POINTS
                                || byteCount > NUMERIC_CHARTBLOCK_PERSIST_MAX_BYTES) {
                            putChartBlockBudgetMetadata(compactRoot, false, pointCount, byteCount);
                            compactRoot.put("chartBlockOmittedReason", "storage_budget_exceeded");
                            return compactRoot.toString();
                        }
                        compactRoot.put("chartBlock", chart);
                        putChartBlockBudgetMetadata(compactRoot, true, pointCount, byteCount);
                        return compactRoot.toString();
                    })
                    .orElse(compactToolJson);
        } catch (Exception e) {
            return compactToolJson;
        }
    }

    private static void putChartBlockBudgetMetadata(
            JSONObject compactRoot, boolean persisted, int pointCount, int byteCount) {
        compactRoot.put("chartBlockPersisted", persisted);
        compactRoot.put("chartBlockPointCount", Math.max(0, pointCount));
        compactRoot.put("chartBlockBytes", Math.max(0, byteCount));
        compactRoot.put("chartBlockPointLimit", NUMERIC_CHARTBLOCK_PERSIST_MAX_POINTS);
        compactRoot.put("chartBlockByteLimit", NUMERIC_CHARTBLOCK_PERSIST_MAX_BYTES);
    }

    private static int chartPointCount(JSONObject chart) {
        if (chart == null) {
            return 0;
        }
        JSONArray series = chart.optJSONArray("series");
        if (series == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < series.length(); i++) {
            JSONObject s = series.optJSONObject(i);
            if (s == null) {
                continue;
            }
            JSONArray x = s.optJSONArray("x");
            JSONArray y = s.optJSONArray("y");
            total += Math.max(x == null ? 0 : x.length(), y == null ? 0 : y.length());
        }
        return total;
    }

    private static boolean isNumericHistoryChartSuppressed(String compactToolJson) {
        if (compactToolJson == null || compactToolJson.isEmpty()) {
            return false;
        }
        try {
            JSONObject root = new JSONObject(compactToolJson);
            return isNumericHistoryCompactRoot(root) && root.has("chartEmitted") && !root.optBoolean("chartEmitted", false);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isNumericHistoryCompactRoot(JSONObject root) {
        if (root == null || !"success".equalsIgnoreCase(root.optString("status"))) {
            return false;
        }
        String format = root.optString("$format");
        if (!"parler.numeric_history.compact.v1".equals(format)
                && !"parler.infotable.matrix.v1".equals(format)) {
            return false;
        }
        String kind = root.optString("resultKind");
        return "NUMERIC_HISTORY_INLINE".equals(kind) || "NUMERIC_HISTORY_AGGREGATES".equals(kind);
    }
}
