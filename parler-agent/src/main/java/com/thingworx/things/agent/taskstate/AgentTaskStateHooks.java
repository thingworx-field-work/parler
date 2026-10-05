package com.thingworx.things.agent.taskstate;

import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.PromptContextCacheSnapshot;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.AgentToolContext;

/**
 * Hooks {@link AgentTaskState} updates from tool execution (v1a adapters only).
 */
public final class AgentTaskStateHooks {

    private AgentTaskStateHooks() {}

    public static boolean shouldTrack(String functionName) {
        if ("invoke_service".equals(functionName) || "fetch_cached_result".equals(functionName)) {
            return true;
        }
        AgentThing agent = AgentToolContext.getAgentThing();
        if (agent == null) {
            return false;
        }
        PromptContextCacheSnapshot snap = agent.getPromptContextSnapshot();
        return snap != null && snap.getExtendedToolRegistry().find(functionName).isPresent();
    }

    public static void beforeExecution(ToolCall toolCall) {
        if (toolCall == null || !shouldTrack(toolCall.getFunctionName())) {
            return;
        }
        AgentTaskState st = AgentToolContext.getAgentTaskState();
        if (st == null) {
            return;
        }
        st.beginTrackedTool(toolCall, toolCall.getFunctionName());
    }

    public static void afterExecution(ToolCall toolCall, String resultJson) {
        if (toolCall == null || !shouldTrack(toolCall.getFunctionName())) {
            return;
        }
        AgentTaskState st = AgentToolContext.getAgentTaskState();
        if (st == null) {
            return;
        }
        AgentTaskEvidence row = st.findRowForCompletion(toolCall);
        if (row == null) {
            return;
        }
        String fn = toolCall.getFunctionName();
        if ("invoke_service".equals(fn)) {
            TaskStateInvokeFetchParsers.applyInvokeServiceResult(row, resultJson);
        } else if ("fetch_cached_result".equals(fn)) {
            TaskStateInvokeFetchParsers.applyFetchCachedResult(row, resultJson);
        } else if (shouldTrack(fn)) {
            TaskStateInvokeFetchParsers.applyInvokeServiceResult(row, resultJson);
        }
        st.finishRow(row);
    }

    public static void markBlocked(ToolCall toolCall) {
        if (toolCall == null || !shouldTrack(toolCall.getFunctionName())) {
            return;
        }
        AgentTaskState st = AgentToolContext.getAgentTaskState();
        if (st == null) {
            return;
        }
        AgentTaskEvidence row = st.findRowForCompletion(toolCall);
        if (row == null) {
            return;
        }
        row.setStatus("blocked-by-approval");
        st.finishRow(row);
        TaskProgressV1bHooks.onBlocked(toolCall);
    }
}
