package com.thingworx.things.agent.taskstate;

import com.thingworx.things.agent.llm.ToolCall;

/**
 * HITL continuation: replay gated-tool outcomes into a freshly primed {@link TaskProgressV1b} (v1b), after v1a seed
 * rows exist. Extracted for unit tests and a single call site in {@link com.thingworx.things.agent.AgentThing}.
 */
public final class TaskProgressV1bHitlReplay {

    private TaskProgressV1bHitlReplay() {}

    /** Approved {@code invoke_service}: correlate checklist row and apply seeded result + evidence. */
    public static void replayApprovedInvokeService(ToolCall gated, String approvedInvokeJson) {
        if (gated == null || approvedInvokeJson == null) {
            return;
        }
        if (!"invoke_service".equals(gated.getFunctionName())) {
            return;
        }
        TaskProgressV1bHooks.onBeforeTool(gated);
        TaskProgressV1bHooks.afterTrackedTool(gated, approvedInvokeJson);
    }

    /** Approved extended tool (configuration repository): correlate checklist row and apply seeded result. */
    public static void replayApprovedExtendedTool(ToolCall gated, String approvedJson) {
        if (gated == null || approvedJson == null) {
            return;
        }
        TaskProgressV1bHooks.onBeforeTool(gated);
        TaskProgressV1bHooks.afterTrackedTool(gated, approvedJson);
    }

    /** Cancel / reject terminal path: mark gated row failed using generic tool error JSON. */
    public static void replayTerminalDecision(ToolCall gated) {
        if (gated == null) {
            return;
        }
        TaskProgressV1bHooks.onBeforeTool(gated);
        TaskProgressV1bHooks.afterTrackedTool(gated, "{\"status\":\"error\"}");
    }
}
