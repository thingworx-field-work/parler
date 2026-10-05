package com.thingworx.things.agent.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.analysis.AnalysisEnvelope;
import com.thingworx.things.agent.analysis.AnalysisEnvelopeValidator;
import com.thingworx.things.agent.analysis.AnalysisOperation;
import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

class FleetDistributionEngineTest {

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

    private static CohortCollectionResult collect(
            FrozenCohortMembership mem,
            List<CohortBatchMemberRow> rows,
            boolean permissionLimited,
            CompletenessStatus completeness) {
        return CohortCollector.collectFromRows(
                mem, SPEC, rows, permissionLimited, completeness, MemberQualityGate.allowAll());
    }

    @Test
    void smallCohortRanksAndBuildsEnvelope() {
        CohortCollectionResult collection = collect(
                membership("a", "b", "c"),
                List.of(valued("a", 10), valued("b", 20), valued("c", 30)),
                false,
                CompletenessStatus.COMPLETE);
        FleetBenchmarkResult result = FleetDistributionEngine.distribute(
                collection, RankingDirection.HIGHER_IS_BETTER, 2, "a");
        assertEquals(EvidenceStatus.SUCCESS, result.status());
        assertEquals(3, result.distribution().comparableN());
        assertTrue(result.focusOutsideTopN());
        assertEquals(3, result.focusPosition().competitionRank());
        assertEquals(3, result.topWithFocus().size());
        assertEquals(6, FleetPositionEvidence.COLUMNS.size());
        assertEquals(3, result.compactPositionRows().size());

        AnalysisEnvelope envelope = U6FleetEnvelopeFactory.fromBenchmark(result, "digest");
        AnalysisEnvelopeValidator.validateOrThrow(envelope);
        assertEquals(AnalysisOperation.FLEET_BENCHMARK, envelope.operation());
        assertEquals(U6FleetEnvelopeFactory.CHART_INTENT, envelope.chartIntent());
        assertEquals("SUCCESS", envelope.metrics().get("outcomeCode"));
    }

    @Test
    void tiesShareCompetitionRank() {
        CohortCollectionResult collection = collect(
                membership("a", "b", "c"),
                List.of(valued("a", 10), valued("b", 20), valued("c", 20)),
                false,
                CompletenessStatus.COMPLETE);
        FleetBenchmarkResult result = FleetDistributionEngine.distribute(
                collection, RankingDirection.HIGHER_IS_BETTER, 10, null);
        MemberPosition b = result.topWithFocus().stream()
                .filter(p -> "b".equals(p.semanticAssetId()))
                .findFirst()
                .orElseThrow();
        MemberPosition c = result.topWithFocus().stream()
                .filter(p -> "c".equals(p.semanticAssetId()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, b.competitionRank());
        assertEquals(1, c.competitionRank());
    }

    @Test
    void outlierHasDefinedRobustZ() {
        CohortCollectionResult collection = collect(
                membership("a", "b", "c", "outlier"),
                List.of(valued("a", 10), valued("b", 11), valued("c", 12), valued("outlier", 100)),
                false,
                CompletenessStatus.COMPLETE);
        FleetBenchmarkResult result = FleetDistributionEngine.distribute(
                collection, RankingDirection.HIGHER_IS_BETTER, 3, "outlier");
        assertNotNull(result.focusPosition().robustZ());
        assertTrue(result.focusPosition().robustZ() > 2.0);
        assertFalse(result.zeroDispersion());
    }

    @Test
    void zeroDispersionLeavesRobustZUndefined() {
        CohortCollectionResult collection = collect(
                membership("a", "b", "c"),
                List.of(valued("a", 5), valued("b", 5), valued("c", 5)),
                false,
                CompletenessStatus.COMPLETE);
        FleetBenchmarkResult result = FleetDistributionEngine.distribute(
                collection, RankingDirection.HIGHER_IS_BETTER, 2, "a");
        assertTrue(result.zeroDispersion());
        assertNull(result.focusPosition().robustZ());
        AnalysisEnvelope envelope = U6FleetEnvelopeFactory.fromBenchmark(result, "d");
        assertTrue(envelope.summaryFacts().stream().anyMatch(s -> s.contains("mad_zero")));
    }

    @Test
    void partialPermissionYieldsCohortPartialSuccess() {
        List<CohortBatchMemberRow> rows = List.of(
                valued("a", 1.0),
                CohortBatchMemberRow.builder()
                        .semanticAssetId("b")
                        .status(CohortMemberStatus.NO_DATA)
                        .build(),
                CohortBatchMemberRow.builder()
                        .status(CohortMemberStatus.PERMISSION_LIMITED)
                        .build());
        CohortCollectionResult collection = collect(
                membership("a", "b", "c"), rows, true, CompletenessStatus.PARTIAL);
        FleetBenchmarkResult result = FleetDistributionEngine.distribute(
                collection, RankingDirection.HIGHER_IS_BETTER, 5, "a");
        assertEquals(EvidenceStatus.SUCCESS, result.status());
        assertTrue(result.reasonCodes().contains(FleetOutcomeCodes.COHORT_PARTIAL));
        AnalysisEnvelope envelope = U6FleetEnvelopeFactory.fromBenchmark(result, "d");
        assertEquals(FleetOutcomeCodes.COHORT_PARTIAL, envelope.metrics().get("outcomeCode"));
        assertTrue(envelope.summaryFacts().stream()
                .anyMatch(s -> s.contains("among_authorized_comparable_members_covered")));
    }

    @Test
    void noComparableMembersIsNoFinding() {
        CohortCollectionResult collection = collect(
                membership("a", "b"),
                List.of(
                        CohortBatchMemberRow.builder()
                                .semanticAssetId("a")
                                .status(CohortMemberStatus.NO_DATA)
                                .build(),
                        CohortBatchMemberRow.builder()
                                .semanticAssetId("b")
                                .status(CohortMemberStatus.NO_DATA)
                                .build()),
                false,
                CompletenessStatus.COMPLETE);
        FleetBenchmarkResult result = FleetDistributionEngine.distribute(
                collection, RankingDirection.LOWER_IS_BETTER, 3, "a");
        assertEquals(EvidenceStatus.NO_FINDING, result.status());
        assertTrue(result.reasonCodes().contains(FleetOutcomeCodes.NO_COMPARABLE_MEMBERS));
        // Authorized focus with NO_DATA is not FOCUS_NOT_IN_COHORT.
        assertFalse(result.reasonCodes().contains(FleetOutcomeCodes.FOCUS_NOT_IN_COHORT));
        assertEquals(CohortMemberStatus.NO_DATA, result.focusGatedStatus());
        assertNull(result.distribution());
        assertTrue(result.topWithFocus().isEmpty());
        AnalysisEnvelope envelope = U6FleetEnvelopeFactory.fromBenchmark(result, "d");
        assertEquals(EvidenceStatus.NO_FINDING, envelope.status());
        assertEquals(FleetOutcomeCodes.NO_COMPARABLE_MEMBERS, envelope.metrics().get("outcomeCode"));
        assertEquals("NO_DATA", envelope.metrics().get("focusMemberStatus"));
        assertFalse(envelope.evidence().warnings().contains(FleetOutcomeCodes.FOCUS_NOT_IN_COHORT));
    }

    @Test
    void authorizedNonComparableFocusDoesNotUseFocusNotInCohort() {
        CohortCollectionResult collection = collect(
                membership("a", "b"),
                List.of(
                        valued("a", 10),
                        CohortBatchMemberRow.builder()
                                .semanticAssetId("b")
                                .status(CohortMemberStatus.NO_DATA)
                                .build()),
                false,
                CompletenessStatus.COMPLETE);
        FleetBenchmarkResult result = FleetDistributionEngine.distribute(
                collection, RankingDirection.HIGHER_IS_BETTER, 5, "b");
        assertEquals(EvidenceStatus.SUCCESS, result.status());
        assertFalse(result.reasonCodes().contains(FleetOutcomeCodes.FOCUS_NOT_IN_COHORT));
        assertEquals(CohortMemberStatus.NO_DATA, result.focusGatedStatus());
        assertNull(result.focusPosition());
        AnalysisEnvelope envelope = U6FleetEnvelopeFactory.fromBenchmark(result, "d");
        assertEquals("NO_DATA", envelope.metrics().get("focusMemberStatus"));
        assertFalse(envelope.evidence().warnings().contains(FleetOutcomeCodes.FOCUS_NOT_IN_COHORT));
    }

    @Test
    void focusOutsideFrozenAuthorizedSetIsFocusNotInCohort() {
        CohortCollectionResult collection = collect(
                membership("a", "b"),
                List.of(valued("a", 10), valued("b", 20)),
                false,
                CompletenessStatus.COMPLETE);
        FleetBenchmarkResult result = FleetDistributionEngine.distribute(
                collection, RankingDirection.HIGHER_IS_BETTER, 5, "not-in-cohort");
        assertEquals(EvidenceStatus.SUCCESS, result.status());
        assertTrue(result.reasonCodes().contains(FleetOutcomeCodes.FOCUS_NOT_IN_COHORT));
        assertNull(result.focusGatedStatus());
        assertNull(result.focusPosition());
    }
}
