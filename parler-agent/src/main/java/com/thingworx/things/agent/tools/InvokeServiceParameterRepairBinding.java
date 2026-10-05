package com.thingworx.things.agent.tools;

import com.thingworx.things.agent.llm.ToolCall;

/**
 * Carries pre-HITL {@link InvokeServiceParameterNormalizer.Repair} across args rewrite so
 * {@link InvokeServiceExecutor} can still emit {@code parametersNormalized} when the executor sees an
 * already-repaired {@code ToolCall}. No static logger — safe for plain JUnit.
 */
public final class InvokeServiceParameterRepairBinding {

    private static final ThreadLocal<String> PENDING_TOOL_ID = new ThreadLocal<>();
    private static final ThreadLocal<InvokeServiceParameterNormalizer.Repair> PENDING_REPAIR = new ThreadLocal<>();

    private InvokeServiceParameterRepairBinding() {}

    public static void bind(String toolCallId, InvokeServiceParameterNormalizer.Repair repair) {
        if (toolCallId == null || toolCallId.isEmpty()
                || repair == null || !repair.isRepaired()) {
            return;
        }
        PENDING_TOOL_ID.set(toolCallId);
        PENDING_REPAIR.set(repair);
    }

    public static void unbind() {
        PENDING_TOOL_ID.remove();
        PENDING_REPAIR.remove();
    }

    /**
     * Prefer executor-side repair when it moved fields; otherwise use bound pre-HITL repair for the same
     * {@link ToolCall#getId()}.
     */
    public static InvokeServiceParameterNormalizer.Repair applyIfAny(
            InvokeServiceParameterNormalizer.Repair executorRepair, ToolCall call) {
        InvokeServiceParameterNormalizer.Repair exec =
                executorRepair != null ? executorRepair : InvokeServiceParameterNormalizer.Repair.none();
        if (exec.isRepaired()) {
            return exec;
        }
        String tid = PENDING_TOOL_ID.get();
        InvokeServiceParameterNormalizer.Repair b = PENDING_REPAIR.get();
        if (tid != null && call != null && tid.equals(call.getId()) && b != null && b.isRepaired()) {
            return b;
        }
        return InvokeServiceParameterNormalizer.Repair.none();
    }
}
