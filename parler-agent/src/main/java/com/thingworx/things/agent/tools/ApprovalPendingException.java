package com.thingworx.things.agent.tools;

/**
 * Thrown when a gated tool (e.g. {@code set_property_value}, {@code invoke_service}) must pause the agent loop for human approval.
 * {@link com.thingworx.things.agent.AgentLoop} converts this into {@code AgentResult.Status.AWAITING_APPROVAL}.
 */
public class ApprovalPendingException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String pendingId;

    public ApprovalPendingException(String pendingId) {
        super("Awaiting approval: pending_id=" + pendingId);
        this.pendingId = pendingId != null ? pendingId : "";
    }

    public String getPendingId() {
        return pendingId;
    }
}
