package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AlertSpecificAckPolicyTest {

    @Test
    void emptyWhenZeroRows() {
        assertEquals(AlertSpecificAckPolicy.ProbeOutcome.EMPTY, AlertSpecificAckPolicy.classifyUnackedRowCount(0));
    }

    @Test
    void withinLimitForOneThroughMax() {
        assertEquals(AlertSpecificAckPolicy.ProbeOutcome.WITHIN_LIMIT, AlertSpecificAckPolicy.classifyUnackedRowCount(1));
        assertEquals(AlertSpecificAckPolicy.ProbeOutcome.WITHIN_LIMIT, AlertSpecificAckPolicy.classifyUnackedRowCount(500));
    }

    @Test
    void exceedsLimitWhenProbeReturnsMoreThanMaxBatch() {
        assertEquals(AlertSpecificAckPolicy.ProbeOutcome.EXCEEDS_LIMIT, AlertSpecificAckPolicy.classifyUnackedRowCount(501));
        assertEquals(AlertSpecificAckPolicy.ProbeOutcome.EXCEEDS_LIMIT, AlertSpecificAckPolicy.classifyUnackedRowCount(10_000));
    }

    @Test
    void rejectsNegativeRowCount() {
        assertThrows(IllegalArgumentException.class, () -> AlertSpecificAckPolicy.classifyUnackedRowCount(-1));
    }

    @Test
    void probeMaxItemsIsOnePastMaxBatch() {
        assertEquals(AlertSpecificAckPolicy.MAX_BATCH_ROWS + 1, AlertSpecificAckPolicy.PROBE_MAX_ITEMS);
    }
}
