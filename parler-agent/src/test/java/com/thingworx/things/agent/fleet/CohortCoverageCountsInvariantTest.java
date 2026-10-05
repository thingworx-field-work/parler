package com.thingworx.things.agent.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CohortCoverageCountsInvariantTest {

    @Test
    void builderRejectsOutcomeSumMismatch() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CohortCoverageCounts.builder()
                        .requestedAuthorizedN(2)
                        .returnedN(2)
                        .valuedN(2)
                        .comparableN(2)
                        .noDataN(2)
                        .errorN(2)
                        .build());
        assertTrue(ex.getMessage().contains("must equal returnedN"));
    }

    @Test
    void builderAcceptsReconciledPartition() {
        CohortCoverageCounts counts = CohortCoverageCounts.builder()
                .requestedAuthorizedN(5)
                .returnedN(5)
                .valuedN(2)
                .comparableN(1)
                .noDataN(1)
                .incomparableN(1)
                .insufficientEvidenceN(1)
                .errorN(0)
                .permissionLimited(true)
                .build();
        assertEquals(2, counts.valuedN());
        assertEquals(1, counts.comparableN());
        assertTrue(counts.permissionLimited());
    }

    @Test
    void builderRejectsComparableExceedingValued() {
        assertThrows(IllegalArgumentException.class, () -> CohortCoverageCounts.builder()
                .requestedAuthorizedN(2)
                .returnedN(2)
                .valuedN(1)
                .comparableN(2)
                .noDataN(1)
                .build());
    }
}
