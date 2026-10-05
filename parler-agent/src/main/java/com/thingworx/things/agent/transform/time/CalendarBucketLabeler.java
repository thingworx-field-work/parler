package com.thingworx.things.agent.transform.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * CF-03 {@code calendar_bucket_v1}: the local calendar day or hour an instant lies in. A bucket is the maximal
 * contiguous interval of instants sharing one local key: the local date for a day, (local date, local hour,
 * offset) for an hour. Instant to local time is unique, but bucket edges derived from a local date or hour are
 * not: a rollback after midnight makes a date recur, and a sub-hour transition cuts an hour short. Every bucket
 * contains its instant, buckets never overlap and they tile the time axis. Design:
 * {@code docs/agent/nearterm/computing-enhancement.md} CE-4.
 */
public final class CalendarBucketLabeler {

    public static final String METHOD_ID = "calendar_bucket_v1";

    public static final String TIME_ZONE_INVALID = "TIME_ZONE_INVALID";
    public static final String CALENDAR_BUCKET_INVALID = "CALENDAR_BUCKET_INVALID";

    private static final Pattern FIXED_OFFSET_ID = Pattern.compile("^(UTC|GMT|UT)[+-].*");
    private static final DateTimeFormatter HOUR_LABEL = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm");

    public enum Granularity {
        DAY("day"),
        HOUR("hour");

        private final String wireName;

        Granularity(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Granularity fromWire(String raw) {
            for (Granularity g : values()) {
                if (g.wireName.equals(raw)) {
                    return g;
                }
            }
            throw new MeasurementException(CALENDAR_BUCKET_INVALID, "calendarBucket must be day or hour, got " + raw);
        }
    }

    /** One contiguous occurrence of a local key. The label is the key; a recurring date shares its label. */
    public static final class Bucket {
        private final Instant start;
        private final Instant end;
        private final String label;

        Bucket(Instant start, Instant end, String label) {
            this.start = start;
            this.end = end;
            this.label = label;
        }

        public Instant start() {
            return start;
        }

        public Instant end() {
            return end;
        }

        public String label() {
            return label;
        }

        public long seconds() {
            return end.getEpochSecond() - start.getEpochSecond();
        }
    }

    private CalendarBucketLabeler() {}

    /**
     * An IANA region id or {@code UTC}; never the server default. A fixed offset carries no daylight-saving
     * rule and would put rows of a switch day on the wrong local date, so it is refused.
     */
    public static ZoneId requireRegionZone(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new MeasurementException(TIME_ZONE_INVALID, "timeZone is required: an IANA zone id or UTC");
        }
        String id = raw.trim();
        ZoneId zone;
        try {
            zone = ZoneId.of(id);
        } catch (Exception e) {
            throw new MeasurementException(TIME_ZONE_INVALID,
                    "timeZone must be an IANA zone id such as Europe/Berlin, or UTC; got " + id);
        }
        if (zone instanceof ZoneOffset || FIXED_OFFSET_ID.matcher(zone.getId()).matches()
                || namesAnOffsetNotAPlace(zone)) {
            throw new MeasurementException(TIME_ZONE_INVALID,
                    "timeZone must be an IANA zone id or UTC, not a fixed offset: " + id);
        }
        return zone;
    }

    /**
     * {@code Etc/GMT-2}, {@code Etc/GMT+5} or {@code SystemV/EST5} are region ids in form only: the name stands
     * for one non-zero offset and its rules never change. Zero-offset aliases of UTC and geographical ids stay
     * admitted, a place whose rules happen to have no transition included.
     */
    private static boolean namesAnOffsetNotAPlace(ZoneId zone) {
        ZoneRules rules = zone.getRules();
        if (!rules.isFixedOffset() || rules.getOffset(Instant.EPOCH).getTotalSeconds() == 0) {
            return false;
        }
        String id = zone.getId();
        return id.startsWith("Etc/") || id.startsWith("SystemV/") || id.indexOf('/') < 0;
    }

    public static Bucket bucketOf(ZoneId zone, Instant instant, Granularity granularity) {
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(instant, "instant");
        return granularity == Granularity.DAY ? dayOf(zone.getRules(), instant) : hourOf(zone.getRules(), instant);
    }

    /**
     * Nominal edges come from the local hour and the instant's own offset; the nearest offset transitions clip
     * them. A transition exactly at the instant opens its bucket.
     */
    private static Bucket hourOf(ZoneRules rules, Instant t) {
        ZoneOffset offset = rules.getOffset(t);
        LocalDateTime localHour = LocalDateTime.ofInstant(t, offset).truncatedTo(ChronoUnit.HOURS);
        Instant start = localHour.toInstant(offset);
        Instant end = start.plusSeconds(3_600L);
        ZoneOffsetTransition previous = rules.previousTransition(t.plusNanos(1L));
        if (previous != null && previous.getInstant().isAfter(start)) {
            start = previous.getInstant();
        }
        ZoneOffsetTransition next = rules.nextTransition(t);
        if (next != null && next.getInstant().isBefore(end)) {
            end = next.getInstant();
        }
        return new Bucket(start, end, HOUR_LABEL.format(localHour) + offset.getId());
    }

    /**
     * Walks the offset segments around the instant. The comparisons are strict: a rollback landing exactly on
     * local midnight makes the midnight instant coincide with the transition, and then the local date on the
     * other side of the transition decides whether the date continues there.
     */
    private static Bucket dayOf(ZoneRules rules, Instant t) {
        LocalDate date = LocalDateTime.ofInstant(t, rules.getOffset(t)).toLocalDate();

        Instant start;
        Instant cursor = t;
        while (true) {
            Instant midnight = date.atStartOfDay().toInstant(rules.getOffset(cursor));
            ZoneOffsetTransition opening = rules.previousTransition(cursor.plusNanos(1L));
            if (opening == null || midnight.isAfter(opening.getInstant())) {
                start = midnight;
                break;
            }
            Instant justBefore = opening.getInstant().minusNanos(1L);
            if (!LocalDateTime.ofInstant(justBefore, opening.getOffsetBefore()).toLocalDate().equals(date)) {
                start = opening.getInstant();
                break;
            }
            cursor = justBefore;
        }

        Instant end;
        cursor = t;
        while (true) {
            Instant nextMidnight = date.plusDays(1L).atStartOfDay().toInstant(rules.getOffset(cursor));
            ZoneOffsetTransition closing = rules.nextTransition(cursor);
            if (closing == null || nextMidnight.isBefore(closing.getInstant())) {
                end = nextMidnight;
                break;
            }
            if (!LocalDateTime.ofInstant(closing.getInstant(), closing.getOffsetAfter()).toLocalDate().equals(date)) {
                end = closing.getInstant();
                break;
            }
            cursor = closing.getInstant();
        }
        return new Bucket(start, end, date.toString());
    }
}
