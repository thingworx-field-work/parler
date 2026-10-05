package com.thingworx.things.agent.analysis.changepoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.analysis.G1DetectionResult;
import com.thingworx.things.agent.analysis.stats.NumericSeries;
import com.thingworx.things.agent.evidence.EvidenceStatus;

class BinarySegmentationTest {

    @Test
    void detectsSingleMeanShift() {
        double[] v = new double[20];
        for (int i = 0; i < 10; i++) {
            v[i] = 0.0;
        }
        for (int i = 10; i < 20; i++) {
            v[i] = 10.0;
        }
        G1DetectionResult r = BinarySegmentation.detect(NumericSeries.fromFiniteValues(v, null),
                new BinarySegmentation.Config(1, 3, 1.0, 0.1));
        assertEquals(EvidenceStatus.SUCCESS, r.status());
        assertEquals(1, r.findings().size());
        assertEquals(10L, r.findings().get(0).sourceOrdinal());
        assertEquals(10.0, r.findings().get(0).effect(), 1e-9);
    }

    @Test
    void noFindingOnFlatSeries() {
        double[] v = {1, 1.1, 0.9, 1.05, 0.95, 1.02, 0.98, 1.01, 0.99, 1.0};
        G1DetectionResult r = BinarySegmentation.detect(NumericSeries.fromFiniteValues(v, null),
                BinarySegmentation.Config.defaults());
        assertEquals(EvidenceStatus.NO_FINDING, r.status());
        assertEquals("no_change_point", r.outcomeCode());
    }

    @Test
    void shortSeriesInsufficient() {
        G1DetectionResult r = BinarySegmentation.detect(
                NumericSeries.fromFiniteValues(new double[] {1, 2, 3}, null),
                BinarySegmentation.Config.defaults());
        assertEquals(EvidenceStatus.INSUFFICIENT_EVIDENCE, r.status());
        assertTrue(r.supportN() < 6);
    }

    @Test
    void prefixRangeQueriesAreLinearInN_forSingleCandidate() {
        int n = 400;
        double[] v = new double[n];
        for (int i = 0; i < n / 2; i++) {
            v[i] = 0.0;
        }
        for (int i = n / 2; i < n; i++) {
            v[i] = 5.0;
        }
        AtomicLong queries = new AtomicLong();
        BinarySegmentation.detect(NumericSeries.fromFiniteValues(v, null),
                new BinarySegmentation.Config(1, 3, 1.0, 0.05), queries);
        // One segment: 1 parent + 2 per candidate split ≈ O(n). Nested full rescans would be ≫ n².
        assertTrue(queries.get() > 0L);
        assertTrue(queries.get() < 5L * n,
                "expected O(n) prefix range queries, got " + queries.get() + " for n=" + n);
    }
}
