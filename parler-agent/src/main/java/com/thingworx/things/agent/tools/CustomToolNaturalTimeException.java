package com.thingworx.things.agent.tools;

/**
 * Thrown by {@link CustomToolDateTimePairResolver} when a custom {@code _tool_*} service exposes a recognized
 * DATETIME pair ({@code startDate}/{@code endDate} or {@code startTime}/{@code endTime}) and the LLM-supplied
 * {@code calendarPhrase} / {@code relativeDuration} / explicit-bound combination violates the cross-tool
 * natural-time contract ({@code docs/agent/time-interpretation.md} §7).
 *
 * <p>Carries the same wire-envelope triple ({@code code}, {@code message}, {@code rejectedParameter}) as
 * {@link BuiltInToolNaturalTimeWindow.Outcome} so {@link com.thingworx.things.agent.AgentThing#executeCustomTool}
 * can route it through {@link BuiltInToolTimeErrorJson#error(String, String, String)} verbatim — the LLM gets
 * the same structured retry hint it would get from {@code query_alert_history} or
 * {@code query_numeric_property_history}.</p>
 *
 * <p>Extends {@link IllegalArgumentException} so existing higher-level catch sites in
 * {@code AgentThing.executeCustomTool} still degrade safely (the dedicated catch must run before the broad
 * {@link IllegalArgumentException} catch — same pattern as {@link UnsupportedRelativeLiteralException}).</p>
 */
public final class CustomToolNaturalTimeException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final String detail;
    private final String rejectedParameter;

    public CustomToolNaturalTimeException(String code, String detail, String rejectedParameter) {
        super(detail);
        this.code = code;
        this.detail = detail;
        this.rejectedParameter = rejectedParameter;
    }

    public String getCode() {
        return code;
    }

    public String getDetail() {
        return detail;
    }

    /** {@code null} when the conflict spans multiple fields (e.g. mutual-exclusion). */
    public String getRejectedParameter() {
        return rejectedParameter;
    }
}
