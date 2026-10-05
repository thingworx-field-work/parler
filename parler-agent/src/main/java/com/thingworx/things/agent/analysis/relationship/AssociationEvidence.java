package com.thingworx.things.agent.analysis.relationship;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.thingworx.things.agent.analysis.AnalysisOutcomeCodes;
import com.thingworx.things.agent.analysis.U5MethodRegistry;
import com.thingworx.things.agent.analysis.stats.AnalysisUnitPropagation;
import com.thingworx.things.agent.analysis.stats.AverageRanks;
import com.thingworx.things.agent.analysis.stats.SimpleOls;
import com.thingworx.things.agent.analysis.stats.StablePearson;
import com.thingworx.things.agent.evidence.EvidenceStatus;

/**
 * Covariance / Pearson / Spearman / OLS on aligned pairs (§7.5). Quantification: valid metrics are
 * {@link EvidenceStatus#SUCCESS} even when association is near zero.
 */
public final class AssociationEvidence {

    public static final String METHOD_PEARSON = "pearson";
    public static final String METHOD_SPEARMAN = "spearman";
    public static final String METHOD_OLS_PAIR = "ols_pair";

    private AssociationEvidence() {}

    public static G2RelationshipResult pearson(AlignmentResult alignment, String leftUnit, String rightUnit) {
        return correlation(METHOD_PEARSON, alignment, leftUnit, rightUnit, false);
    }

    public static G2RelationshipResult spearman(AlignmentResult alignment, String leftUnit, String rightUnit) {
        return correlation(METHOD_SPEARMAN, alignment, leftUnit, rightUnit, true);
    }

    public static G2RelationshipResult olsPair(AlignmentResult alignment, String xUnit, String yUnit) {
        Objects.requireNonNull(alignment, "alignment");
        U5MethodRegistry.assertWithinComplexity(U5MethodRegistry.requireEnabled(METHOD_OLS_PAIR),
                Math.max(alignment.leftConsidered(), alignment.rightConsidered()), alignment.alignedPairs());
        PairwiseFinitePairs pairs = PairwiseFinitePairs.from(alignment);
        Map<String, String> metrics = baseMetrics(alignment, pairs, xUnit, yUnit);
        if (pairs.usedCount() < 2L) {
            return insufficient(METHOD_OLS_PAIR, pairs.usedCount(), metrics, "insufficient_support");
        }
        SimpleOls fit = SimpleOls.fit(pairs.x(), pairs.y());
        if (fit.insufficient()) {
            return insufficient(METHOD_OLS_PAIR, pairs.usedCount(), metrics, "insufficient_variance");
        }
        metrics.put("slope", Double.toString(fit.slope()));
        metrics.put("intercept", Double.toString(fit.intercept()));
        metrics.put("rSquared", Double.toString(fit.rSquared()));
        metrics.put("sse", Double.toString(fit.sse()));
        metrics.put("mse", Double.toString(fit.mse()));
        metrics.put("mseDenominator", "n-2");
        metrics.put("residualMean", Double.toString(fit.residualMean()));
        metrics.put("residualStddev", Double.toString(fit.residualStddev()));
        metrics.put("residualMin", Double.toString(fit.residualMin()));
        metrics.put("residualMax", Double.toString(fit.residualMax()));
        // Paired OLS allows distinct x/y units; slope unit is y-unit / x-unit when both known.
        String xu = AnalysisUnitPropagation.echo(xUnit);
        String yu = AnalysisUnitPropagation.echo(yUnit);
        if (xu != null && yu != null) {
            metrics.put("slopeUnit", yu + " / " + xu);
        }
        metrics.put(AnalysisOutcomeCodes.METRIC_KEY, "association");
        metrics.put("summary", RelationshipWording.associationSummary("ols_pair", fit.rSquared(), pairs.usedCount()));
        return G2RelationshipResult.builder()
                .status(EvidenceStatus.SUCCESS)
                .outcomeCode("association")
                .methodId(METHOD_OLS_PAIR)
                .supportN(pairs.usedCount())
                .metrics(metrics)
                .build();
    }

    private static G2RelationshipResult correlation(String methodId, AlignmentResult alignment,
            String leftUnit, String rightUnit, boolean spearman) {
        Objects.requireNonNull(alignment, "alignment");
        U5MethodRegistry.assertWithinComplexity(U5MethodRegistry.requireEnabled(methodId),
                Math.max(alignment.leftConsidered(), alignment.rightConsidered()), alignment.alignedPairs());
        PairwiseFinitePairs pairs = PairwiseFinitePairs.from(alignment);
        Map<String, String> metrics = baseMetrics(alignment, pairs, leftUnit, rightUnit);
        if (pairs.usedCount() < 2L) {
            return insufficient(methodId, pairs.usedCount(), metrics, "insufficient_support");
        }
        double[] x = pairs.x();
        double[] y = pairs.y();
        if (spearman) {
            x = AverageRanks.ofFinite(x);
            y = AverageRanks.ofFinite(y);
        }
        StablePearson p = StablePearson.ofPairedFinite(x, y);
        if (p.insufficient()) {
            return insufficient(methodId, pairs.usedCount(), metrics, "insufficient_variance");
        }
        // Spearman metrics are rank-space only — do not emit value-space pearsonR/covariance keys (DIK-5 naming).
        if (spearman) {
            metrics.put("rankCovariance", Double.toString(p.covariance()));
            metrics.put("spearmanRho", Double.toString(p.pearson()));
        } else {
            metrics.put("covariance", Double.toString(p.covariance()));
            metrics.put("pearsonR", Double.toString(p.pearson()));
        }
        metrics.put(AnalysisOutcomeCodes.METRIC_KEY, "association");
        metrics.put("summary", RelationshipWording.associationSummary(methodId, p.pearson(), pairs.usedCount()));
        return G2RelationshipResult.builder()
                .status(EvidenceStatus.SUCCESS)
                .outcomeCode("association")
                .methodId(methodId)
                .supportN(pairs.usedCount())
                .metrics(metrics)
                .build();
    }

    private static Map<String, String> baseMetrics(AlignmentResult alignment, PairwiseFinitePairs pairs,
            String leftUnit, String rightUnit) {
        Map<String, String> m = new LinkedHashMap<>();
        m.putAll(alignment.toMetrics());
        m.putAll(pairs.toMetrics());
        String lu = AnalysisUnitPropagation.echo(leftUnit);
        String ru = AnalysisUnitPropagation.echo(rightUnit);
        if (lu != null) {
            m.put("leftUnit", lu);
        }
        if (ru != null) {
            m.put("rightUnit", ru);
        }
        return m;
    }

    private static G2RelationshipResult insufficient(String methodId, long supportN,
            Map<String, String> metrics, String outcome) {
        Map<String, String> m = new LinkedHashMap<>(metrics);
        m.put(AnalysisOutcomeCodes.METRIC_KEY, outcome);
        m.put("summary", RelationshipWording.insufficientSummary(outcome));
        return G2RelationshipResult.builder()
                .status(EvidenceStatus.INSUFFICIENT_EVIDENCE)
                .outcomeCode(outcome)
                .methodId(methodId)
                .supportN(supportN)
                .metrics(m)
                .build();
    }

    /** Format helper kept package-visible for tests. */
    static String formatR(double r) {
        return String.format(Locale.ROOT, "%.6f", r);
    }
}
