package com.thingworx.things.agent.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

class CohortCoverageDisclosureTest {

    @Test
    void permissionLimitedRowsDoNotEnterAuthorizedCounts() {
        CohortBatchSourceResult result = CohortBatchSourceResult.builder()
                .completeness(CompletenessStatus.PARTIAL)
                .permissionLimited(true)
                .rows(List.of(
                        CohortBatchMemberRow.builder()
                                .semanticAssetId("asset-a")
                                .status(CohortMemberStatus.ELIGIBLE_VALUE)
                                .metricValue(1.0)
                                .unit("C")
                                .grain("1m")
                                .methodId("kpi_v1")
                                .build(),
                        CohortBatchMemberRow.builder()
                                .status(CohortMemberStatus.PERMISSION_LIMITED)
                                .build(),
                        CohortBatchMemberRow.builder()
                                .semanticAssetId("asset-b")
                                .status(CohortMemberStatus.NO_DATA)
                                .build()))
                .build();

        CohortCoverageCounts counts = result.toCoverageCounts(2);
        assertEquals(2, counts.requestedAuthorizedN());
        assertEquals(2, counts.returnedN());
        assertEquals(1, counts.valuedN());
        assertEquals(0, counts.comparableN(), "source-stage helper leaves comparableN for FRC-1 gates");
        assertEquals(1, counts.noDataN());
        assertEquals(0, counts.errorN());
        assertTrue(counts.permissionLimited());
    }

    @Test
    void permissionLimitedRowOmitsIdentity() {
        CohortBatchMemberRow row = CohortBatchMemberRow.builder()
                .status(CohortMemberStatus.PERMISSION_LIMITED)
                .semanticAssetId("secret-peer")
                .build();
        assertNull(row.semanticAssetId());
        assertNull(row.metricValue());
    }

    @Test
    void eligibleValueRequiresFiniteMetric() {
        assertThrows(IllegalArgumentException.class, () -> CohortBatchMemberRow.builder()
                .semanticAssetId("a")
                .status(CohortMemberStatus.ELIGIBLE_VALUE)
                .build());
    }
}
