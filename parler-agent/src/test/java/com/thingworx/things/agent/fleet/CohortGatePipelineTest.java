package com.thingworx.things.agent.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

class CohortGatePipelineTest {

    private static final HalfOpenWindow WINDOW = HalfOpenWindow.of(
            Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-02T00:00:00Z"));
    private static final MetricComparabilitySpec SPEC =
            new MetricComparabilitySpec("C", "1m", "kpi_v1");

    private static FrozenCohortMembership membership(String... ids) {
        return FrozenCohortMembership.freeze("peer-v1", "metric-v1", WINDOW, List.of(ids));
    }

    private static CohortBatchMemberRow valued(String id, double v) {
        return CohortBatchMemberRow.builder()
                .semanticAssetId(id)
                .status(CohortMemberStatus.ELIGIBLE_VALUE)
                .metricValue(v)
                .unit("C")
                .grain("1m")
                .methodId("kpi_v1")
                .build();
    }

    @Test
    void completeCohortAllComparable() {
        FrozenCohortMembership mem = membership("a", "b");
        CohortCollectionResult result = CohortCollector.collectFromRows(
                mem,
                SPEC,
                List.of(valued("a", 1.0), valued("b", 2.0)),
                false,
                CompletenessStatus.COMPLETE,
                MemberQualityGate.allowAll());
        CohortCoverageCounts c = result.publishedCoverage();
        assertEquals(2, c.requestedAuthorizedN());
        assertEquals(2, c.returnedN());
        assertEquals(2, c.valuedN());
        assertEquals(2, c.comparableN());
        assertFalse(c.permissionLimited());
        assertFalse(result.batchSourcePartial());
        assertEquals(2, result.comparableMetrics().size());
        assertEquals(2, result.memberEvidence().size());
    }

    @Test
    void unitMismatchDemotesOutOfValuedPartition() {
        FrozenCohortMembership mem = membership("a", "b");
        CohortBatchMemberRow badUnit = CohortBatchMemberRow.builder()
                .semanticAssetId("b")
                .status(CohortMemberStatus.ELIGIBLE_VALUE)
                .metricValue(9.0)
                .unit("F")
                .grain("1m")
                .methodId("kpi_v1")
                .build();
        CohortCollectionResult result = CohortCollector.collectFromRows(
                mem,
                SPEC,
                List.of(valued("a", 1.0), badUnit),
                false,
                CompletenessStatus.COMPLETE,
                MemberQualityGate.allowAll());
        CohortCoverageCounts c = result.publishedCoverage();
        assertEquals(2, c.returnedN());
        assertEquals(1, c.valuedN());
        assertEquals(1, c.comparableN());
        assertEquals(1, c.incomparableN());
        assertEquals(0, c.insufficientEvidenceN());
        assertEquals(1, result.comparableMetrics().size());
        assertEquals("a", result.comparableMetrics().get(0).semanticAssetId());
    }

    @Test
    void qualityBlockingDemotesValuedToInsufficient_preservesPartition() {
        FrozenCohortMembership mem = membership("a", "b");
        CohortCollectionResult result = CohortCollector.collectFromRows(
                mem,
                SPEC,
                List.of(valued("a", 1.0), valued("b", 2.0)),
                false,
                CompletenessStatus.COMPLETE,
                MemberQualityGate.blocking("b"));
        CohortCoverageCounts c = result.publishedCoverage();
        // Demotion moves b out of valuedN into insufficientEvidenceN.
        assertEquals(2, c.returnedN());
        assertEquals(1, c.valuedN());
        assertEquals(1, c.comparableN());
        assertEquals(1, c.insufficientEvidenceN());
        assertEquals(0, c.incomparableN());
        assertEquals("QUALITY_BLOCKING", result.outcomes().stream()
                .filter(o -> "b".equals(o.semanticAssetId()))
                .findFirst()
                .orElseThrow()
                .reasonCode());
    }

    @Test
    void noDataAndPartialPermissionMatrix() {
        FrozenCohortMembership mem = membership("a", "b", "c");
        List<CohortBatchMemberRow> rows = List.of(
                valued("a", 1.0),
                CohortBatchMemberRow.builder()
                        .semanticAssetId("b")
                        .status(CohortMemberStatus.NO_DATA)
                        .build(),
                CohortBatchMemberRow.builder()
                        .status(CohortMemberStatus.PERMISSION_LIMITED)
                        .build());
        // c omitted is legitimate only when source completeness is PARTIAL.
        CohortCollectionResult result = CohortCollector.collectFromRows(
                mem, SPEC, rows, true, CompletenessStatus.PARTIAL, MemberQualityGate.allowAll());
        CohortCoverageCounts c = result.publishedCoverage();
        assertEquals(3, c.requestedAuthorizedN());
        assertEquals(2, c.returnedN());
        assertEquals(1, c.valuedN());
        assertEquals(1, c.comparableN());
        assertEquals(1, c.noDataN());
        assertTrue(c.permissionLimited());
        assertTrue(result.batchSourcePartial());
        assertEquals(2, result.memberEvidence().size());
        for (FleetMemberEvidence e : result.memberEvidence()) {
            assertTrue(e.outcome().semanticAssetId() != null);
            assertFalse(e.outcome().status() == CohortMemberStatus.PERMISSION_LIMITED);
        }
    }

    @Test
    void unknownIdentityDoesNotLeakAsNamedMissing() {
        FrozenCohortMembership mem = membership("a");
        CohortCollectionResult result = CohortCollector.collectFromRows(
                mem,
                SPEC,
                List.of(valued("a", 1.0), valued("secret-peer", 99.0)),
                false,
                CompletenessStatus.COMPLETE,
                MemberQualityGate.allowAll());
        CohortCoverageCounts c = result.publishedCoverage();
        assertEquals(1, c.returnedN());
        assertEquals(1, c.comparableN());
        assertTrue(c.permissionLimited());
        assertTrue(result.outcomes().stream()
                .anyMatch(o -> o.status() == CohortMemberStatus.PERMISSION_LIMITED
                        && o.semanticAssetId() == null));
        assertFalse(result.memberEvidence().stream()
                .anyMatch(e -> "secret-peer".equals(e.outcome().semanticAssetId())));
    }

    @Test
    void evidenceTypedRowsMatchSchema() {
        FrozenCohortMembership mem = membership("a");
        CohortCollectionResult result = CohortCollector.collectFromRows(
                mem,
                SPEC,
                List.of(valued("a", 3.5)),
                false,
                CompletenessStatus.COMPLETE,
                MemberQualityGate.allowAll());
        assertEquals(7, FleetMemberEvidence.COLUMNS.size());
        assertEquals(1, FleetMemberEvidence.toTypedRows(result.memberEvidence()).size());
    }
}
