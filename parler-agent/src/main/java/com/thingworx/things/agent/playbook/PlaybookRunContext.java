package com.thingworx.things.agent.playbook;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;

import com.thingworx.things.agent.StreamTokenUsage;
import com.thingworx.types.InfoTable;

/** Mutable per-run state for {@link PlaybookRunner}. */
public final class PlaybookRunContext {

    private static final int DEFAULT_MAX_RAW_TABLE_ROWS = 100_000;
    private static final int ABSOLUTE_MAX_RAW_TABLE_ROWS = 1_000_000;

    private final String runId;
    private final String playbookId;
    private final JSONObject params;
    private final int maxRawTableRows;
    private final Map<String, Object> vars = new LinkedHashMap<>();
    private final Map<String, JSONObject> nodeOutputs = new LinkedHashMap<>();
    private final Map<String, InfoTable> rawTables = new LinkedHashMap<>();
    private int cumulativeRawTableRows;
    private JSONObject currentItem = new JSONObject();
    private int toolCallCount;
    private int llmCallCount;
    private StreamTokenUsage llmUsageAccumulator = StreamTokenUsage.ZERO;
    private long localAdmissionWaitMs = -1;
    private final Set<String> skippedNodeIds = new LinkedHashSet<>();
    private PlaybookArtifactEmitter artifactEmitter;
    private String turnRequestId = "";
    private String telemetryConversationId = "";
    private String agentThingName = "";

    public PlaybookRunContext(String runId, String playbookId, JSONObject params) {
        this(runId, playbookId, params, new JSONObject());
    }

    public PlaybookRunContext(String runId, String playbookId, JSONObject params, JSONObject budgets) {
        this.runId = runId;
        this.playbookId = playbookId;
        this.params = params != null ? params : new JSONObject();
        JSONObject b = budgets != null ? budgets : new JSONObject();
        int cap = b.optInt("maxRawTableRows", DEFAULT_MAX_RAW_TABLE_ROWS);
        if (cap < 1) {
            cap = 1;
        }
        if (cap > ABSOLUTE_MAX_RAW_TABLE_ROWS) {
            cap = ABSOLUTE_MAX_RAW_TABLE_ROWS;
        }
        this.maxRawTableRows = cap;
    }

    public String runId() {
        return runId;
    }

    public String playbookId() {
        return playbookId;
    }

    public JSONObject params() {
        return params;
    }

    public int maxRawTableRows() {
        return maxRawTableRows;
    }

    public Map<String, Object> vars() {
        return vars;
    }

    public void putVar(String dottedPath, Object value) {
        PlaybookRunContextVars.put(vars, dottedPath, value);
    }

    public JSONObject nodeOutput(String nodeId) {
        return nodeOutputs.get(nodeId);
    }

    public void putNodeOutput(String nodeId, JSONObject output) {
        nodeOutputs.put(nodeId, output);
    }

    /** Unmodifiable view for diagnostics / collection export. */
    public Map<String, JSONObject> nodeOutputsSnapshot() {
        return Map.copyOf(nodeOutputs);
    }

    public JSONObject currentItem() {
        return currentItem;
    }

    public void setCurrentItem(JSONObject currentItem) {
        this.currentItem = currentItem != null ? currentItem : new JSONObject();
    }

    public int toolCallCount() {
        return toolCallCount;
    }

    public void incrementToolCallCount() {
        toolCallCount++;
    }

    public int llmCallCount() {
        return llmCallCount;
    }

    public void incrementLlmCallCount() {
        llmCallCount++;
    }

    /** Accumulated usage for all playbook-internal {@code llm_summary} LLM calls (summed, not last-only). */
    public void addLlmUsage(StreamTokenUsage chunk) {
        if (chunk == null || chunk == StreamTokenUsage.ZERO) {
            return;
        }
        llmUsageAccumulator = StreamTokenUsage.combine(llmUsageAccumulator, chunk);
    }

    public StreamTokenUsage llmUsageAccumulator() {
        return llmUsageAccumulator;
    }

    public long localAdmissionWaitMs() {
        return localAdmissionWaitMs;
    }

    public void setLocalAdmissionWaitMs(long ms) {
        localAdmissionWaitMs = ms;
    }

    public int nodeOutputSize() {
        return nodeOutputs.size();
    }

    public void skipNodes(Set<String> nodeIds) {
        if (nodeIds != null) {
            skippedNodeIds.addAll(nodeIds);
        }
    }

    public boolean isSkipped(String nodeId) {
        return skippedNodeIds.contains(nodeId);
    }

    public InfoTable rawTable(String key) {
        return rawTables.get(key);
    }

    /**
     * Retains a raw service result table for {@code $table} resolution. Key is normally {@code <nodeId>.result}.
     *
     * @throws PlaybookRunException when the cumulative row budget would be exceeded
     */
    public void putRawTable(String key, InfoTable table, String failureCode) throws PlaybookRunException {
        if (key == null || key.isBlank() || table == null) {
            return;
        }
        int rows = table.getRowCount();
        long next = (long) cumulativeRawTableRows + rows;
        if (next > maxRawTableRows) {
            throw new PlaybookRunException(
                    "Raw INFOTABLE row budget exceeded for playbook run (cap " + maxRawTableRows + ", key " + key + ").",
                    failureCode != null ? failureCode : "TABLE_REF_RAW_BUDGET_EXCEEDED");
        }
        rawTables.put(key, table);
        cumulativeRawTableRows += rows;
    }

    public void clearRawTables() {
        rawTables.clear();
        cumulativeRawTableRows = 0;
    }

    /** Optional AlwaysOn hook for chart/table/tabular emission after each successful internal tool call. */
    public void setArtifactEmitter(PlaybookArtifactEmitter artifactEmitter) {
        this.artifactEmitter = artifactEmitter;
    }

    public PlaybookArtifactEmitter artifactEmitter() {
        return artifactEmitter;
    }

    public void setTelemetryContext(String turnRequestId, String conversationId, String agentThingName) {
        this.turnRequestId = turnRequestId != null ? turnRequestId : "";
        this.telemetryConversationId = conversationId != null ? conversationId : "";
        this.agentThingName = agentThingName != null ? agentThingName : "";
    }

    public String turnRequestId() {
        return turnRequestId;
    }

    public String telemetryConversationId() {
        return telemetryConversationId;
    }

    public String agentThingName() {
        return agentThingName;
    }
}
