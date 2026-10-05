package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Wire-error JSON envelopes for {@code invoke_service}. Carries machine-readable error metadata
 * for {@code UNSUPPORTED_RELATIVE_LITERAL} so the LLM can surgically retry without parsing English prose.
 * Lives in its own file so the wire shape is offline-unit-testable
 * without {@link InvokeServiceExecutor}'s {@code LogUtilities} static-init dependency.
 *
 * <p>Wire fields:</p>
 * <ul>
 *   <li>{@code status}        — always {@code "error"}.</li>
 *   <li>{@code code}          — wire error code; for this envelope: {@code UNSUPPORTED_RELATIVE_LITERAL}.</li>
 *   <li>{@code message}       — English detail intended for human readers / LLM context.</li>
 *   <li>{@code rejectedParameter} — path-style parameter name (e.g. {@code payload.startDate} for object-bag
 *       rejections, or just {@code startDate} for scalar / declared-DATETIME slots).</li>
 *   <li>{@code rejectionReason}   — stable {@link InvokeServiceDatetimeLiteralDefense.RejectionReason}
 *       classifier ({@code CALENDAR_OR_WALL_CLOCK} / {@code INFORMAL_RELATIVE} / {@code DAY_TOKEN}).</li>
 * </ul>
 *
 * <p><b>What is intentionally NOT on the wire:</b> the raw rejected value. PII risk is low for DATETIME
 * literals but non-zero (LLM may forward user free-text); the truncated value lives in the INFO log only.</p>
 */
public final class InvokeServiceErrorJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InvokeServiceErrorJson() {}

    /**
     * Build the wire JSON for a {@link UnsupportedRelativeLiteralException}. Always returns valid JSON;
     * if Jackson serialization fails for any reason, falls back to a minimal hand-built error envelope so
     * the caller never has to handle a checked exception inline at the catch site.
     *
     * <p>Public so
     * {@link com.thingworx.things.agent.AgentThing#executeCustomTool} can route custom-tool DATETIME
     * defense exceptions through the same wire envelope as {@code invoke_service}, so custom tools do not
     * fall back to a string-only error.</p>
     */
    public static String unsupportedRelativeLiteral(UnsupportedRelativeLiteralException e) {
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", "UNSUPPORTED_RELATIVE_LITERAL");
            o.put("message", e.getDetail() == null ? "" : e.getDetail());
            if (e.getParamName() != null) {
                o.put("rejectedParameter", e.getParamName());
            }
            if (e.getRejectionReason() != null) {
                o.put("rejectionReason", e.getRejectionReason().name());
            }
            return MAPPER.writeValueAsString(o);
        } catch (Exception serFail) {
            return "{\"status\":\"error\",\"code\":\"UNSUPPORTED_RELATIVE_LITERAL\","
                    + "\"message\":\"serialization failed\"}";
        }
    }

    /**
     * Truncate a string for log emission without throwing on null. Mirrors the long-standing private helper
     * in {@link InvokeServiceExecutor}; extracted so it is exercisable in plain JUnit and so future wire-envelope helpers in this file can reuse it.
     *
     * @param s   value to truncate; {@code null} returns {@code ""}
     * @param max maximum length to keep before appending {@code "..."}
     */
    public static String truncateForLog(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
