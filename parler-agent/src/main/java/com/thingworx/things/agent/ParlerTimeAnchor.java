package com.thingworx.things.agent;

import java.time.Instant;
import java.util.Objects;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.ISODateTimeFormat;

/** Stable time guidance and deterministic widget-IANA ↔ UTC value formatting for Parler. */
public final class ParlerTimeAnchor {

    public static final String STABLE_TIME_GUIDANCE = "## Time interpretation\n\n"
            + "- Use the user's explicit timezone; otherwise interpret relative times, bare dates and clock times with "
            + "no zone in user_timezone. A local calendar day runs from local midnight using that date's actual offset, "
            + "which changes across daylight-saving transitions; never assume a fixed offset. Decide what the window "
            + "means first, then convert to UTC for the call without moving the day boundary; never substitute the UTC "
            + "day for it.\n"
            + "- Explicit dates and user corrections take precedence. For omitted date parts, inherit the period "
            + "established for the same topic or follow-up; use supplied current-time context only for parts not "
            + "established there. Keep the resolved window consistent across related queries, updating affected "
            + "queries when the user corrects it.\n"
            + "- Resolve relative times against supplied current time unless the user specifies another anchor; do not "
            + "invent the current date. Prefer supported calendarPhrase / relativeDuration arguments when they express "
            + "the intended window and anchor. Otherwise derive startTime/endTime from the resolved window and use "
            + "ISO-8601 UTC with Z unless the tool requires otherwise.\n"
            + "- A supplied user_timezone is the zone for all unqualified times: do not query or offer an alternative "
            + "zone to rule out ambiguity, and do not ask which zone applies. When user_timezone is absent and no "
            + "documented fallback applies, state the timezone used or ask. "
            + "Whether the end bound is inclusive or exclusive follows the target service's own contract; do not apply "
            + "one convention to every service.";

    private ParlerTimeAnchor() {}

    /**
     * @return canonical IANA id for Joda, or {@code null} if absent / unknown
     */
    public static String normalizeIanaOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return DateTimeZone.forID(t).getID();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Formats the volatile values for one provider round. UTC is unconditional; local values are included only when
     * {@code canonicalIanaId} normalizes successfully. Joda stays at the ThingWorx boundary.
     */
    public static String formatTimeValues(String canonicalIanaId, Instant nowUtc) {
        Objects.requireNonNull(nowUtc, "nowUtc");
        DateTime utc = new DateTime(nowUtc.toEpochMilli(), DateTimeZone.UTC);
        String utcStr = ISODateTimeFormat.dateTime().withZone(DateTimeZone.UTC).print(utc);
        StringBuilder out = new StringBuilder("- now_utc: ").append(utcStr);
        String normalized = normalizeIanaOrNull(canonicalIanaId);
        if (normalized != null) {
            DateTimeZone z = DateTimeZone.forID(normalized);
            DateTime nowLocal = utc.withZone(z);
            String localStr = ISODateTimeFormat.dateTime().withZone(z).print(nowLocal);
            out.append("\n- now_local: ").append(localStr)
                    .append("\n- user_timezone: ").append(z.getID());
        }
        return out.toString();
    }
}
