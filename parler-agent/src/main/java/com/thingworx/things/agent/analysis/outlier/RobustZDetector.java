package com.thingworx.things.agent.analysis.outlier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.thingworx.things.agent.analysis.FindingRow;
import com.thingworx.things.agent.analysis.G1DetectionResult;
import com.thingworx.things.agent.analysis.stats.LinearPercentile;
import com.thingworx.things.agent.analysis.stats.NumericObservation;
import com.thingworx.things.agent.analysis.stats.NumericSeries;
import com.thingworx.things.agent.evidence.EvidenceStatus;

/**
 * Modified z-score by MAD (§7.1). Constant {@code 0.67448975 = Φ⁻¹(0.75)}.
 */
public final class RobustZDetector {

    public static final String METHOD_ID = "robust_z";
    public static final double DEFAULT_THRESHOLD = 3.5;
    public static final double MAD_SCALE = 0.67448975;
    public static final long MIN_SUPPORT = 3L;

    private RobustZDetector() {}

    public static G1DetectionResult detect(NumericSeries series, double threshold,
            ZeroDispersionPolicy zeroPolicy) {
        Objects.requireNonNull(series, "series");
        Objects.requireNonNull(zeroPolicy, "zeroPolicy");
        if (!(threshold > 0.0) || !Double.isFinite(threshold)) {
            throw new IllegalArgumentException("threshold must be finite and > 0");
        }
        List<NumericObservation> finite = finiteObs(series);
        long n = finite.size();
        if (n < MIN_SUPPORT) {
            return insufficient(n, "insufficient_support", zeroPolicy, threshold, null);
        }
        double[] values = new double[(int) n];
        for (int i = 0; i < finite.size(); i++) {
            values[i] = finite.get(i).value();
        }
        double median = LinearPercentile.of(values, 0.5);
        double[] absDev = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            absDev[i] = Math.abs(values[i] - median);
        }
        double mad = LinearPercentile.of(absDev, 0.5);
        if (mad == 0.0) {
            if (zeroPolicy == ZeroDispersionPolicy.FALLBACK_IQR) {
                G1DetectionResult iqr = IqrDetector.detect(series, IqrDetector.DEFAULT_K,
                        ZeroDispersionPolicy.INSUFFICIENT);
                Map<String, String> m = new LinkedHashMap<>(iqr.metrics());
                m.put("zeroDispersionPolicy", zeroPolicy.name());
                m.put("fallbackFrom", METHOD_ID);
                m.put(com.thingworx.things.agent.analysis.AnalysisOutcomeCodes.METRIC_KEY, iqr.outcomeCode());
                return G1DetectionResult.builder()
                        .status(iqr.status())
                        .outcomeCode(iqr.outcomeCode())
                        .methodId(IqrDetector.METHOD_ID)
                        .supportN(iqr.supportN())
                        .findings(iqr.findings())
                        .metrics(m)
                        .build();
            }
            return insufficient(n, "ZERO_DISPERSION", zeroPolicy, threshold, median);
        }
        List<FindingRow> findings = new ArrayList<>();
        for (NumericObservation o : finite) {
            double score = MAD_SCALE * (o.value() - median) / mad;
            if (Math.abs(score) >= threshold) {
                findings.add(FindingRow.builder()
                        .sourceOrdinal(o.sourceOrdinal())
                        .timestamp(o.instant())
                        .score(score)
                        .effect(o.value() - median)
                        .methodId(METHOD_ID)
                        .outcomeCode("outlier")
                        .segmentOrPair("robust_z")
                        .build());
            }
        }
        Map<String, String> metrics = baseMetrics(zeroPolicy, threshold, median, mad, n);
        if (findings.isEmpty()) {
            return G1DetectionResult.builder()
                    .status(EvidenceStatus.NO_FINDING)
                    .outcomeCode("no_outlier")
                    .methodId(METHOD_ID)
                    .supportN(n)
                    .findings(List.of())
                    .metrics(withOutcome(metrics, "no_outlier"))
                    .build();
        }
        return G1DetectionResult.builder()
                .status(EvidenceStatus.SUCCESS)
                .outcomeCode("outlier")
                .methodId(METHOD_ID)
                .supportN(n)
                .findings(findings)
                .metrics(withOutcome(metrics, "outlier"))
                .build();
    }

    private static G1DetectionResult insufficient(long n, String outcome, ZeroDispersionPolicy policy,
            double threshold, Double median) {
        Map<String, String> metrics = baseMetrics(policy, threshold, median, null, n);
        return G1DetectionResult.builder()
                .status(EvidenceStatus.INSUFFICIENT_EVIDENCE)
                .outcomeCode(outcome)
                .methodId(METHOD_ID)
                .supportN(n)
                .findings(List.of())
                .metrics(withOutcome(metrics, outcome))
                .build();
    }

    private static Map<String, String> baseMetrics(ZeroDispersionPolicy policy, double threshold,
            Double median, Double mad, long n) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("threshold", Double.toString(threshold));
        m.put("zeroDispersionPolicy", policy.name());
        m.put("supportN", Long.toString(n));
        if (median != null) {
            m.put("median", Double.toString(median));
        }
        if (mad != null) {
            m.put("mad", Double.toString(mad));
        }
        return m;
    }

    private static Map<String, String> withOutcome(Map<String, String> metrics, String outcome) {
        Map<String, String> m = new LinkedHashMap<>(metrics);
        m.put(com.thingworx.things.agent.analysis.AnalysisOutcomeCodes.METRIC_KEY, outcome);
        return m;
    }

    private static List<NumericObservation> finiteObs(NumericSeries series) {
        List<NumericObservation> out = new ArrayList<>();
        for (NumericObservation o : series.observations()) {
            if (o.isFinite()) {
                out.add(o);
            }
        }
        return out;
    }
}
