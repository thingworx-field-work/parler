package com.thingworx.things.agent.analysis.spc;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.thingworx.things.agent.analysis.AnalysisOutcomeCodes;
import com.thingworx.things.agent.analysis.FindingRow;
import com.thingworx.things.agent.analysis.G1DetectionResult;
import com.thingworx.things.agent.analysis.stats.NumericObservation;
import com.thingworx.things.agent.analysis.stats.NumericSeries;
import com.thingworx.things.agent.evidence.EvidenceStatus;

/**
 * Evaluates the versioned I-MR run-rule catalog against computed control limits (§7.3). Each finding
 * records rule id/version and involved source ordinals; multi-point rules emit one finding per
 * rule instance (window/streak), not one per terminal index.
 */
public final class SpcRunRuleEvaluator {

    private SpcRunRuleEvaluator() {}

    public static G1DetectionResult evaluate(NumericSeries series, Set<SpcRunRule> enabledRules) {
        Objects.requireNonNull(series, "series");
        Set<SpcRunRule> rules = enabledRules == null || enabledRules.isEmpty()
                ? EnumSet.of(SpcRunRule.R1)
                : EnumSet.copyOf(enabledRules);
        ImrLimits.Result imr = ImrLimits.compute(series);
        if (imr.insufficient()) {
            return imr.asInsufficient;
        }
        ImrLimits limits = imr.limits;
        List<NumericObservation> finite = imr.finite;
        double[] values = imr.values;
        List<FindingRow> findings = new ArrayList<>();
        if (rules.contains(SpcRunRule.R1)) {
            for (int i = 0; i < values.length; i++) {
                double z = (values[i] - limits.center()) / limits.sigma();
                if (Math.abs(z) > 3.0) {
                    findings.add(finding(List.of(finite.get(i)), values, List.of(i), SpcRunRule.R1, limits));
                }
            }
        }
        if (rules.contains(SpcRunRule.R2)) {
            findings.addAll(ruleKofN(finite, values, limits, SpcRunRule.R2, 3, 2, 2.0));
        }
        if (rules.contains(SpcRunRule.R3)) {
            findings.addAll(ruleKofN(finite, values, limits, SpcRunRule.R3, 5, 4, 1.0));
        }
        if (rules.contains(SpcRunRule.R4)) {
            findings.addAll(ruleSideStreak(finite, values, limits, SpcRunRule.R4, 8));
        }
        Map<String, String> metrics = new LinkedHashMap<>(limits.toMetrics());
        metrics.put("enabledRules", rules.toString());
        if (findings.isEmpty()) {
            metrics.put(AnalysisOutcomeCodes.METRIC_KEY, "no_spc_violation");
            return G1DetectionResult.builder()
                    .status(EvidenceStatus.NO_FINDING)
                    .outcomeCode("no_spc_violation")
                    .methodId(ImrLimits.METHOD_ID)
                    .supportN(limits.supportN())
                    .findings(List.of())
                    .metrics(metrics)
                    .build();
        }
        metrics.put(AnalysisOutcomeCodes.METRIC_KEY, "spc_violation");
        return G1DetectionResult.builder()
                .status(EvidenceStatus.SUCCESS)
                .outcomeCode("spc_violation")
                .methodId(ImrLimits.METHOD_ID)
                .supportN(limits.supportN())
                .findings(findings)
                .metrics(metrics)
                .build();
    }

    private static List<FindingRow> ruleKofN(List<NumericObservation> finite, double[] values,
            ImrLimits limits, SpcRunRule rule, int window, int need, double sigmaMult) {
        List<FindingRow> out = new ArrayList<>();
        for (int end = window - 1; end < values.length; end++) {
            int start = end - window + 1;
            List<Integer> aboveIdx = new ArrayList<>();
            List<Integer> belowIdx = new ArrayList<>();
            for (int i = start; i <= end; i++) {
                double z = (values[i] - limits.center()) / limits.sigma();
                if (z > sigmaMult) {
                    aboveIdx.add(i);
                } else if (z < -sigmaMult) {
                    belowIdx.add(i);
                }
            }
            List<Integer> involvedIdx = null;
            if (aboveIdx.size() >= need) {
                involvedIdx = aboveIdx;
            } else if (belowIdx.size() >= need) {
                involvedIdx = belowIdx;
            }
            if (involvedIdx != null) {
                List<NumericObservation> involved = new ArrayList<>(involvedIdx.size());
                for (int idx : involvedIdx) {
                    involved.add(finite.get(idx));
                }
                out.add(finding(involved, values, involvedIdx, rule, limits));
            }
        }
        return out;
    }

    private static List<FindingRow> ruleSideStreak(List<NumericObservation> finite, double[] values,
            ImrLimits limits, SpcRunRule rule, int need) {
        List<FindingRow> out = new ArrayList<>();
        int i = 0;
        while (i < values.length) {
            int s = sideSign(values[i], limits.center());
            if (s == 0) {
                i++;
                continue;
            }
            int start = i;
            while (i < values.length && sideSign(values[i], limits.center()) == s) {
                i++;
            }
            int streakLen = i - start;
            if (streakLen >= need) {
                List<Integer> idx = new ArrayList<>(streakLen);
                List<NumericObservation> involved = new ArrayList<>(streakLen);
                for (int j = start; j < i; j++) {
                    idx.add(j);
                    involved.add(finite.get(j));
                }
                out.add(finding(involved, values, idx, rule, limits));
            }
        }
        return out;
    }

    private static int sideSign(double value, double center) {
        return value > center ? 1 : value < center ? -1 : 0;
    }

    private static FindingRow finding(List<NumericObservation> involved, double[] values,
            List<Integer> involvedIdx, SpcRunRule rule, ImrLimits limits) {
        if (involved.isEmpty()) {
            throw new IllegalArgumentException("involved rows required");
        }
        int extremePos = involvedIdx.get(0);
        double extremeZ = (values[extremePos] - limits.center()) / limits.sigma();
        for (int idx : involvedIdx) {
            double z = (values[idx] - limits.center()) / limits.sigma();
            if (Math.abs(z) > Math.abs(extremeZ)
                    || (Math.abs(z) == Math.abs(extremeZ) && idx < extremePos)) {
                extremeZ = z;
                extremePos = idx;
            }
        }
        NumericObservation primary = involved.stream()
                .min((a, b) -> Long.compare(a.sourceOrdinal(), b.sourceOrdinal()))
                .orElseThrow();
        String involvedCsv = involved.stream()
                .map(o -> Long.toString(o.sourceOrdinal()))
                .sorted()
                .collect(Collectors.joining(","));
        return FindingRow.builder()
                .sourceOrdinal(primary.sourceOrdinal())
                .timestamp(primary.instant())
                .score(extremeZ)
                .effect(values[extremePos] - limits.center())
                .limit(extremeZ > 0 ? limits.ucl() : limits.lcl())
                .methodId(rule.methodId())
                .outcomeCode("spc_violation")
                .segmentOrPair(String.format(Locale.ROOT, "%s@v%s;involved=%s",
                        rule.name(), rule.version(), involvedCsv))
                .build();
    }
}
