package com.thingworx.things.agent.analysis.relationship;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pairwise deletion of non-finite aligned values (§7.5). No imputation.
 */
public final class PairwiseFinitePairs {

    private final double[] x;
    private final double[] y;
    private final List<AlignedPair> used;
    private final long considered;
    private final long dropped;

    private PairwiseFinitePairs(double[] x, double[] y, List<AlignedPair> used, long considered, long dropped) {
        this.x = x;
        this.y = y;
        this.used = used;
        this.considered = considered;
        this.dropped = dropped;
    }

    public static PairwiseFinitePairs from(AlignmentResult alignment) {
        Objects.requireNonNull(alignment, "alignment");
        List<AlignedPair> used = new ArrayList<>();
        for (AlignedPair p : alignment.pairs()) {
            if (p.bothFinite()) {
                used.add(p);
            }
        }
        double[] x = new double[used.size()];
        double[] y = new double[used.size()];
        for (int i = 0; i < used.size(); i++) {
            x[i] = used.get(i).left().value();
            y[i] = used.get(i).right().value();
        }
        long considered = alignment.alignedPairs();
        long dropped = considered - used.size();
        return new PairwiseFinitePairs(x, y, List.copyOf(used), considered, dropped);
    }

    public double[] x() {
        return x.clone();
    }

    public double[] y() {
        return y.clone();
    }

    public List<AlignedPair> used() {
        return used;
    }

    public long usedCount() {
        return used.size();
    }

    public long considered() {
        return considered;
    }

    public long dropped() {
        return dropped;
    }

    public Map<String, String> toMetrics() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("pairsConsidered", Long.toString(considered));
        m.put("pairsUsed", Long.toString(used.size()));
        m.put("pairsDropped", Long.toString(dropped));
        m.put("missingPolicy", "pairwise_deletion");
        return m;
    }
}
