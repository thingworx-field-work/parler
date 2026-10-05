package com.thingworx.things.agent.tools;

import com.thingworx.things.agent.llm.ToolCall;

/**
 * Carries pre-HITL {@code invoke_service} {@link ServiceTargetEntityTypeResolution} across args rewrite so
 * {@link InvokeServiceExecutor} can still emit {@code entityTypeNormalized}. No static logger — safe for plain JUnit.
 */
public final class InvokeServiceEntityTypeNormalization {

    private static final ThreadLocal<String> PENDING_TOOL_ID = new ThreadLocal<>();
    private static final ThreadLocal<ServiceTargetEntityTypeResolution> PENDING_RES = new ThreadLocal<>();

    private InvokeServiceEntityTypeNormalization() {}

    public static void bind(String toolCallId, ServiceTargetEntityTypeResolution normalizedResolution) {
        if (toolCallId == null || toolCallId.isEmpty()
                || normalizedResolution == null || !normalizedResolution.isNormalized()) {
            return;
        }
        PENDING_TOOL_ID.set(toolCallId);
        PENDING_RES.set(normalizedResolution);
    }

    public static void unbind() {
        PENDING_TOOL_ID.remove();
        PENDING_RES.remove();
    }

    /** When args were rewritten to {@code Thing}, merge bound normalization metadata for serializers. */
    public static ServiceTargetEntityTypeResolution applyIfAny(ServiceTargetEntityTypeResolution resolved,
            ToolCall call) {
        if (resolved == null || resolved.isNormalized()) {
            return resolved;
        }
        String tid = PENDING_TOOL_ID.get();
        ServiceTargetEntityTypeResolution b = PENDING_RES.get();
        if (tid != null && call != null && tid.equals(call.getId()) && b != null && b.isNormalized()) {
            return b;
        }
        return resolved;
    }
}
