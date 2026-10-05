package com.thingworx.things.agent.analysis.trend;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.thingworx.things.agent.analysis.AnalysisOutcomeCodes;
import com.thingworx.things.agent.analysis.U5MethodRegistry;
import com.thingworx.things.agent.analysis.stats.AnalysisUnitPropagation;
import com.thingworx.things.agent.analysis.stats.NumericSeries;
import com.thingworx.things.agent.analysis.stats.SimpleOls;
import com.thingworx.things.agent.evidence.EvidenceStatus;

/**
 * OLS trend on elapsed seconds from the first observation (§7.6). Quantification: flat / near-zero
 * slope is still {@link EvidenceStatus#SUCCESS}.
 */
public final class OlsTrend {

    public static final String METHOD_ID = "ols_trend";
    public static final String TIME_UNIT = "s";
    /** Reported with MSE so consumers share the DIK-4 residual-df convention. */
    public static final String MSE_DENOMINATOR = "n-2";

    private OlsTrend() {}

    public static G4TrendResult fit(NumericSeries series, String valueUnit) {
        Objects.requireNonNull(series, "series");
        U5MethodRegistry.assertWithinComplexity(U5MethodRegistry.requireEnabled(METHOD_ID),
                series.observations().size(), 0L);
        TrendSeriesProjection proj = TrendSeriesProjection.from(series);
        Map<String, String> metrics = baseMetrics(proj, valueUnit);
        if (proj.usedCount() < 2L) {
            return insufficient(proj.usedCount(), metrics, "insufficient_support");
        }
        SimpleOls ols = SimpleOls.fit(proj.elapsedSeconds(), proj.values());
        if (ols.insufficient()) {
            return insufficient(proj.usedCount(), metrics, "insufficient_variance");
        }
        metrics.put("slope", Double.toString(ols.slope()));
        metrics.put("intercept", Double.toString(ols.intercept()));
        metrics.put("rSquared", Double.toString(ols.rSquared()));
        metrics.put("sse", Double.toString(ols.sse()));
        metrics.put("mse", Double.toString(ols.mse()));
        metrics.put("mseDenominator", MSE_DENOMINATOR);
        metrics.put("residualMean", Double.toString(ols.residualMean()));
        metrics.put("residualStddev", Double.toString(ols.residualStddev()));
        metrics.put("residualMin", Double.toString(ols.residualMin()));
        metrics.put("residualMax", Double.toString(ols.residualMax()));
        metrics.put("observedStart", proj.t0().toString());
        metrics.put("observedEnd", proj.tEnd().toString());
        String slopeUnit = AnalysisUnitPropagation.slopeUnit(valueUnit, TIME_UNIT);
        if (slopeUnit != null) {
            metrics.put("slopeUnit", slopeUnit);
        }
        metrics.put(AnalysisOutcomeCodes.METRIC_KEY, "trend");
        return G4TrendResult.builder()
                .status(EvidenceStatus.SUCCESS)
                .outcomeCode("trend")
                .methodId(METHOD_ID)
                .supportN(proj.usedCount())
                .slope(ols.slope())
                .intercept(ols.intercept())
                .metrics(metrics)
                .build();
    }

    private static Map<String, String> baseMetrics(TrendSeriesProjection proj, String valueUnit) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("supportN", Long.toString(proj.usedCount()));
        m.put("considered", Long.toString(proj.considered()));
        m.put("dropped", Long.toString(proj.dropped()));
        m.put("timeUnit", TIME_UNIT);
        String vu = AnalysisUnitPropagation.echo(valueUnit);
        if (vu != null) {
            m.put("valueUnit", vu);
        }
        return m;
    }

    private static G4TrendResult insufficient(long supportN, Map<String, String> metrics, String outcome) {
        Map<String, String> m = new LinkedHashMap<>(metrics);
        m.put(AnalysisOutcomeCodes.METRIC_KEY, outcome);
        return G4TrendResult.builder()
                .status(EvidenceStatus.INSUFFICIENT_EVIDENCE)
                .outcomeCode(outcome)
                .methodId(METHOD_ID)
                .supportN(supportN)
                .metrics(m)
                .build();
    }
}
