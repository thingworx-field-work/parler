package com.thingworx.things.agent.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.analysis.AnalysisEnvelope;
import com.thingworx.things.agent.analysis.AnalysisEnvelopeBuilder;
import com.thingworx.things.agent.analysis.AnalysisMethodDescriptor;
import com.thingworx.things.agent.analysis.AnalysisOperation;
import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;
import com.thingworx.things.agent.transform.time.TimePoint;

class G6QualityAssessmentTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void perfectSeries_noBlockingOrWarning() {
        HalfOpenWindow window = HalfOpenWindow.of(T0, T0.plus(Duration.ofHours(1)));
        List<TimePoint> points = varying(T0, Duration.ofMinutes(5), 12);
        QualityProfile profile = cadenceProfile(Duration.ofMinutes(5))
                .freshnessMaxAge(Duration.ofMinutes(10))
                .flatlineMinSupport(100)
                .build();
        QualityAssessment a = TimeSeriesQualityEvaluator.evaluate(
                points, window, profile, T0.plus(Duration.ofHours(1)));
        assertFalse(a.hasBlocking());
        for (QualityFinding f : a.findings()) {
            assertEquals(QualitySeverity.INFO, f.severity(), f.metric().name());
            assertFalse(f.unknown(), f.metric().name());
        }
        assertEquals("1.0000", a.finding(QualityMetricId.GAPS_COVERAGE).observed());
    }

    @Test
    void cadenceDrift_demoProfile_warnsOnlyOnDriftFactor() {
        HalfOpenWindow window = HalfOpenWindow.of(T0, T0.plus(Duration.ofHours(2)));
        QualityProfile profile = DemoQualityAppProfile.defaultProfile();
        Instant asOf = T0.plus(Duration.ofHours(2));

        QualityFinding onCadence = TimeSeriesQualityEvaluator
                .evaluate(varying(T0, Duration.ofMinutes(5), 24), window, profile, asOf)
                .finding(QualityMetricId.CADENCE_DRIFT);
        assertEquals(QualitySeverity.INFO, onCadence.severity());
        assertEquals("1.0000", onCadence.observed());

        QualityFinding slightlySlow = TimeSeriesQualityEvaluator
                .evaluate(varying(T0, Duration.ofMinutes(7), 17), window, profile, asOf)
                .finding(QualityMetricId.CADENCE_DRIFT);
        assertEquals(QualitySeverity.INFO, slightlySlow.severity());

        QualityFinding slow = TimeSeriesQualityEvaluator
                .evaluate(varying(T0, Duration.ofMinutes(10), 12), window, profile, asOf)
                .finding(QualityMetricId.CADENCE_DRIFT);
        assertEquals(QualitySeverity.WARNING, slow.severity());
        assertEquals("2.0000", slow.observed());

        QualityFinding fast = TimeSeriesQualityEvaluator
                .evaluate(varying(T0, Duration.ofSeconds(150), 48), window, profile, asOf)
                .finding(QualityMetricId.CADENCE_DRIFT);
        assertEquals(QualitySeverity.WARNING, fast.severity());
        assertEquals("0.5000", fast.observed());
    }

    @Test
    void gapsCoverage_nonMultipleWindow_usesCeilExpectedSlots() {
        // [00:00, 00:59) @ 5m: floor(59/5)=11 would hide the 12th slot; ceil → 12.
        HalfOpenWindow window = HalfOpenWindow.of(T0, T0.plus(Duration.ofMinutes(59)));
        List<TimePoint> full = varying(T0, Duration.ofMinutes(5), 12);
        QualityProfile profile = cadenceProfile(Duration.ofMinutes(5))
                .flatlineMinSupport(100)
                .build();
        QualityFinding fullCov = TimeSeriesQualityEvaluator
                .evaluate(full, window, profile, T0.plus(Duration.ofHours(1)))
                .finding(QualityMetricId.GAPS_COVERAGE);
        assertEquals("1.0000", fullCov.observed());
        assertTrue(fullCov.detail().contains("expectedSlots=12"));
        assertTrue(fullCov.detail().contains("observedSlots=12"));

        // Missing terminal slot 00:55 → 11/12, not floor-bug 11/11 = 1.0000.
        List<TimePoint> missingTerminal = varying(T0, Duration.ofMinutes(5), 11);
        QualityFinding gappy = TimeSeriesQualityEvaluator
                .evaluate(missingTerminal, window, profile, T0.plus(Duration.ofHours(1)))
                .finding(QualityMetricId.GAPS_COVERAGE);
        assertEquals("0.9167", gappy.observed());
        assertTrue(gappy.detail().contains("expectedSlots=12"));
        assertTrue(gappy.detail().contains("observedSlots=11"));
    }

    @Test
    void gapsCoverage_finerThanCadence_countsOccupiedSlotsNotRawTimestamps() {
        // Window is one expected slot; two raw samples in that slot must not inflate coverage > 1.
        HalfOpenWindow window = HalfOpenWindow.of(T0, T0.plus(Duration.ofMinutes(5)));
        List<TimePoint> points = List.of(
                p(T0.plusSeconds(60), 0, 1d),
                p(T0.plusSeconds(180), 1, 2d));
        QualityProfile profile = cadenceProfile(Duration.ofMinutes(5))
                .flatlineMinSupport(100)
                .build();
        QualityFinding cov = TimeSeriesQualityEvaluator
                .evaluate(points, window, profile, T0.plus(Duration.ofMinutes(5)))
                .finding(QualityMetricId.GAPS_COVERAGE);
        assertEquals("1.0000", cov.observed());
        assertTrue(cov.detail().contains("expectedSlots=1"));
        assertTrue(cov.detail().contains("observedSlots=1"));
    }

    @Test
    void irregularCadence_warns() {
        HalfOpenWindow window = HalfOpenWindow.of(T0, T0.plus(Duration.ofHours(1)));
        List<TimePoint> points = List.of(
                p(T0, 0, 1d),
                p(T0.plus(Duration.ofMinutes(5)), 1, 1d),
                p(T0.plus(Duration.ofMinutes(40)), 2, 1d));
        QualityProfile profile = cadenceProfile(Duration.ofMinutes(5)).build();
        QualityAssessment a = TimeSeriesQualityEvaluator.evaluate(
                points, window, profile, T0.plus(Duration.ofHours(1)));
        assertEquals(QualitySeverity.WARNING, a.finding(QualityMetricId.CADENCE).severity());
    }

    @Test
    void constantFlatline_blocking() {
        HalfOpenWindow window = HalfOpenWindow.of(T0, T0.plus(Duration.ofMinutes(30)));
        List<TimePoint> points = regular(T0, Duration.ofMinutes(1), 20, 42d);
        QualityProfile profile = QualityProfile.builder()
                .profileDigest("flat-v1")
                .flatlineEpsilon(0.01d)
                .flatlineMinSupport(5)
                .flatlineMinDuration(Duration.ofMinutes(5))
                .flatlineSeverity(QualitySeverity.BLOCKING)
                .build();
        QualityAssessment a = TimeSeriesQualityEvaluator.evaluate(
                points, window, profile, T0.plus(Duration.ofMinutes(30)));
        assertTrue(a.hasBlocking());
        assertEquals(QualitySeverity.BLOCKING, a.finding(QualityMetricId.FLATLINE).severity());
    }

    @Test
    void flatline_splitRuns_doNotCombineSupportAndDuration() {
        // Run A: support 3 over 2s (meets support, not duration). Run B: support 2 over 15m
        // (meets duration, not support). Neither run is stuck alone.
        HalfOpenWindow window = HalfOpenWindow.of(T0, T0.plus(Duration.ofHours(1)));
        List<TimePoint> points = List.of(
                p(T0, 0, 10d),
                p(T0.plusSeconds(1), 1, 10d),
                p(T0.plusSeconds(2), 2, 10d),
                p(T0.plus(Duration.ofMinutes(20)), 3, 99d),
                p(T0.plus(Duration.ofMinutes(35)), 4, 99d));
        QualityProfile profile = QualityProfile.builder()
                .profileDigest("flat-split-v1")
                .flatlineEpsilon(0.01d)
                .flatlineMinSupport(3)
                .flatlineMinDuration(Duration.ofMinutes(5))
                .flatlineSeverity(QualitySeverity.BLOCKING)
                .build();
        QualityAssessment a = TimeSeriesQualityEvaluator.evaluate(
                points, window, profile, T0.plus(Duration.ofHours(1)));
        assertEquals(QualitySeverity.INFO, a.finding(QualityMetricId.FLATLINE).severity());
        assertFalse(a.hasBlocking());
    }

    @Test
    void qualityProfile_rejectsInvalidThresholds() {
        assertThrows(IllegalArgumentException.class, () -> QualityProfile.builder()
                .profileDigest(" ")
                .build());
        assertThrows(IllegalArgumentException.class, () -> QualityProfile.builder()
                .expectedCadence(Duration.ZERO)
                .build());
        assertThrows(IllegalArgumentException.class, () -> QualityProfile.builder()
                .expectedCadence(Duration.ofMinutes(-1))
                .build());
        assertThrows(IllegalArgumentException.class, () -> QualityProfile.builder()
                .freshnessMaxAge(Duration.ofMinutes(-1))
                .build());
        assertThrows(IllegalArgumentException.class, () -> QualityProfile.builder()
                .flatlineEpsilon(-1d)
                .build());
        assertThrows(IllegalArgumentException.class, () -> QualityProfile.builder()
                .flatlineEpsilon(Double.NaN)
                .build());
        assertThrows(IllegalArgumentException.class, () -> QualityProfile.builder()
                .flatlineMinDuration(Duration.ofMinutes(-1))
                .build());
        assertThrows(IllegalArgumentException.class, () -> QualityProfile.builder()
                .flatlineMinSupport(0)
                .build());
        assertThrows(IllegalArgumentException.class, () -> QualityProfile.builder()
                .cadenceDriftRatio(0d)
                .build());
        assertThrows(IllegalArgumentException.class, () -> QualityProfile.builder()
                .cadenceDriftRatio(Double.NaN)
                .build());
        assertThrows(IllegalArgumentException.class, () -> QualityProfile.builder()
                .cadenceDriftRatio(1d)
                .build());
        assertThrows(IllegalArgumentException.class, () -> QualityProfile.builder()
                .cadenceDriftRatio(0.5d)
                .build());
        assertThrows(IllegalArgumentException.class, () -> QualityProfile.builder()
                .specMin(10d)
                .specMax(1d)
                .build());
        assertThrows(IllegalArgumentException.class, () -> QualityProfile.builder()
                .controlMin(Double.POSITIVE_INFINITY)
                .build());
        // Valid absences still build.
        QualityProfile absentCadence = QualityProfile.builder().profileDigest("ok-v1").build();
        assertFalse(absentCadence.hasExpectedCadence());
    }

    @Test
    void lateFreshness_blocking() {
        HalfOpenWindow window = HalfOpenWindow.of(T0, T0.plus(Duration.ofHours(1)));
        List<TimePoint> points = List.of(
                p(T0, 0, 1d),
                p(T0.plus(Duration.ofMinutes(10)), 1, 2d));
        QualityProfile profile = QualityProfile.builder()
                .profileDigest("fresh-v1")
                .freshnessMaxAge(Duration.ofMinutes(5))
                .freshnessSeverity(QualitySeverity.BLOCKING)
                .build();
        QualityAssessment a = TimeSeriesQualityEvaluator.evaluate(
                points, window, profile, T0.plus(Duration.ofHours(1)));
        assertEquals(QualitySeverity.BLOCKING, a.finding(QualityMetricId.FRESHNESS).severity());
        assertTrue(a.hasBlocking());
    }

    @Test
    void duplicateTimestamps_warn() {
        HalfOpenWindow window = HalfOpenWindow.of(T0, T0.plus(Duration.ofHours(1)));
        List<TimePoint> points = List.of(
                p(T0, 0, 1d),
                p(T0, 1, 2d),
                p(T0.plusSeconds(60), 2, 3d));
        QualityProfile profile = QualityProfile.builder().profileDigest("dup-v1").build();
        QualityAssessment a = TimeSeriesQualityEvaluator.evaluate(
                points, window, profile, T0.plus(Duration.ofHours(1)));
        assertEquals("1", a.finding(QualityMetricId.DUPLICATES).observed());
        assertEquals(QualitySeverity.WARNING, a.finding(QualityMetricId.DUPLICATES).severity());
    }

    @Test
    void outOfOrder_countsBackwardDisplacement() {
        HalfOpenWindow window = HalfOpenWindow.of(T0, T0.plus(Duration.ofHours(1)));
        List<TimePoint> points = List.of(
                p(T0.plusSeconds(120), 0, 1d),
                p(T0.plusSeconds(60), 1, 2d),
                p(T0.plusSeconds(180), 2, 3d));
        QualityProfile profile = QualityProfile.builder().profileDigest("ooo-v1").build();
        QualityAssessment a = TimeSeriesQualityEvaluator.evaluate(
                points, window, profile, T0.plus(Duration.ofHours(1)));
        QualityFinding ooo = a.finding(QualityMetricId.OUT_OF_ORDER);
        assertEquals("1", ooo.observed());
        assertEquals(QualitySeverity.WARNING, ooo.severity());
        assertTrue(ooo.detail().contains("maxBackwardMs=60000"));
    }

    @Test
    void missingTimestamp_blocking() {
        HalfOpenWindow window = HalfOpenWindow.of(T0, T0.plus(Duration.ofHours(1)));
        List<TimePoint> points = List.of(p(T0, 0, 1d));
        QualityProfile profile = QualityProfile.builder().profileDigest("ts-v1").build();
        QualityAssessment a = TimeSeriesQualityEvaluator.evaluate(
                points, window, profile, T0.plus(Duration.ofHours(1)), 2L, 1L);
        QualityFinding tv = a.finding(QualityMetricId.TIMESTAMP_VALIDITY);
        assertEquals("3", tv.observed());
        assertEquals(QualitySeverity.BLOCKING, tv.severity());
        assertTrue(a.hasBlocking());
    }

    @Test
    void unknownCadence_doesNotInventCoverage() {
        HalfOpenWindow window = HalfOpenWindow.of(T0, T0.plus(Duration.ofHours(1)));
        List<TimePoint> points = varying(T0, Duration.ofMinutes(5), 6);
        QualityProfile profile = QualityProfile.builder()
                .profileDigest("no-cadence-v1")
                .flatlineMinSupport(100)
                .build();
        QualityAssessment a = TimeSeriesQualityEvaluator.evaluate(
                points, window, profile, T0.plus(Duration.ofHours(1)));
        assertTrue(a.finding(QualityMetricId.CADENCE).unknown());
        assertEquals("UNKNOWN", a.finding(QualityMetricId.CADENCE).observed());
        assertTrue(a.finding(QualityMetricId.GAPS_COVERAGE).unknown());
        assertEquals("UNKNOWN", a.finding(QualityMetricId.GAPS_COVERAGE).observed());
        assertTrue(a.finding(QualityMetricId.CADENCE_DRIFT).unknown());
        assertFalse(a.hasBlocking());
    }

    @Test
    void blockingCannotBeDroppedFromEvidence() {
        HalfOpenWindow window = HalfOpenWindow.of(T0, T0.plus(Duration.ofMinutes(30)));
        List<TimePoint> points = regular(T0, Duration.ofMinutes(1), 20, 7d);
        QualityProfile profile = QualityProfile.builder()
                .profileDigest("attach-v1")
                .flatlineMinSupport(3)
                .flatlineMinDuration(Duration.ofMinutes(2))
                .flatlineSeverity(QualitySeverity.BLOCKING)
                .build();
        QualityAssessment assessment = TimeSeriesQualityEvaluator.evaluate(
                points, window, profile, T0.plus(Duration.ofMinutes(30)));
        assertTrue(assessment.hasBlocking());

        AnalysisEnvelope env = QualityEvidenceAttachment.attach(baseBuilder(), assessment).build();
        assertTrue(env.evidence().hasQuality("BLOCKING:flatline"));
        QualityEvidenceAttachment.requireBlockingPreserved(assessment, env.evidence().quality());

        assertThrows(IllegalStateException.class, () -> QualityEvidenceAttachment
                .requireBlockingPreserved(assessment, List.of("flatline", "profile:attach-v1")));
    }

    @Test
    void rangeViolation_keepsSpecAndControlDistinct() {
        HalfOpenWindow window = HalfOpenWindow.of(T0, T0.plus(Duration.ofHours(1)));
        List<TimePoint> points = List.of(
                p(T0, 0, -5d),
                p(T0.plusSeconds(60), 1, 50d),
                p(T0.plusSeconds(120), 2, 15d));
        QualityProfile profile = QualityProfile.builder()
                .profileDigest("range-v1")
                .specMin(0d)
                .specMax(100d)
                .controlMin(10d)
                .controlMax(40d)
                .build();
        QualityAssessment a = TimeSeriesQualityEvaluator.evaluate(
                points, window, profile, T0.plus(Duration.ofHours(1)));
        QualityFinding range = a.finding(QualityMetricId.RANGE_VIOLATION);
        assertEquals(QualitySeverity.WARNING, range.severity());
        assertTrue(range.detail().contains("belowSpec=1"));
        assertTrue(range.detail().contains("aboveControl=1"));
        assertTrue(range.threshold().contains("spec=[0.0,100.0]"));
        assertTrue(range.threshold().contains("control=[10.0,40.0]"));
    }

    private static QualityProfile.Builder cadenceProfile(Duration expected) {
        return QualityProfile.builder()
                .profileDigest("cadence-v1")
                .expectedCadence(expected);
    }

    private static AnalysisEnvelopeBuilder baseBuilder() {
        return AnalysisEnvelopeBuilder.create()
                .status(EvidenceStatus.SUCCESS)
                .operation(AnalysisOperation.QUALITY)
                .addSourceCacheId("src-q")
                .method(descriptor())
                .completeness(CompletenessStatus.COMPLETE)
                .n(1)
                .inputsFullyScanned(true);
    }

    private static AnalysisMethodDescriptor descriptor() {
        return AnalysisMethodDescriptor.builder()
                .id("u4.quality")
                .version("1")
                .profileDigest("attach-v1")
                .operation(AnalysisOperation.QUALITY)
                .build();
    }

    private static List<TimePoint> regular(Instant start, Duration step, int n, double value) {
        List<TimePoint> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(p(start.plus(step.multipliedBy(i)), i, value));
        }
        return out;
    }

    private static List<TimePoint> varying(Instant start, Duration step, int n) {
        List<TimePoint> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(p(start.plus(step.multipliedBy(i)), i, 10d + i));
        }
        return out;
    }

    private static TimePoint p(Instant t, long ordinal, Double value) {
        return new TimePoint(t, ordinal, value);
    }
}
