package com.thingworx.things.agent.analysis.relationship;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.thingworx.things.agent.analysis.AnalysisOutcomeCodes;
import com.thingworx.things.agent.analysis.FindingRow;
import com.thingworx.things.agent.analysis.U5MethodRegistry;
import com.thingworx.things.agent.analysis.stats.StablePearson;
import com.thingworx.things.agent.evidence.EvidenceStatus;

/**
 * Bounded index lag scan after alignment (§7.5). Emits one finding row per attempted lag and
 * selects the greatest absolute Pearson association with deterministic ties.
 */
public final class LagScan {

    public static final String METHOD_ID = "lag_scan";
    public static final String LAG_SELECTION_CAVEAT = "selecting_lag_increases_false_discovery_risk";

    private LagScan() {}

    /**
     * @param maxAbsLag inclusive absolute lag bound (grid = {@code -maxAbsLag .. +maxAbsLag})
     */
    public static G2RelationshipResult evaluate(AlignmentResult alignment, int maxAbsLag) {
        Objects.requireNonNull(alignment, "alignment");
        if (maxAbsLag < 0) {
            throw new IllegalArgumentException("maxAbsLag must be >= 0");
        }
        PairwiseFinitePairs pairs = PairwiseFinitePairs.from(alignment);
        long gridSize = 2L * maxAbsLag + 1L;
        long materialized = pairs.usedCount() * gridSize;
        U5MethodRegistry.assertWithinComplexity(U5MethodRegistry.requireEnabled(METHOD_ID),
                Math.max(alignment.leftConsidered(), alignment.rightConsidered()), materialized);

        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.putAll(alignment.toMetrics());
        metrics.putAll(pairs.toMetrics());
        metrics.put("maxAbsLag", Integer.toString(maxAbsLag));
        metrics.put("lagSelectionCaveat", LAG_SELECTION_CAVEAT);

        if (pairs.usedCount() < 2L) {
            metrics.put(AnalysisOutcomeCodes.METRIC_KEY, "insufficient_support");
            metrics.put("summary", RelationshipWording.insufficientSummary("insufficient_support"));
            return G2RelationshipResult.builder()
                    .status(EvidenceStatus.INSUFFICIENT_EVIDENCE)
                    .outcomeCode("insufficient_support")
                    .methodId(METHOD_ID)
                    .supportN(pairs.usedCount())
                    .metrics(metrics)
                    .build();
        }

        double[] x = pairs.x();
        double[] y = pairs.y();
        List<FindingRow> findings = new ArrayList<>();
        int bestLag = 0;
        double bestAbsR = -1.0;
        double bestR = Double.NaN;
        long bestSupport = 0L;
        int attempted = 0;
        boolean anySuccess = false;

        for (int lag = -maxAbsLag; lag <= maxAbsLag; lag++) {
            LagWindow w = window(x, y, lag);
            attempted++;
            String outcome;
            double r = Double.NaN;
            long support = w.n;
            if (w.n < 2) {
                outcome = "insufficient_support";
            } else {
                StablePearson p = StablePearson.ofPairedFinite(w.x, w.y);
                if (p.insufficient()) {
                    outcome = "insufficient_variance";
                } else {
                    outcome = "association";
                    r = p.pearson();
                    anySuccess = true;
                    double abs = Math.abs(r);
                    int cmp = compareAbsAssociation(abs, bestAbsR);
                    if (cmp > 0
                            || (cmp == 0 && Math.abs(lag) < Math.abs(bestLag))
                            || (cmp == 0 && Math.abs(lag) == Math.abs(bestLag) && lag < bestLag)) {
                        bestAbsR = abs;
                        bestR = r;
                        bestLag = lag;
                        bestSupport = w.n;
                    }
                }
            }
            findings.add(FindingRow.builder()
                    .sourceOrdinal(Math.abs((long) lag))
                    .score(Double.isNaN(r) ? null : r)
                    .effect(Double.isNaN(r) ? null : Math.abs(r))
                    .methodId(METHOD_ID)
                    .outcomeCode(outcome)
                    .segmentOrPair(String.format(Locale.ROOT, "lag=%d;support=%d;r=%s",
                            lag, support, Double.isNaN(r) ? "na" : Double.toString(r)))
                    .build());
        }

        if (!anySuccess) {
            metrics.put(AnalysisOutcomeCodes.METRIC_KEY, "insufficient_variance");
            metrics.put("attemptedLags", Integer.toString(attempted));
            metrics.put("summary", RelationshipWording.insufficientSummary("insufficient_variance"));
            return G2RelationshipResult.builder()
                    .status(EvidenceStatus.INSUFFICIENT_EVIDENCE)
                    .outcomeCode("insufficient_variance")
                    .methodId(METHOD_ID)
                    .supportN(pairs.usedCount())
                    .findings(findings)
                    .metrics(metrics)
                    .build();
        }

        metrics.put("bestLag", Integer.toString(bestLag));
        metrics.put("bestPearsonR", Double.toString(bestR));
        metrics.put("bestAbsPearsonR", Double.toString(bestAbsR));
        metrics.put("bestSupport", Long.toString(bestSupport));
        metrics.put("attemptedLags", Integer.toString(attempted));
        metrics.put(AnalysisOutcomeCodes.METRIC_KEY, "association");
        metrics.put("summary", RelationshipWording.lagScanSummary(bestLag, bestAbsR, bestSupport, attempted));
        return G2RelationshipResult.builder()
                .status(EvidenceStatus.SUCCESS)
                .outcomeCode("association")
                .methodId(METHOD_ID)
                .supportN(bestSupport)
                .findings(findings)
                .metrics(metrics)
                .build();
    }

    /** Strictly greater / equal / less with a tiny epsilon so perfect-r ties stay deterministic. */
    private static int compareAbsAssociation(double a, double b) {
        double diff = a - b;
        if (diff > 1e-12) {
            return 1;
        }
        if (diff < -1e-12) {
            return -1;
        }
        return 0;
    }

    private static LagWindow window(double[] x, double[] y, int lag) {
        // Positive lag: y leads x by lag samples → pair x[i] with y[i+lag].
        int n = x.length;
        if (lag >= 0) {
            int len = n - lag;
            if (len < 1) {
                return LagWindow.empty();
            }
            double[] xx = new double[len];
            double[] yy = new double[len];
            for (int i = 0; i < len; i++) {
                xx[i] = x[i];
                yy[i] = y[i + lag];
            }
            return new LagWindow(xx, yy, len);
        }
        int shift = -lag;
        int len = n - shift;
        if (len < 1) {
            return LagWindow.empty();
        }
        double[] xx = new double[len];
        double[] yy = new double[len];
        for (int i = 0; i < len; i++) {
            xx[i] = x[i + shift];
            yy[i] = y[i];
        }
        return new LagWindow(xx, yy, len);
    }

    private static final class LagWindow {
        final double[] x;
        final double[] y;
        final int n;

        LagWindow(double[] x, double[] y, int n) {
            this.x = x;
            this.y = y;
            this.n = n;
        }

        static LagWindow empty() {
            return new LagWindow(new double[0], new double[0], 0);
        }
    }
}
