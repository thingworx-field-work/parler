package com.thingworx.things.agent.analysis.outlier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.thingworx.things.agent.analysis.AnalysisOutcomeCodes;
import com.thingworx.things.agent.analysis.FindingRow;
import com.thingworx.things.agent.analysis.G1DetectionResult;
import com.thingworx.things.agent.analysis.stats.LinearPercentile;
import com.thingworx.things.agent.analysis.stats.NumericObservation;
import com.thingworx.things.agent.analysis.stats.NumericSeries;
import com.thingworx.things.agent.evidence.EvidenceStatus;

/** Tukey IQR fences (§7.1). */
public final class IqrDetector {

    public static final String METHOD_ID = "iqr";
    public static final double DEFAULT_K = 1.5;
    public static final long MIN_SUPPORT = 4L;

    private IqrDetector() {}

    public static G1DetectionResult detect(NumericSeries series, double k, ZeroDispersionPolicy zeroPolicy) {
        Objects.requireNonNull(series, "series");
        Objects.requireNonNull(zeroPolicy, "zeroPolicy");
        if (!(k > 0.0) || !Double.isFinite(k)) {
            throw new IllegalArgumentException("k must be finite and > 0");
        }
        List<NumericObservation> finite = new ArrayList<>();
        for (NumericObservation o : series.observations()) {
            if (o.isFinite()) {
                finite.add(o);
            }
        }
        long n = finite.size();
        if (n < MIN_SUPPORT) {
            return insufficient(n, "insufficient_support", k, zeroPolicy, null, null, null);
        }
        double[] values = new double[(int) n];
        for (int i = 0; i < finite.size(); i++) {
            values[i] = finite.get(i).value();
        }
        double q1 = LinearPercentile.of(values, 0.25);
        double q3 = LinearPercentile.of(values, 0.75);
        double iqr = q3 - q1;
        if (iqr == 0.0) {
            return insufficient(n, "ZERO_DISPERSION", k, zeroPolicy, q1, q3, iqr);
        }
        double lower = q1 - k * iqr;
        double upper = q3 + k * iqr;
        List<FindingRow> findings = new ArrayList<>();
        for (NumericObservation o : finite) {
            if (o.value() < lower || o.value() > upper) {
                double score = o.value() < lower ? (o.value() - lower) / iqr : (o.value() - upper) / iqr;
                findings.add(FindingRow.builder()
                        .sourceOrdinal(o.sourceOrdinal())
                        .timestamp(o.instant())
                        .score(score)
                        .effect(o.value())
                        .limit(o.value() < lower ? lower : upper)
                        .methodId(METHOD_ID)
                        .outcomeCode("outlier")
                        .segmentOrPair("iqr")
                        .build());
            }
        }
        Map<String, String> metrics = baseMetrics(k, zeroPolicy, n, q1, q3, iqr, lower, upper);
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

    private static G1DetectionResult insufficient(long n, String outcome, double k,
            ZeroDispersionPolicy policy, Double q1, Double q3, Double iqr) {
        Map<String, String> metrics = baseMetrics(k, policy, n, q1, q3, iqr, null, null);
        return G1DetectionResult.builder()
                .status(EvidenceStatus.INSUFFICIENT_EVIDENCE)
                .outcomeCode(outcome)
                .methodId(METHOD_ID)
                .supportN(n)
                .findings(List.of())
                .metrics(withOutcome(metrics, outcome))
                .build();
    }

    private static Map<String, String> baseMetrics(double k, ZeroDispersionPolicy policy, long n,
            Double q1, Double q3, Double iqr, Double lower, Double upper) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("k", Double.toString(k));
        m.put("zeroDispersionPolicy", policy.name());
        m.put("supportN", Long.toString(n));
        if (q1 != null) {
            m.put("q1", Double.toString(q1));
        }
        if (q3 != null) {
            m.put("q3", Double.toString(q3));
        }
        if (iqr != null) {
            m.put("iqr", Double.toString(iqr));
        }
        if (lower != null) {
            m.put("lowerFence", Double.toString(lower));
        }
        if (upper != null) {
            m.put("upperFence", Double.toString(upper));
        }
        return m;
    }

    private static Map<String, String> withOutcome(Map<String, String> metrics, String outcome) {
        Map<String, String> m = new LinkedHashMap<>(metrics);
        m.put(AnalysisOutcomeCodes.METRIC_KEY, outcome);
        return m;
    }
}
