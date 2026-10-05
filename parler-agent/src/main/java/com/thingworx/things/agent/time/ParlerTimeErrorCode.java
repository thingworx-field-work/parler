package com.thingworx.things.agent.time;

/**
 * Stable error codes for {@link ParlerTimeResolver}. This enum is a <strong>v1 subset</strong> of
 * {@code docs/agent/time-interpretation.md} §7 — codes for point-with-tolerance, caps, legacy-bound conflicts,
 * {@code invoke_service} DATETIME defense, and related paths ship as those features land.
 */
public enum ParlerTimeErrorCode {

    INVALID_TIME_SPEC_SHAPE,
    INVALID_DURATION_GRAMMAR,
    UNSUPPORTED_UNIT,
    UNSUPPORTED_CALENDAR_PHRASE,
    ANCHOR_AND_ANCHOR_UTC_CONFLICT,
    RANGE_START_AFTER_END
}
