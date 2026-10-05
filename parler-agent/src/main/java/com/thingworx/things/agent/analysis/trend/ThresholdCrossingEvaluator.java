package com.thingworx.things.agent.analysis.trend;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.thingworx.things.agent.analysis.AnalysisOutcomeCodes;
import com.thingworx.things.agent.analysis.FindingRow;
import com.thingworx.things.agent.analysis.ThresholdCrossingOutcome;
import com.thingworx.things.agent.analysis.ThresholdCrossingOutcomeResolver;
import com.thingworx.things.agent.analysis.U5MethodRegistry;
import com.thingworx.things.agent.analysis.stats.NumericSeries;
import com.thingworx.things.agent.evidence.EvidenceStatus;

/**
 * Horizon-gated threshold crossing over a fitted trend (§7.6 / §6.3.3–§6.3.5). Status is always
 * derived from {@link ThresholdCrossingOutcomeResolver}; never invented per call site.
 */
public final class ThresholdCrossingEvaluator {

    public static final String METHOD_ID = "threshold_crossing";

    private ThresholdCrossingEvaluator() {}

    public static G4CrossingResult evaluate(NumericSeries series, Config config) {
        Objects.requireNonNull(series, "series");
        Config cfg = config == null ? Config.defaults() : config.validate();
        U5MethodRegistry.assertWithinComplexity(U5MethodRegistry.requireEnabled(METHOD_ID),
                series.observations().size(), 0L);

        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("threshold", Double.toString(cfg.threshold));
        metrics.put("horizonSeconds", Double.toString(cfg.horizon.toMillis() / 1000.0));
        metrics.put("minSupport", Integer.toString(cfg.minSupport));
        metrics.put("minRSquared", Double.toString(cfg.minRSquared));
        metrics.put("flatSlopeEps", Double.toString(cfg.flatSlopeEps));
        metrics.put("trendMethod", cfg.useTheilSen ? TheilSenTrend.METHOD_ID : OlsTrend.METHOD_ID);
        metrics.put("mseDenominator", OlsTrend.MSE_DENOMINATOR);
        // OLS uses rSquared; Theil–Sen has no R² — support-gated only (explicit).
        metrics.put("fitGateStatistic", cfg.useTheilSen ? "support_only" : "rSquared");

        ThresholdCrossingOutcomeResolver.Flags flags = new ThresholdCrossingOutcomeResolver.Flags();
        if (cfg.applicabilityFailed) {
            flags.applicabilityFailed(true);
            return finish(flags, null, 0L, metrics, null, List.of());
        }
        if (cfg.qualityBlocked) {
            flags.qualityBlocked(true);
            return finish(flags, null, 0L, metrics, null, List.of());
        }

        G4TrendResult trend = cfg.useTheilSen
                ? TheilSenTrend.fit(series, cfg.valueUnit)
                : OlsTrend.fit(series, cfg.valueUnit);
        metrics.putAll(trend.metrics());
        long support = trend.supportN();

        if (trend.status() == EvidenceStatus.ERROR
                || "budget_exceeded".equals(trend.outcomeCode())) {
            return hardFault("budget_exceeded", support, metrics);
        }
        if (support < cfg.minSupport
                || "insufficient_support".equals(trend.outcomeCode())) {
            flags.insufficientSupport(true);
            return finish(flags, null, support, metrics, null, List.of());
        }
        if (trend.status() != EvidenceStatus.SUCCESS
                || "insufficient_variance".equals(trend.outcomeCode())) {
            // Zero-variance / unfittable trend → insufficient_fit (§6.3.3), not insufficient_support.
            flags.insufficientFit(true);
            return finish(flags, null, support, metrics, null, List.of());
        }
        if (!cfg.useTheilSen) {
            String r2s = trend.metrics().get("rSquared");
            double r2 = r2s == null ? Double.NaN : Double.parseDouble(r2s);
            if (!Double.isFinite(r2) || r2 < cfg.minRSquared) {
                flags.insufficientFit(true);
                return finish(flags, null, support, metrics, null, List.of());
            }
        }

        TrendSeriesProjection proj = TrendSeriesProjection.from(series);
        double[] values = proj.values();
        double firstY = values[0];
        double lastY = values[values.length - 1];
        Instant tEnd = proj.tEnd();
        double slope = trend.slope();
        double intercept = trend.intercept();
        metrics.put("firstValue", Double.toString(firstY));
        metrics.put("lastValue", Double.toString(lastY));

        // Sense from the first observation; inspect the full eligible window (not only endpoints).
        boolean already = alreadyCrossed(values, cfg.threshold, cfg.flatSlopeEps);
        if (already) {
            flags.alreadyCrossed(true);
            return finish(flags, null, support, metrics, null, List.of());
        }

        if (Math.abs(slope) < cfg.flatSlopeEps) {
            flags.flat(true);
            return finish(flags, null, support, metrics, null, List.of());
        }

        double needed = cfg.threshold - lastY;
        if (needed * slope <= 0.0) {
            flags.wrongDirection(true);
            return finish(flags, null, support, metrics, null, List.of());
        }

        // Solve threshold = intercept + slope * elapsedSeconds
        double elapsedCross = (cfg.threshold - intercept) / slope;
        Instant estimated = proj.instantAtElapsedSeconds(elapsedCross);
        if (estimated == null || !estimated.isAfter(tEnd)) {
            // Model says crossing is not after the observed window — treat as already / flat edge.
            flags.alreadyCrossed(true);
            return finish(flags, estimated, support, metrics, null, List.of());
        }
        Instant horizonEnd = tEnd.plus(cfg.horizon);
        metrics.put("horizonEnd", horizonEnd.toString());
        metrics.put("estimatedCrossingAt", estimated.toString());

        if (!estimated.isAfter(horizonEnd)) {
            flags.crossingWithinHorizon(true);
            FindingRow finding = FindingRow.builder()
                    .sourceOrdinal(proj.used().get(proj.used().size() - 1).sourceOrdinal())
                    .timestamp(estimated)
                    .score(slope)
                    .effect(cfg.threshold - lastY)
                    .limit(cfg.threshold)
                    .methodId(METHOD_ID)
                    .outcomeCode(ThresholdCrossingOutcome.CROSSING_WITHIN_HORIZON.code())
                    .segmentOrPair("horizon=" + cfg.horizon + ";from=" + tEnd)
                    .build();
            return finish(flags, estimated, support, metrics, estimated, List.of(finding));
        }
        flags.outsideHorizon(true);
        return finish(flags, estimated, support, metrics, null, List.of());
    }

    /**
     * Observed window already meets/crosses the threshold relative to the first sample's side:
     * rise when {@code firstY <= threshold}, fall when {@code firstY > threshold}. Any eligible
     * in-window sample that meets the sense counts (not only the last point).
     */
    static boolean alreadyCrossed(double[] values, double threshold, double flatEps) {
        if (values == null || values.length == 0) {
            return false;
        }
        double firstY = values[0];
        boolean rise = firstY <= threshold;
        for (double y : values) {
            if (Math.abs(y - threshold) <= Math.abs(flatEps)) {
                return true;
            }
            if (rise && y >= threshold) {
                return true;
            }
            if (!rise && y <= threshold) {
                return true;
            }
        }
        return false;
    }

    private static G4CrossingResult hardFault(String faultCode, long supportN, Map<String, String> metrics) {
        Map<String, String> m = new LinkedHashMap<>(metrics);
        m.put(AnalysisOutcomeCodes.METRIC_KEY, faultCode);
        return G4CrossingResult.builder()
                .hardFault(faultCode)
                .methodId(METHOD_ID)
                .supportN(supportN)
                .metrics(m)
                .build();
    }

    private static G4CrossingResult finish(ThresholdCrossingOutcomeResolver.Flags flags,
            Instant estimatedForMetrics, long supportN, Map<String, String> metrics,
            Instant estimatedCrossingAt, List<FindingRow> findings) {
        // Ensure at least one judgment flag for the resolver when only gates fired.
        if (!(flags.applicabilityFailed || flags.qualityBlocked || flags.insufficientSupport
                || flags.insufficientFit || flags.alreadyCrossed || flags.crossingWithinHorizon
                || flags.outsideHorizon || flags.flat || flags.wrongDirection)) {
            flags.applicabilityFailed(true);
        }
        ThresholdCrossingOutcome outcome = ThresholdCrossingOutcomeResolver.resolve(flags);
        Map<String, String> m = new LinkedHashMap<>(metrics);
        m.put(AnalysisOutcomeCodes.METRIC_KEY, outcome.code());
        if (estimatedForMetrics != null && !m.containsKey("estimatedCrossingAt")) {
            m.put("estimatedCrossingAt", estimatedForMetrics.toString());
        }
        long n = supportN;
        if (outcome.status() == EvidenceStatus.NO_FINDING && n <= 0L) {
            n = 1L; // shell invariant; these outcomes only arise after a fitted series
        }
        return G4CrossingResult.builder()
                .outcome(outcome)
                .methodId(METHOD_ID)
                .supportN(n)
                .estimatedCrossingAt(estimatedCrossingAt)
                .findings(findings)
                .metrics(m)
                .build();
    }

    public static final class Config {
        public final double threshold;
        public final Duration horizon;
        public final int minSupport;
        public final double minRSquared;
        public final double flatSlopeEps;
        public final boolean useTheilSen;
        public final boolean qualityBlocked;
        public final boolean applicabilityFailed;
        public final String valueUnit;

        public Config(double threshold, Duration horizon, int minSupport, double minRSquared,
                double flatSlopeEps, boolean useTheilSen, boolean qualityBlocked,
                boolean applicabilityFailed, String valueUnit) {
            this.threshold = threshold;
            this.horizon = horizon;
            this.minSupport = minSupport;
            this.minRSquared = minRSquared;
            this.flatSlopeEps = flatSlopeEps;
            this.useTheilSen = useTheilSen;
            this.qualityBlocked = qualityBlocked;
            this.applicabilityFailed = applicabilityFailed;
            this.valueUnit = valueUnit;
        }

        public static Config defaults() {
            return new Config(0.0, Duration.ofHours(1), 3, 0.0, 1e-12, false, false, false, null);
        }

        public Config withThreshold(double v) {
            return new Config(v, horizon, minSupport, minRSquared, flatSlopeEps, useTheilSen,
                    qualityBlocked, applicabilityFailed, valueUnit);
        }

        public Config withHorizon(Duration v) {
            return new Config(threshold, v, minSupport, minRSquared, flatSlopeEps, useTheilSen,
                    qualityBlocked, applicabilityFailed, valueUnit);
        }

        public Config withMinRSquared(double v) {
            return new Config(threshold, horizon, minSupport, v, flatSlopeEps, useTheilSen,
                    qualityBlocked, applicabilityFailed, valueUnit);
        }

        public Config withQualityBlocked(boolean v) {
            return new Config(threshold, horizon, minSupport, minRSquared, flatSlopeEps, useTheilSen,
                    v, applicabilityFailed, valueUnit);
        }

        public Config withApplicabilityFailed(boolean v) {
            return new Config(threshold, horizon, minSupport, minRSquared, flatSlopeEps, useTheilSen,
                    qualityBlocked, v, valueUnit);
        }

        public Config withTheilSen(boolean v) {
            return new Config(threshold, horizon, minSupport, minRSquared, flatSlopeEps, v,
                    qualityBlocked, applicabilityFailed, valueUnit);
        }

        public Config withValueUnit(String v) {
            return new Config(threshold, horizon, minSupport, minRSquared, flatSlopeEps, useTheilSen,
                    qualityBlocked, applicabilityFailed, v);
        }

        Config validate() {
            if (!Double.isFinite(threshold)) {
                throw new IllegalArgumentException("threshold must be finite");
            }
            if (horizon == null || horizon.isNegative() || horizon.isZero()) {
                throw new IllegalArgumentException("horizon must be positive");
            }
            if (minSupport < 2) {
                throw new IllegalArgumentException("minSupport must be >= 2");
            }
            if (!(minRSquared >= 0.0) || !Double.isFinite(minRSquared) || minRSquared > 1.0) {
                throw new IllegalArgumentException("minRSquared must be in [0,1]");
            }
            if (!(flatSlopeEps >= 0.0) || !Double.isFinite(flatSlopeEps)) {
                throw new IllegalArgumentException("flatSlopeEps must be finite and >= 0");
            }
            return this;
        }
    }
}
