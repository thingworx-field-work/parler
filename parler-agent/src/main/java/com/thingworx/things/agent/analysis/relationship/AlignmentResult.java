package com.thingworx.things.agent.analysis.relationship;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exact or nearest-within-tolerance alignment report (§7.4). Pairwise finite deletion is applied
 * later by association methods; this result keeps all matched rows.
 */
public final class AlignmentResult {

    private final String mode;
    private final List<AlignedPair> pairs;
    private final long leftConsidered;
    private final long rightConsidered;
    private final long leftUnmatched;
    private final long rightUnmatched;
    private final long maxAbsDeltaMillis;
    private final long medianAbsDeltaMillis;

    public AlignmentResult(String mode, List<AlignedPair> pairs, long leftConsidered, long rightConsidered,
            long leftUnmatched, long rightUnmatched, long maxAbsDeltaMillis, long medianAbsDeltaMillis) {
        this.mode = Objects.requireNonNull(mode, "mode").trim();
        this.pairs = List.copyOf(Objects.requireNonNull(pairs, "pairs"));
        this.leftConsidered = leftConsidered;
        this.rightConsidered = rightConsidered;
        this.leftUnmatched = leftUnmatched;
        this.rightUnmatched = rightUnmatched;
        this.maxAbsDeltaMillis = maxAbsDeltaMillis;
        this.medianAbsDeltaMillis = medianAbsDeltaMillis;
    }

    public String mode() {
        return mode;
    }

    public List<AlignedPair> pairs() {
        return pairs;
    }

    public long leftConsidered() {
        return leftConsidered;
    }

    public long rightConsidered() {
        return rightConsidered;
    }

    public long leftUnmatched() {
        return leftUnmatched;
    }

    public long rightUnmatched() {
        return rightUnmatched;
    }

    public long alignedPairs() {
        return pairs.size();
    }

    public long maxAbsDeltaMillis() {
        return maxAbsDeltaMillis;
    }

    public long medianAbsDeltaMillis() {
        return medianAbsDeltaMillis;
    }

    public Map<String, String> toMetrics() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("alignmentMode", mode);
        m.put("leftConsidered", Long.toString(leftConsidered));
        m.put("rightConsidered", Long.toString(rightConsidered));
        m.put("alignedPairs", Long.toString(pairs.size()));
        m.put("leftUnmatched", Long.toString(leftUnmatched));
        m.put("rightUnmatched", Long.toString(rightUnmatched));
        m.put("maxAbsDeltaMillis", Long.toString(maxAbsDeltaMillis));
        m.put("medianAbsDeltaMillis", Long.toString(medianAbsDeltaMillis));
        return Collections.unmodifiableMap(m);
    }
}
