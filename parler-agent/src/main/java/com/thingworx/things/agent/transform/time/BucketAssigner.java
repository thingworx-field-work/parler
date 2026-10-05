package com.thingworx.things.agent.transform.time;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.analysis.TimeAxisNormalizer;

/**
 * Half-open bucket assignment. Boundary points at {@code endExclusive} belong to the next bucket.
 */
public final class BucketAssigner {

    private final BucketKind kind;
    private final Duration fixedDuration;
    private final Instant fixedAnchor;
    private final ZoneId zone;

    private BucketAssigner(BucketKind kind, Duration fixedDuration, Instant fixedAnchor, ZoneId zone) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.fixedDuration = fixedDuration;
        this.fixedAnchor = fixedAnchor;
        this.zone = zone;
    }

    public static BucketAssigner fixedDuration(Instant anchor, Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("WINDOW_INVALID");
        }
        return new BucketAssigner(BucketKind.FIXED_DURATION, duration,
                Objects.requireNonNull(anchor, "anchor"), null);
    }

    public static BucketAssigner calendarDay(String ianaZoneId) {
        return new BucketAssigner(BucketKind.CALENDAR_DAY, null, null,
                TimeAxisNormalizer.requireZone(ianaZoneId));
    }

    public static BucketAssigner calendarHour(String ianaZoneId) {
        return new BucketAssigner(BucketKind.CALENDAR_HOUR, null, null,
                TimeAxisNormalizer.requireZone(ianaZoneId));
    }

    public BucketKind kind() {
        return kind;
    }

    public ZoneId zone() {
        return zone;
    }

    /** Bucket containing {@code instant} as {@code [start, end)}. */
    public HalfOpenWindow bucketOf(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        switch (kind) {
            case FIXED_DURATION:
                return fixedBucket(instant);
            case CALENDAR_DAY:
                return calendarDayBucket(instant);
            case CALENDAR_HOUR:
                return calendarHourBucket(instant);
            default:
                throw new IllegalStateException("unknown bucket kind");
        }
    }

    private HalfOpenWindow fixedBucket(Instant instant) {
        long nanos = fixedDuration.toNanos();
        long offset = Duration.between(fixedAnchor, instant).toNanos();
        long index = Math.floorDiv(offset, nanos);
        Instant start = fixedAnchor.plusNanos(index * nanos);
        Instant end = start.plus(fixedDuration);
        return HalfOpenWindow.of(start, end);
    }

    private HalfOpenWindow calendarDayBucket(Instant instant) {
        ZonedDateTime zdt = instant.atZone(zone);
        LocalDate day = zdt.toLocalDate();
        Instant start = day.atStartOfDay(zone).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(zone).toInstant();
        return HalfOpenWindow.of(start, end);
    }

    private HalfOpenWindow calendarHourBucket(Instant instant) {
        ZonedDateTime zdt = instant.atZone(zone).withMinute(0).withSecond(0).withNano(0);
        Instant start = zdt.toInstant();
        Instant end = zdt.plusHours(1).toInstant();
        return HalfOpenWindow.of(start, end);
    }

    /** Advance one bucket after {@code current}'s start. */
    public HalfOpenWindow nextBucket(HalfOpenWindow current) {
        Objects.requireNonNull(current, "current");
        Instant probe = current.endExclusive();
        return bucketOf(probe);
    }
}
