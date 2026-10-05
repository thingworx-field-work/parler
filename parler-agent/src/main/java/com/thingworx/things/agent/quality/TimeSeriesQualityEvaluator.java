package com.thingworx.things.agent.quality;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.transform.time.TimePoint;
import com.thingworx.things.agent.transform.time.TimeSeriesOrdering;

/**
 * Deterministic G6 quality metrics over source-order observations. Cadence-dependent metrics are
 * {@code UNKNOWN} when expected cadence is absent (B4).
 */
public final class TimeSeriesQualityEvaluator {

    private TimeSeriesQualityEvaluator() {}

    /**
     * @param sourceOrderPoints points in observed source order (not necessarily sorted); null
     *                          instants are not representable on {@link TimePoint} — pass those
     *                          via {@code missingTimestampCount}
     * @param window evaluation window; points outside are ignored for coverage/freshness support
     * @param profile validated thresholds
     * @param evaluationAnchor freshness reference instant
     * @param missingTimestampCount rows with missing/unparseable timestamps (pre-parse)
     * @param outOfSupportedRangeCount timestamps outside supported range (pre-parse)
     */
    public static QualityAssessment evaluate(
            List<TimePoint> sourceOrderPoints,
            HalfOpenWindow window,
            QualityProfile profile,
            Instant evaluationAnchor,
            long missingTimestampCount,
            long outOfSupportedRangeCount) {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(evaluationAnchor, "evaluationAnchor");
        List<TimePoint> source = sourceOrderPoints == null ? List.of() : List.copyOf(sourceOrderPoints);
        if (missingTimestampCount < 0L || outOfSupportedRangeCount < 0L) {
            throw new IllegalArgumentException("timestamp invalidity counts must be non-negative");
        }

        List<QualityFinding> findings = new ArrayList<>();

        findings.add(timestampValidity(missingTimestampCount, outOfSupportedRangeCount, source.size()));
        findings.add(duplicates(source, profile));
        findings.add(outOfOrder(source, profile));
        findings.add(freshness(source, window, profile, evaluationAnchor));
        findings.add(cadence(source, window, profile));
        findings.add(gapsCoverage(source, window, profile));
        findings.add(flatline(source, window, profile));
        findings.add(rangeViolation(source, window, profile));
        findings.add(cadenceDrift(source, window, profile));

        return QualityAssessment.of(profile.profileDigest(), findings);
    }

    public static QualityAssessment evaluate(
            List<TimePoint> sourceOrderPoints,
            HalfOpenWindow window,
            QualityProfile profile,
            Instant evaluationAnchor) {
        return evaluate(sourceOrderPoints, window, profile, evaluationAnchor, 0L, 0L);
    }

    private static QualityFinding timestampValidity(long missing, long outOfRange, long parseable) {
        long invalid = missing + outOfRange;
        QualitySeverity severity = invalid > 0L ? QualitySeverity.BLOCKING : QualitySeverity.INFO;
        return QualityFinding.builder()
                .metric(QualityMetricId.TIMESTAMP_VALIDITY)
                .severity(severity)
                .threshold("0")
                .observed(Long.toString(invalid))
                .support(parseable + invalid)
                .detail("missing=" + missing + ";outOfRange=" + outOfRange)
                .build();
    }

    private static QualityFinding duplicates(List<TimePoint> source, QualityProfile profile) {
        List<TimePoint> ordered = TimeSeriesOrdering.sortedCopy(source);
        int dups = TimeSeriesOrdering.countDuplicateTimestamps(ordered);
        QualitySeverity severity = dups > 0 ? profile.duplicateSeverity() : QualitySeverity.INFO;
        return QualityFinding.builder()
                .metric(QualityMetricId.DUPLICATES)
                .severity(severity)
                .threshold("0")
                .observed(Integer.toString(dups))
                .support(ordered.size())
                .build();
    }

    private static QualityFinding outOfOrder(List<TimePoint> source, QualityProfile profile) {
        long count = 0L;
        long maxBackwardMs = 0L;
        Instant prev = null;
        for (TimePoint p : source) {
            Instant t = p.instant();
            if (prev != null && t.isBefore(prev)) {
                count++;
                long back = Duration.between(t, prev).toMillis();
                if (back > maxBackwardMs) {
                    maxBackwardMs = back;
                }
            }
            prev = t;
        }
        QualitySeverity severity = count > 0L ? profile.outOfOrderSeverity() : QualitySeverity.INFO;
        return QualityFinding.builder()
                .metric(QualityMetricId.OUT_OF_ORDER)
                .severity(severity)
                .threshold("0")
                .observed(Long.toString(count))
                .support(source.size())
                .detail("maxBackwardMs=" + maxBackwardMs)
                .build();
    }

    private static QualityFinding freshness(
            List<TimePoint> source,
            HalfOpenWindow window,
            QualityProfile profile,
            Instant evaluationAnchor) {
        Instant latest = null;
        long support = 0L;
        for (TimePoint p : source) {
            if (!window.contains(p.instant())) {
                continue;
            }
            support++;
            if (latest == null || p.instant().isAfter(latest)) {
                latest = p.instant();
            }
        }
        Duration maxAge = profile.freshnessMaxAge();
        if (latest == null) {
            return QualityFinding.builder()
                    .metric(QualityMetricId.FRESHNESS)
                    .severity(maxAge == null ? QualitySeverity.INFO : profile.freshnessSeverity())
                    .threshold(maxAge == null ? null : maxAge.toString())
                    .observed("no_observations")
                    .support(0L)
                    .build();
        }
        Duration age = Duration.between(latest, evaluationAnchor);
        if (age.isNegative()) {
            age = Duration.ZERO;
        }
        boolean late = maxAge != null && age.compareTo(maxAge) > 0;
        return QualityFinding.builder()
                .metric(QualityMetricId.FRESHNESS)
                .severity(late ? profile.freshnessSeverity() : QualitySeverity.INFO)
                .threshold(maxAge == null ? null : maxAge.toString())
                .observed(age.toString())
                .support(support)
                .detail("latest=" + latest)
                .build();
    }

    private static QualityFinding cadence(
            List<TimePoint> source,
            HalfOpenWindow window,
            QualityProfile profile) {
        List<Long> deltas = positiveDeltasMs(inWindowOrdered(source, window));
        if (!profile.hasExpectedCadence()) {
            return QualityFinding.builder()
                    .metric(QualityMetricId.CADENCE)
                    .severity(QualitySeverity.INFO)
                    .unknown(true)
                    .observed("UNKNOWN")
                    .support(deltas.size())
                    .detail("expected cadence absent")
                    .build();
        }
        long expectedMs = profile.expectedCadence().toMillis();
        long median = median(deltas);
        String observed = deltas.isEmpty() ? "no_positive_deltas" : Duration.ofMillis(median).toString();
        boolean irregular = !deltas.isEmpty() && median > expectedMs * 2L;
        return QualityFinding.builder()
                .metric(QualityMetricId.CADENCE)
                .severity(irregular ? QualitySeverity.WARNING : QualitySeverity.INFO)
                .threshold(profile.expectedCadence().toString())
                .observed(observed)
                .support(deltas.size())
                .build();
    }

    private static QualityFinding gapsCoverage(
            List<TimePoint> source,
            HalfOpenWindow window,
            QualityProfile profile) {
        if (!profile.hasExpectedCadence()) {
            return QualityFinding.builder()
                    .metric(QualityMetricId.GAPS_COVERAGE)
                    .severity(QualitySeverity.INFO)
                    .unknown(true)
                    .observed("UNKNOWN")
                    .support(0L)
                    .detail("expected cadence absent; coverage not invented")
                    .build();
        }
        long expectedMs = profile.expectedCadence().toMillis();
        long windowMs = Duration.between(window.startInclusive(), window.endExclusive()).toMillis();
        // Half-open cadence slots anchored at window start: ceil so non-multiple windows keep the
        // terminal partial slot (e.g. [00:00,00:59) @ 5m → 12 slots, not floor(59/5)=11).
        long expectedSlots = Math.max(1L, Math.ceilDiv(windowMs, expectedMs));
        List<TimePoint> inWindow = inWindowOrdered(source, window);
        // Distinct occupied cadence-aligned slots (not raw timestamps).
        Set<Long> occupied = new HashSet<>();
        Instant anchor = window.startInclusive();
        for (TimePoint p : inWindow) {
            long offsetMs = Duration.between(anchor, p.instant()).toMillis();
            if (offsetMs < 0L) {
                continue;
            }
            long slotIndex = Math.floorDiv(offsetMs, expectedMs);
            if (slotIndex >= 0L && slotIndex < expectedSlots) {
                occupied.add(slotIndex);
            }
        }
        long observedSlots = occupied.size();
        double raw = expectedSlots == 0L ? 0d : (double) observedSlots / (double) expectedSlots;
        double coverage = Math.min(1.0d, raw);
        boolean gappy = coverage < 0.9d;
        return QualityFinding.builder()
                .metric(QualityMetricId.GAPS_COVERAGE)
                .severity(gappy ? QualitySeverity.WARNING : QualitySeverity.INFO)
                .threshold("0.9")
                .observed(String.format(Locale.ROOT, "%.4f", coverage))
                .support(observedSlots)
                .detail("expectedSlots=" + expectedSlots + ";observedSlots=" + observedSlots)
                .build();
    }

    private static QualityFinding flatline(
            List<TimePoint> source,
            HalfOpenWindow window,
            QualityProfile profile) {
        List<TimePoint> inWindow = inWindowOrdered(source, window);
        long observedRun = 0L;
        Duration observedDur = Duration.ZERO;
        boolean stuck = false;
        long run = 0L;
        Instant runStart = null;
        Double runValue = null;
        for (TimePoint p : inWindow) {
            if (!p.hasValue()) {
                run = 0L;
                runStart = null;
                runValue = null;
                continue;
            }
            if (runValue != null && Math.abs(p.value() - runValue) <= profile.flatlineEpsilon()) {
                run++;
            } else {
                run = 1L;
                runStart = p.instant();
                runValue = p.value();
            }
            Duration d = Duration.between(runStart, p.instant());
            boolean runStuck = run >= profile.flatlineMinSupport()
                    && d.compareTo(profile.flatlineMinDuration()) >= 0;
            if (runStuck) {
                stuck = true;
                if (run > observedRun
                        || (run == observedRun && d.compareTo(observedDur) > 0)) {
                    observedRun = run;
                    observedDur = d;
                }
            } else if (!stuck && (run > observedRun
                    || (run == observedRun && d.compareTo(observedDur) > 0))) {
                // Report the strongest single-run pair; never combine support/duration across runs.
                observedRun = run;
                observedDur = d;
            }
        }
        return QualityFinding.builder()
                .metric(QualityMetricId.FLATLINE)
                .severity(stuck ? profile.flatlineSeverity() : QualitySeverity.INFO)
                .threshold("support>=" + profile.flatlineMinSupport()
                        + ";duration>=" + profile.flatlineMinDuration())
                .observed("run=" + observedRun + ";duration=" + observedDur)
                .support(inWindow.size())
                .build();
    }

    private static QualityFinding rangeViolation(
            List<TimePoint> source,
            HalfOpenWindow window,
            QualityProfile profile) {
        long belowSpec = 0L;
        long aboveSpec = 0L;
        long belowControl = 0L;
        long aboveControl = 0L;
        long support = 0L;
        for (TimePoint p : inWindowOrdered(source, window)) {
            if (!p.hasValue()) {
                continue;
            }
            support++;
            double v = p.value();
            if (profile.specMin() != null && v < profile.specMin()) {
                belowSpec++;
            }
            if (profile.specMax() != null && v > profile.specMax()) {
                aboveSpec++;
            }
            if (profile.controlMin() != null && v < profile.controlMin()) {
                belowControl++;
            }
            if (profile.controlMax() != null && v > profile.controlMax()) {
                aboveControl++;
            }
        }
        long violations = belowSpec + aboveSpec + belowControl + aboveControl;
        boolean hasBounds = profile.specMin() != null || profile.specMax() != null
                || profile.controlMin() != null || profile.controlMax() != null;
        QualitySeverity severity = violations > 0L && hasBounds
                ? profile.rangeSeverity()
                : QualitySeverity.INFO;
        return QualityFinding.builder()
                .metric(QualityMetricId.RANGE_VIOLATION)
                .severity(severity)
                .threshold("spec=[" + profile.specMin() + "," + profile.specMax()
                        + "];control=[" + profile.controlMin() + "," + profile.controlMax() + "]")
                .observed(Long.toString(violations))
                .support(support)
                .detail("belowSpec=" + belowSpec + ";aboveSpec=" + aboveSpec
                        + ";belowControl=" + belowControl + ";aboveControl=" + aboveControl)
                .build();
    }

    private static QualityFinding cadenceDrift(
            List<TimePoint> source,
            HalfOpenWindow window,
            QualityProfile profile) {
        if (!profile.hasExpectedCadence()) {
            return QualityFinding.builder()
                    .metric(QualityMetricId.CADENCE_DRIFT)
                    .severity(QualitySeverity.INFO)
                    .unknown(true)
                    .observed("UNKNOWN")
                    .support(0L)
                    .build();
        }
        List<Long> deltas = positiveDeltasMs(inWindowOrdered(source, window));
        if (deltas.isEmpty()) {
            return QualityFinding.builder()
                    .metric(QualityMetricId.CADENCE_DRIFT)
                    .severity(QualitySeverity.INFO)
                    .threshold(profile.expectedCadence().toString())
                    .observed("no_positive_deltas")
                    .support(0L)
                    .build();
        }
        // Recent half of positive deltas vs expected
        int from = deltas.size() / 2;
        List<Long> recent = deltas.subList(from, deltas.size());
        long medianRecent = median(recent);
        long expectedMs = profile.expectedCadence().toMillis();
        double ratio = expectedMs == 0L ? Double.POSITIVE_INFINITY
                : (double) medianRecent / (double) expectedMs;
        boolean drift = ratio >= profile.cadenceDriftRatio() || ratio <= 1.0d / profile.cadenceDriftRatio();
        return QualityFinding.builder()
                .metric(QualityMetricId.CADENCE_DRIFT)
                .severity(drift ? QualitySeverity.WARNING : QualitySeverity.INFO)
                .threshold(Double.toString(profile.cadenceDriftRatio()))
                .observed(String.format(Locale.ROOT, "%.4f", ratio))
                .support(recent.size())
                .build();
    }

    private static List<TimePoint> inWindowOrdered(List<TimePoint> source, HalfOpenWindow window) {
        List<TimePoint> filtered = new ArrayList<>();
        for (TimePoint p : source) {
            if (window.contains(p.instant())) {
                filtered.add(p);
            }
        }
        return TimeSeriesOrdering.sortedCopy(filtered);
    }

    private static List<Long> positiveDeltasMs(List<TimePoint> ordered) {
        if (ordered.size() < 2) {
            return List.of();
        }
        List<Long> deltas = new ArrayList<>();
        Instant prev = ordered.get(0).instant();
        for (int i = 1; i < ordered.size(); i++) {
            Instant t = ordered.get(i).instant();
            long ms = Duration.between(prev, t).toMillis();
            if (ms > 0L) {
                deltas.add(ms);
            }
            prev = t;
        }
        return deltas;
    }

    private static long median(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return (sorted.get(mid - 1) + sorted.get(mid)) / 2L;
    }
}
