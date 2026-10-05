package com.thingworx.things.agent.analysis.spc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.thingworx.things.agent.analysis.AnalysisOutcomeCodes;
import com.thingworx.things.agent.analysis.G1DetectionResult;
import com.thingworx.things.agent.analysis.stats.NumericObservation;
import com.thingworx.things.agent.analysis.stats.NumericSeries;
import com.thingworx.things.agent.analysis.stats.StableMeanVariance;
import com.thingworx.things.agent.evidence.EvidenceStatus;

/**
 * Individuals / moving-range limits (§7.3). {@code d2=1.128}, MR UCL factor {@code 3.267}.
 * Specification limits are never mixed into these control limits.
 */
public final class ImrLimits {

    public static final String METHOD_ID = "imr";
    public static final double D2 = 1.128;
    public static final double MR_UCL_FACTOR = 3.267;
    public static final long MIN_SUPPORT = 2L;

    private final double center;
    private final double mrBar;
    private final double sigma;
    private final double ucl;
    private final double lcl;
    private final double mrUcl;
    private final long supportN;
    private final long movingRangeCount;

    private ImrLimits(double center, double mrBar, double sigma, double ucl, double lcl, double mrUcl,
            long supportN, long movingRangeCount) {
        this.center = center;
        this.mrBar = mrBar;
        this.sigma = sigma;
        this.ucl = ucl;
        this.lcl = lcl;
        this.mrUcl = mrUcl;
        this.supportN = supportN;
        this.movingRangeCount = movingRangeCount;
    }

    public static Result compute(NumericSeries series) {
        Objects.requireNonNull(series, "series");
        List<NumericObservation> finite = new ArrayList<>();
        for (NumericObservation o : series.observations()) {
            if (o.isFinite()) {
                finite.add(o);
            }
        }
        long n = finite.size();
        if (n < MIN_SUPPORT) {
            return Result.insufficient(n, 0L, "insufficient_support");
        }
        double[] values = new double[(int) n];
        for (int i = 0; i < finite.size(); i++) {
            values[i] = finite.get(i).value();
        }
        StableMeanVariance mv = StableMeanVariance.ofFinite(values);
        List<Double> mrs = new ArrayList<>();
        for (int i = 1; i < finite.size(); i++) {
            // Consecutive finite observations in series order — gap policy is executor-owned later.
            mrs.add(Math.abs(values[i] - values[i - 1]));
        }
        if (mrs.isEmpty()) {
            return Result.insufficient(n, 0L, "insufficient_support");
        }
        double mrSum = 0.0;
        for (double mr : mrs) {
            mrSum += mr;
        }
        double mrBar = mrSum / mrs.size();
        double sigma = mrBar / D2;
        if (sigma == 0.0) {
            return Result.insufficient(n, mrs.size(), "ZERO_DISPERSION");
        }
        double ucl = mv.mean() + 3.0 * sigma;
        double lcl = mv.mean() - 3.0 * sigma;
        double mrUcl = MR_UCL_FACTOR * mrBar;
        ImrLimits limits = new ImrLimits(mv.mean(), mrBar, sigma, ucl, lcl, mrUcl, n, mrs.size());
        return Result.ok(limits, finite, values);
    }

    public double center() {
        return center;
    }

    public double mrBar() {
        return mrBar;
    }

    public double sigma() {
        return sigma;
    }

    public double ucl() {
        return ucl;
    }

    public double lcl() {
        return lcl;
    }

    public double mrUcl() {
        return mrUcl;
    }

    public long supportN() {
        return supportN;
    }

    public long movingRangeCount() {
        return movingRangeCount;
    }

    public Map<String, String> toMetrics() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("center", Double.toString(center));
        m.put("mrBar", Double.toString(mrBar));
        m.put("sigma", Double.toString(sigma));
        m.put("ucl", Double.toString(ucl));
        m.put("lcl", Double.toString(lcl));
        m.put("mrUcl", Double.toString(mrUcl));
        m.put("supportN", Long.toString(supportN));
        m.put("movingRangeCount", Long.toString(movingRangeCount));
        m.put("limitKind", "control"); // never "specification"
        return m;
    }

    public static final class Result {
        public final G1DetectionResult asInsufficient;
        public final ImrLimits limits;
        public final List<NumericObservation> finite;
        public final double[] values;

        private Result(G1DetectionResult asInsufficient, ImrLimits limits, List<NumericObservation> finite,
                double[] values) {
            this.asInsufficient = asInsufficient;
            this.limits = limits;
            this.finite = finite;
            this.values = values;
        }

        static Result ok(ImrLimits limits, List<NumericObservation> finite, double[] values) {
            return new Result(null, limits, finite, values);
        }

        static Result insufficient(long n, long mrCount, String outcome) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("supportN", Long.toString(n));
            m.put("movingRangeCount", Long.toString(mrCount));
            m.put("limitKind", "control");
            m.put(AnalysisOutcomeCodes.METRIC_KEY, outcome);
            G1DetectionResult r = G1DetectionResult.builder()
                    .status(EvidenceStatus.INSUFFICIENT_EVIDENCE)
                    .outcomeCode(outcome)
                    .methodId(METHOD_ID)
                    .supportN(n)
                    .findings(List.of())
                    .metrics(m)
                    .build();
            return new Result(r, null, List.of(), new double[0]);
        }

        public boolean insufficient() {
            return asInsufficient != null;
        }
    }
}
