package com.thingworx.things.agent.transform.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.analysis.HalfOpenWindow;

class G3TimeTransformTest {

    private static final ZoneId NYC = ZoneId.of("America/New_York");

    @Test
    void fixedDuration_boundaryBelongsToNextBucket() {
        Instant anchor = Instant.parse("2026-01-01T00:00:00Z");
        BucketAssigner assigner = BucketAssigner.fixedDuration(anchor, Duration.ofHours(1));
        HalfOpenWindow w = assigner.bucketOf(Instant.parse("2026-01-01T01:00:00Z"));
        assertEquals(Instant.parse("2026-01-01T01:00:00Z"), w.startInclusive());
        assertFalse(assigner.bucketOf(Instant.parse("2026-01-01T00:59:59Z")).contains(
                Instant.parse("2026-01-01T01:00:00Z")));
    }

    @Test
    void calendarDay_dstSpringForward_shortDay() {
        // US DST spring 2026-03-08 in America/New_York — day has 23 hours
        Instant mid = LocalDate.of(2026, 3, 8).atTime(12, 0).atZone(NYC).toInstant();
        BucketAssigner assigner = BucketAssigner.calendarDay("America/New_York");
        HalfOpenWindow day = assigner.bucketOf(mid);
        long hours = Duration.between(day.startInclusive(), day.endExclusive()).toHours();
        assertEquals(23L, hours);
    }

    @Test
    void calendarDay_dstFallBack_longDay() {
        // US DST fall 2026-11-01 in America/New_York — day has 25 hours
        Instant mid = LocalDate.of(2026, 11, 1).atTime(12, 0).atZone(NYC).toInstant();
        BucketAssigner assigner = BucketAssigner.calendarDay("America/New_York");
        HalfOpenWindow day = assigner.bucketOf(mid);
        long hours = Duration.between(day.startInclusive(), day.endExclusive()).toHours();
        assertEquals(25L, hours);
    }

    @Test
    void timezoneRequired_noServerDefault() {
        assertThrows(IllegalArgumentException.class, () -> BucketAssigner.calendarDay(null));
        assertThrows(IllegalArgumentException.class, () -> BucketAssigner.calendarDay(""));
    }

    @Test
    void resample_emptyBucket_nullAndZeroPolicies() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        HalfOpenWindow window = HalfOpenWindow.of(start, start.plus(Duration.ofHours(2)));
        BucketAssigner assigner = BucketAssigner.fixedDuration(start, Duration.ofHours(1));
        List<TimePoint> points = List.of(
                new TimePoint(start.plusSeconds(10), 0, 5d));
        List<BucketedValue> nullFill = Resampler.resample(points, window, assigner, Aggregation.SUM,
                MissingPolicy.NULL);
        assertEquals(2, nullFill.size());
        assertEquals(5d, nullFill.get(0).value());
        assertFalse(nullFill.get(0).synthesized());
        assertNull(nullFill.get(1).value());
        assertTrue(nullFill.get(1).synthesized());

        List<BucketedValue> zeroFill = Resampler.resample(points, window, assigner, Aggregation.SUM,
                MissingPolicy.ZERO);
        assertEquals(0d, zeroFill.get(1).value());
        assertTrue(zeroFill.get(1).synthesized());
    }

    @Test
    void resample_zeroFillRejectedForMean() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        HalfOpenWindow window = HalfOpenWindow.of(start, start.plus(Duration.ofHours(1)));
        BucketAssigner assigner = BucketAssigner.fixedDuration(start, Duration.ofHours(1));
        assertThrows(IllegalArgumentException.class,
                () -> Resampler.resample(List.of(), window, assigner, Aggregation.MEAN,
                        MissingPolicy.ZERO));
    }

    @Test
    void ordering_keepsDuplicatesVisible() {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        List<TimePoint> unordered = List.of(
                new TimePoint(t.plusSeconds(2), 2, 3d),
                new TimePoint(t, 0, 1d),
                new TimePoint(t, 1, 2d));
        List<TimePoint> ordered = TimeSeriesOrdering.sortedCopy(unordered);
        assertEquals(0L, ordered.get(0).sourceOrdinal());
        assertEquals(1L, ordered.get(1).sourceOrdinal());
        assertEquals(1, TimeSeriesOrdering.countDuplicateTimestamps(ordered));
    }

    @Test
    void firstLast_useCanonicalOrder() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        List<TimePoint> points = List.of(
                new TimePoint(t0.plusSeconds(1), 1, 20d),
                new TimePoint(t0, 0, 10d));
        assertEquals(10d, Resampler.aggregate(TimeSeriesOrdering.sortedCopy(points), Aggregation.FIRST).value);
        assertEquals(20d, Resampler.aggregate(TimeSeriesOrdering.sortedCopy(points), Aggregation.LAST).value);
    }

    @Test
    void rollingMean_observationWindow_nullAsZeroForbidden() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        List<TimePoint> points = List.of(
                new TimePoint(t0, 0, 10d),
                new TimePoint(t0.plusSeconds(1), 1, null),
                new TimePoint(t0.plusSeconds(2), 2, 20d));
        List<BucketedValue> out = RollingOperator.rollingMean(points,
                RollingOperator.WindowKind.OBSERVATION_COUNT, 2, null, 1);
        // last point window includes null + 20 → mean 20 (support 1) or with both valued...
        // window of last two: null and 20 → support 1, mean 20
        assertEquals(20d, out.get(2).value());
        assertEquals(1, out.get(2).support());
    }

    @Test
    void rateOfChange_duplicateTimeInsufficient() {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        List<RateOfChange.Sample> samples = RateOfChange.compute(List.of(
                new TimePoint(t, 0, 1d),
                new TimePoint(t, 1, 2d)));
        assertTrue(samples.get(1).insufficient());
        assertNull(samples.get(1).ratePerSecond());
    }

    @Test
    void rateOfChange_perSecond() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        List<RateOfChange.Sample> samples = RateOfChange.compute(List.of(
                new TimePoint(t0, 0, 0d),
                new TimePoint(t0.plusSeconds(2), 1, 10d)));
        assertFalse(samples.get(1).insufficient());
        assertEquals(5d, samples.get(1).ratePerSecond());
    }

    @Test
    void periodCompare_zeroDenominatorUndefinedPercent() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        HalfOpenWindow orig = HalfOpenWindow.of(t0, t0.plus(Duration.ofHours(1)));
        HalfOpenWindow cur = HalfOpenWindow.of(t0.plus(Duration.ofHours(1)), t0.plus(Duration.ofHours(2)));
        List<TimePoint> points = List.of(
                new TimePoint(t0.plusSeconds(1), 0, 0d),
                new TimePoint(t0.plus(Duration.ofHours(1)).plusSeconds(1), 1, 10d));
        PeriodComparator.Result r = PeriodComparator.compare(points, orig, cur, Aggregation.SUM);
        assertEquals(0d, r.original());
        assertEquals(10d, r.current());
        assertTrue(r.percentUndefined());
        assertNull(r.percent());
        assertEquals(10d, r.delta());
    }

    @Test
    void periodCompare_unequalWindowsVisible() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        HalfOpenWindow orig = HalfOpenWindow.of(t0, t0.plus(Duration.ofHours(1)));
        HalfOpenWindow cur = HalfOpenWindow.of(t0.plus(Duration.ofHours(1)), t0.plus(Duration.ofHours(3)));
        PeriodComparator.Result r = PeriodComparator.compare(List.of(), orig, cur, Aggregation.SUM);
        assertTrue(r.unequalOrPartial());
    }

    @Test
    void resample_unorderedInput_deterministic() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        HalfOpenWindow window = HalfOpenWindow.of(start, start.plus(Duration.ofHours(1)));
        BucketAssigner assigner = BucketAssigner.fixedDuration(start, Duration.ofHours(1));
        List<TimePoint> points = new ArrayList<>();
        points.add(new TimePoint(start.plusSeconds(30), 1, 2d));
        points.add(new TimePoint(start.plusSeconds(10), 0, 4d));
        List<BucketedValue> out = Resampler.resample(points, window, assigner, Aggregation.SUM,
                MissingPolicy.NULL);
        assertEquals(6d, out.get(0).value());
    }
}
