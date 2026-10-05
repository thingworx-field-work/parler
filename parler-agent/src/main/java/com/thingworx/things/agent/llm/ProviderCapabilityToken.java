package com.thingworx.things.agent.llm;

/**
 * Frozen v1 Provider capability tokens for route eligibility (U7 D13).
 * Missing effective metadata ⇒ route ineligible (fail closed).
 */
public enum ProviderCapabilityToken {
    TOOLS,
    STRICT_JSON_SCHEMA,
    HITL_CONTINUATION,
    CONTEXT_WINDOW,
    DATA_EGRESS_REGION,
    STRUCTURED_OUTPUT
}
