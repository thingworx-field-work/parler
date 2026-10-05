package com.thingworx.things.agent.taskstate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.PromptContextCacheSnapshot;
import com.thingworx.things.agent.configrepo.ExtendedToolDefinition;
import com.thingworx.things.agent.evidence.EvidenceAssessment;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.AgentToolContext;

/**
 * Per-turn in-memory evidence ledger (v1a).
 */
public final class AgentTaskState {

    public static final int DEFAULT_MAX_GOAL_CHARS = 200;
    public static final int DEFAULT_MAX_EVIDENCE_ROWS = 30;
    public static final int DEFAULT_MAX_SUMMARY_CHARS = 200;
    public static final int DEFAULT_MAX_TASK_STATE_CHARS = 8000;

    private final String requestId;
    private final String conversationId;
    /** Cleaned user text (slash-stripped); renderer caps with {@link #DEFAULT_MAX_GOAL_CHARS}. */
    private final String goalCleanedText;

    private int sequenceCounter;
    private int evidenceIdCounter;
    /** When provider omits tool call id, {@link #beginTrackedTool} stores the synthetic key for the current call. */
    private String lastSyntheticCorrelationKey;

    private final List<AgentTaskEvidence> evidenceRows = new ArrayList<>();
    private final Map<String, AgentTaskEvidence> openInProgressByKey = new LinkedHashMap<>();

    /**
     * Compact U4/U5 {@link EvidenceAssessment} snapshots from analysis tools (e.g.
     * {@code analyze_cached_result}). Merged into final-answer guards even when {@code mayPublish}
     * is false so §6.3 status remains authoritative.
     */
    private final List<EvidenceAssessment> analysisAssessments = new ArrayList<>();

    /** v1b skill checklist + ad-hoc progress; optional. */
    private TaskProgressV1b taskProgressV1b;

    /**
     * v1b.2: ordered unique short ids for dynamic {@code get_agent_skill} merges accepted this turn (see
     * {@code docs/agent/task-state.md}).
     */
    private final List<String> dynamicSkillShortNamesInOrder = new ArrayList<>();

    public AgentTaskState(String requestId, String conversationId, String goalCleanedText) {
        this.requestId = requestId != null ? requestId : "";
        this.conversationId = conversationId != null ? conversationId : "";
        this.goalCleanedText = goalCleanedText != null ? goalCleanedText : "";
    }

    public String getRequestId() {
        return requestId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getGoalCleanedText() {
        return goalCleanedText;
    }

    public List<AgentTaskEvidence> getEvidenceRows() {
        return evidenceRows;
    }

    /** Immutable view of analysis-envelope assessments recorded this turn. */
    public List<EvidenceAssessment> getAnalysisAssessments() {
        return List.copyOf(analysisAssessments);
    }

    /**
     * Record a compact analysis assessment for final-answer grounding (DIK-5). Caps at
     * {@link #DEFAULT_MAX_EVIDENCE_ROWS} entries.
     */
    public void recordAnalysisAssessment(EvidenceAssessment assessment) {
        if (assessment == null) {
            return;
        }
        if (analysisAssessments.size() >= DEFAULT_MAX_EVIDENCE_ROWS) {
            analysisAssessments.remove(0);
        }
        analysisAssessments.add(assessment);
    }

    public TaskProgressV1b getTaskProgressV1b() {
        return taskProgressV1b;
    }

    public void setTaskProgressV1b(TaskProgressV1b taskProgressV1b) {
        this.taskProgressV1b = taskProgressV1b;
    }

    /** Mutable ordered list of successfully merged dynamic skill short ids (v1b.2). */
    public List<String> getDynamicSkillShortNamesInOrder() {
        return dynamicSkillShortNamesInOrder;
    }

    /** Immutable copy for {@link com.thingworx.things.agent.tools.PendingApprovalRecord} at HITL enqueue. */
    public List<String> snapshotDynamicSkillShortNamesForPending() {
        return dynamicSkillShortNamesInOrder.isEmpty() ? List.of() : List.copyOf(dynamicSkillShortNamesInOrder);
    }

    public void recordMergedDynamicSkillShortName(String shortId) {
        if (shortId == null || shortId.isEmpty()) {
            return;
        }
        if (!dynamicSkillShortNamesInOrder.contains(shortId)) {
            dynamicSkillShortNamesInOrder.add(shortId);
        }
    }

    /**
     * Creates an {@code in-progress} row; {@link #findRowForCompletion} resolves it after execution.
     */
    public void beginTrackedTool(ToolCall toolCall, String toolName) {
        sequenceCounter++;
        String callId = toolCall != null ? toolCall.getId() : null;
        String correlationKey =
                (callId != null && !callId.isEmpty()) ? callId : "seq:" + sequenceCounter;
        lastSyntheticCorrelationKey = (callId == null || callId.isEmpty()) ? correlationKey : null;

        if (openInProgressByKey.containsKey(correlationKey)) {
            AgentTaskEvidence dup = openInProgressByKey.get(correlationKey);
            if (dup != null) {
                dup.setStatus("error");
                dup.setErrorCode(TaskStateErrorCode.TOOL_RESULT_INVALID);
                openInProgressByKey.remove(correlationKey);
            }
        }
        evidenceIdCounter++;
        String eid = "e" + evidenceIdCounter;
        long now = System.currentTimeMillis();
        AgentTaskEvidence row =
                new AgentTaskEvidence(eid, sequenceCounter, correlationKey, callId, toolName, "in-progress", now);
        evidenceRows.add(row);
        openInProgressByKey.put(correlationKey, row);
        populateTargetFromArgs(row, toolCall, toolName);
    }

    private static void populateTargetFromArgs(AgentTaskEvidence row, ToolCall toolCall, String toolName) {
        if (toolCall == null || toolCall.getArguments() == null || toolCall.getArguments().isEmpty()) {
            return;
        }
        try {
            org.json.JSONObject args = new org.json.JSONObject(toolCall.getArguments());
            if ("invoke_service".equals(toolName)) {
                row.setTargetType(nullIfEmpty(args.optString("entityType", null)));
                row.setTargetName(nullIfEmpty(args.optString("entityName", null)));
                row.setOperation(nullIfEmpty(args.optString("serviceName", null)));
            } else if ("fetch_cached_result".equals(toolName)) {
                row.setOperation("fetch_cached_result");
                row.setTargetType("Cache");
                row.setTargetName(nullIfEmpty(args.optString("cacheId", null)));
            } else {
                AgentThing agent = AgentToolContext.getAgentThing();
                if (agent == null) {
                    return;
                }
                PromptContextCacheSnapshot snap = agent.getPromptContextSnapshot();
                if (snap == null) {
                    return;
                }
                Optional<ExtendedToolDefinition> ext = snap.getExtendedToolRegistry().find(toolName);
                if (ext.isPresent()) {
                    row.setTargetType("Thing");
                    row.setTargetName(nullIfEmpty(ext.get().resolvedTargetThingName()));
                    row.setOperation(nullIfEmpty(ext.get().serviceName()));
                }
            }
        } catch (Exception ignored) {
            // leave targets null
        }
    }

    private static String nullIfEmpty(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        return s;
    }

    public AgentTaskEvidence findRowForCompletion(ToolCall toolCall) {
        if (toolCall == null) {
            return null;
        }
        String callId = toolCall.getId();
        if (callId != null && !callId.isEmpty()) {
            return openInProgressByKey.get(callId);
        }
        if (lastSyntheticCorrelationKey != null) {
            return openInProgressByKey.get(lastSyntheticCorrelationKey);
        }
        return null;
    }

    public void finishRow(AgentTaskEvidence row) {
        if (row == null || row.getCorrelationKey() == null) {
            return;
        }
        openInProgressByKey.remove(row.getCorrelationKey());
        lastSyntheticCorrelationKey = null;
    }

    /**
     * Post-HITL approve path: one completed {@code invoke_service} row keyed by {@code gated.getId()}.
     */
    public void addSeededInvokeServiceRow(ToolCall gated, String resultJson) {
        if (gated == null) {
            return;
        }
        sequenceCounter++;
        evidenceIdCounter++;
        String eid = "e" + evidenceIdCounter;
        String callId = gated.getId();
        String correlationKey = (callId != null && !callId.isEmpty()) ? callId : "seq:" + sequenceCounter;
        long now = System.currentTimeMillis();
        AgentTaskEvidence row = new AgentTaskEvidence(eid, sequenceCounter, correlationKey, callId, "invoke_service",
                "in-progress", now);
        populateTargetFromArgs(row, gated, "invoke_service");
        TaskStateInvokeFetchParsers.applyInvokeServiceResult(row, resultJson);
        evidenceRows.add(row);
    }

    /**
     * Post-HITL approve path for a configuration-repository extended tool (same result envelope as
     * {@code invoke_service} where applicable).
     */
    public void addSeededExtendedToolRow(ToolCall gated, String targetThingName, String serviceName, String resultJson) {
        if (gated == null) {
            return;
        }
        sequenceCounter++;
        evidenceIdCounter++;
        String eid = "e" + evidenceIdCounter;
        String callId = gated.getId();
        String correlationKey = (callId != null && !callId.isEmpty()) ? callId : "seq:" + sequenceCounter;
        long now = System.currentTimeMillis();
        String tool = gated.getFunctionName() != null ? gated.getFunctionName() : "extended_tool";
        AgentTaskEvidence row = new AgentTaskEvidence(eid, sequenceCounter, correlationKey, callId, tool, "in-progress",
                now);
        row.setTargetType("Thing");
        row.setTargetName(nullIfEmpty(targetThingName));
        row.setOperation(nullIfEmpty(serviceName));
        TaskStateInvokeFetchParsers.applyInvokeServiceResult(row, resultJson);
        evidenceRows.add(row);
    }

    /** Post-HITL cancel/reject: terminal error row with stable {@link TaskStateErrorCode}. */
    public void addSeededHitlDecisionRow(ToolCall gated, TaskStateErrorCode code) {
        if (gated == null || code == null) {
            return;
        }
        sequenceCounter++;
        evidenceIdCounter++;
        String eid = "e" + evidenceIdCounter;
        String callId = gated.getId();
        String correlationKey = (callId != null && !callId.isEmpty()) ? callId : "seq:" + sequenceCounter;
        long now = System.currentTimeMillis();
        String tool = gated.getFunctionName() != null ? gated.getFunctionName() : "invoke_service";
        AgentTaskEvidence row = new AgentTaskEvidence(eid, sequenceCounter, correlationKey, callId, tool, "error", now);
        populateTargetFromArgs(row, gated, tool);
        row.setErrorCode(code);
        row.setRowCount(-1);
        row.setProtectedOmissions(false);
        evidenceRows.add(row);
    }
}
