package com.thingworx.things.agent.taskstate;

import java.util.List;

import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.AgentToolContext;

/** Bridges tool lifecycle to {@link TaskProgressV1b} and wire emission (v1b). */
public final class TaskProgressV1bHooks {

    private TaskProgressV1bHooks() {}

    public static void onBeforeTool(ToolCall toolCall) {
        if (AgentToolContext.isPlaybookTaskProgressActive()) {
            return;
        }
        AgentTaskState st = AgentToolContext.getAgentTaskState();
        if (st == null) {
            return;
        }
        TaskProgressV1b v = st.getTaskProgressV1b();
        if (v == null) {
            return;
        }
        v.onBeforeTool(toolCall);
        TaskProgressWireEmitter.flushSnapshot(st);
    }

    public static void afterTrackedTool(ToolCall toolCall, String resultJson) {
        if (AgentToolContext.isPlaybookTaskProgressActive()) {
            return;
        }
        AgentTaskState st = AgentToolContext.getAgentTaskState();
        if (st == null) {
            return;
        }
        TaskProgressV1b v = st.getTaskProgressV1b();
        if (v == null) {
            return;
        }
        AgentTaskEvidence ev = resolveEvidence(st, toolCall);
        v.onAfterTool(toolCall, resultJson, ev);
        TaskProgressWireEmitter.flushSnapshot(st);
    }

    public static void onBlocked(ToolCall toolCall) {
        if (AgentToolContext.isPlaybookTaskProgressActive()) {
            return;
        }
        AgentTaskState st = AgentToolContext.getAgentTaskState();
        if (st == null) {
            return;
        }
        TaskProgressV1b v = st.getTaskProgressV1b();
        if (v == null) {
            return;
        }
        v.onBlockedTool(toolCall);
        TaskProgressWireEmitter.flushSnapshot(st);
    }

    private static AgentTaskEvidence resolveEvidence(AgentTaskState st, ToolCall tc) {
        if (st == null || tc == null) {
            return null;
        }
        String tid = tc.getId();
        if (tid == null || tid.isEmpty()) {
            return null;
        }
        List<AgentTaskEvidence> rows = st.getEvidenceRows();
        for (int i = rows.size() - 1; i >= 0; i--) {
            AgentTaskEvidence e = rows.get(i);
            if (tid.equals(e.getToolCallId())) {
                return e;
            }
        }
        return null;
    }
}
