package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Live {@link PeriodOverPeriodPopSupport#validateUnitCompatibility} coverage after S13 removed the
 * retired same-window multi-series redirect helpers.
 */
class PeriodOverPeriodPopSupportTest {

    @Test
    void validateUnitCompatibility() {
        assertNull(PeriodOverPeriodPopSupport.validateUnitCompatibility(Map.of(
                "A", "A", "B", "A")));
        assertNull(PeriodOverPeriodPopSupport.validateUnitCompatibility(Map.of(
                "A", "", "B", "")));
        assertNotNull(PeriodOverPeriodPopSupport.validateUnitCompatibility(Map.of(
                "A", "A", "B", "V")));
    }
}
