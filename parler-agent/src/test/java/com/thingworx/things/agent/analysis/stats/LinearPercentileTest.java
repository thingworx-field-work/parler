package com.thingworx.things.agent.analysis.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LinearPercentileTest {

    @Test
    void type7Interpolation_hEqualsNMinusOneTimesP() {
        double[] sorted = {1.0, 2.0, 3.0, 4.0};
        // n=4, p=0.5 → rank=(4-1)*0.5=1.5 → 2.5
        assertEquals(2.5, LinearPercentile.ofSorted(sorted, 0.5), 1e-12);
        assertEquals(1.0, LinearPercentile.ofSorted(sorted, 0.0), 1e-12);
        assertEquals(4.0, LinearPercentile.ofSorted(sorted, 1.0), 1e-12);
        // p=0.25 → rank=0.75 → 1 + 0.75*(2-1)=1.75
        assertEquals(1.75, LinearPercentile.ofSorted(sorted, 0.25), 1e-12);
    }

    @Test
    void ofSortsCopy() {
        double[] values = {4.0, 1.0, 3.0, 2.0};
        assertEquals(2.5, LinearPercentile.of(values, 0.5), 1e-12);
        assertEquals(4.0, values[0], 0.0); // input not mutated
    }
}
