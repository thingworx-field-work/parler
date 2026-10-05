package com.thingworx.things.agent.analysis.trend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.thingworx.things.agent.analysis.AnalysisOutcomeCodes;
import com.thingworx.things.agent.analysis.U5MethodCapability;
import com.thingworx.things.agent.analysis.U5MethodRegistry;
import com.thingworx.things.agent.analysis.stats.AnalysisUnitPropagation;
import com.thingworx.things.agent.analysis.stats.LinearPercentile;
import com.thingworx.things.agent.analysis.stats.NumericSeries;
import com.thingworx.things.agent.evidence.EvidenceStatus;

/**
 * Exact budget-bounded Theil–Sen slope (§7.6 / D6). Computes the median of all pairwise finite
 * slopes when {@code n*(n-1)/2} fits the catalog pair budget; never silently samples pairs.
 */
public final class TheilSenTrend {

    public static final String METHOD_ID = "theil_sen";

    private TheilSenTrend() {}

    public static G4TrendResult fit(NumericSeries series, String valueUnit) {
        Objects.requireNonNull(series, "series");
        U5MethodCapability cap = U5MethodRegistry.requireEnabled(METHOD_ID);
        TrendSeriesProjection proj = TrendSeriesProjection.from(series);
        long n = proj.usedCount();
        long pairCount = n < 2L ? 0L : n * (n - 1L) / 2L;
        try {
            U5MethodRegistry.assertWithinComplexity(cap, n, pairCount);
        } catch (IllegalStateException ex) {
            // §6.3.2: exhausted hard budget → ERROR (not insufficient_support / INSUFFICIENT_EVIDENCE).
            Map<String, String> metrics = baseMetrics(proj, valueUnit, pairCount);
            metrics.put(AnalysisOutcomeCodes.METRIC_KEY, "budget_exceeded");
            return G4TrendResult.builder()
                    .status(EvidenceStatus.ERROR)
                    .outcomeCode("budget_exceeded")
                    .methodId(METHOD_ID)
                    .supportN(n)
                    .metrics(metrics)
                    .build();
        }
        Map<String, String> metrics = baseMetrics(proj, valueUnit, pairCount);
        if (n < 2L) {
            metrics.put(AnalysisOutcomeCodes.METRIC_KEY, "insufficient_support");
            return G4TrendResult.builder()
                    .status(EvidenceStatus.INSUFFICIENT_EVIDENCE)
                    .outcomeCode("insufficient_support")
                    .methodId(METHOD_ID)
                    .supportN(n)
                    .metrics(metrics)
                    .build();
        }
        double[] x = proj.elapsedSeconds();
        double[] y = proj.values();
        List<Double> slopes = new ArrayList<>((int) Math.min(pairCount, Integer.MAX_VALUE));
        for (int i = 0; i < x.length; i++) {
            for (int j = i + 1; j < x.length; j++) {
                double dx = x[j] - x[i];
                if (dx == 0.0) {
                    continue;
                }
                slopes.add((y[j] - y[i]) / dx);
            }
        }
        if (slopes.size() < 1) {
            metrics.put(AnalysisOutcomeCodes.METRIC_KEY, "insufficient_variance");
            return G4TrendResult.builder()
                    .status(EvidenceStatus.INSUFFICIENT_EVIDENCE)
                    .outcomeCode("insufficient_variance")
                    .methodId(METHOD_ID)
                    .supportN(n)
                    .metrics(metrics)
                    .build();
        }
        double[] slopeArr = new double[slopes.size()];
        for (int i = 0; i < slopes.size(); i++) {
            slopeArr[i] = slopes.get(i);
        }
        double slope = LinearPercentile.of(slopeArr, 0.5);
        // Intercept = median of (y_i - slope * x_i)
        double[] intercepts = new double[y.length];
        for (int i = 0; i < y.length; i++) {
            intercepts[i] = y[i] - slope * x[i];
        }
        double intercept = LinearPercentile.of(intercepts, 0.5);
        metrics.put("slope", Double.toString(slope));
        metrics.put("intercept", Double.toString(intercept));
        metrics.put("pairwiseSlopes", Integer.toString(slopes.size()));
        metrics.put("observedStart", proj.t0().toString());
        metrics.put("observedEnd", proj.tEnd().toString());
        String slopeUnit = AnalysisUnitPropagation.slopeUnit(valueUnit, OlsTrend.TIME_UNIT);
        if (slopeUnit != null) {
            metrics.put("slopeUnit", slopeUnit);
        }
        metrics.put(AnalysisOutcomeCodes.METRIC_KEY, "trend");
        return G4TrendResult.builder()
                .status(EvidenceStatus.SUCCESS)
                .outcomeCode("trend")
                .methodId(METHOD_ID)
                .supportN(n)
                .slope(slope)
                .intercept(intercept)
                .metrics(metrics)
                .build();
    }

    private static Map<String, String> baseMetrics(TrendSeriesProjection proj, String valueUnit,
            long pairCount) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("supportN", Long.toString(proj.usedCount()));
        m.put("considered", Long.toString(proj.considered()));
        m.put("dropped", Long.toString(proj.dropped()));
        m.put("pairCount", Long.toString(pairCount));
        m.put("timeUnit", OlsTrend.TIME_UNIT);
        String vu = AnalysisUnitPropagation.echo(valueUnit);
        if (vu != null) {
            m.put("valueUnit", vu);
        }
        return m;
    }
}
