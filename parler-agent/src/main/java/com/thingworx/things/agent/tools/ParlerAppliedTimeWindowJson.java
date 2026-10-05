package com.thingworx.things.agent.tools;

import java.time.Instant;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * §6 {@code applied_time_window} payload for tools that execute a bounded query. Platform inclusive/exclusive edge
 * semantics are often unverified — emit {@code requested_semantics_only: true}.
 *
 * <p><b>v1:</b> {@code requested_semantics_only} is always {@code true} until per-service boundary audits exist
 * ({@code docs/agent/time-interpretation.md} §4.4). Flip to {@code false} only when documented verification allows
 * confident inclusive/exclusive claims.</p>
 */
public final class ParlerAppliedTimeWindowJson {

    private ParlerAppliedTimeWindowJson() {}

    /**
     * Closed-open Parler intent {@code [start_utc, end_utc)} for range tools.
     *
     * @param source               tool-specific or §6 source label (e.g. {@code EXPLICIT_ISO},
     *                             {@code NATURAL_LANGUAGE_CALENDAR_DAY})
     * @param timezoneBasisOrNull  optional IANA id when resolution used {@code user_timezone}
     */
    public static void putClosedOpenWindow(ObjectNode parent, String source, Instant startUtc, Instant endUtc,
            String timezoneBasisOrNull) {
        ObjectNode w = parent.putObject("applied_time_window");
        w.put("source", source);
        w.put("start_utc", startUtc.toString());
        w.put("end_utc", endUtc.toString());
        // Always true in v1 — see class Javadoc.
        w.put("requested_semantics_only", true);
        if (timezoneBasisOrNull != null && !timezoneBasisOrNull.isBlank()) {
            w.put("timezone_basis", timezoneBasisOrNull.trim());
        }
    }
}
