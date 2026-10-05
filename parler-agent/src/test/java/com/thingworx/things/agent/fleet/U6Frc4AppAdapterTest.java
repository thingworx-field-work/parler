package com.thingworx.things.agent.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.analysis.AnalysisEnvelope;
import com.thingworx.things.agent.analysis.AnalysisEnvelopeValidator;
import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.analysis.config.U6DemoAppProfiles;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.execution.BudgetVector;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

/**
 * FRC-4: demo peer adapter + App runner — batch path, permission disclosure, envelope budget.
 */
class U6Frc4AppAdapterTest {

    private static final HalfOpenWindow WINDOW = HalfOpenWindow.of(
            Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-02T00:00:00Z"));
    private static final MetricComparabilitySpec SPEC =
            new MetricComparabilitySpec("C", "1m", "kpi_v1");

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
    void demoAdapterBatchFetchDoesNotScaleWithModelCalls() {
        List<CohortBatchMemberRow> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            rows.add(valued("asset-" + i, 10.0 + i));
        }
        DemoPeerCohortBatchAdapter adapter = new DemoPeerCohortBatchAdapter(
                rows, false, CompletenessStatus.COMPLETE, 25);
        FrozenCohortMembership mem = FrozenCohortMembership.freeze(
                "peer-v1", "metric-v1", WINDOW, rows.stream().map(CohortBatchMemberRow::semanticAssetId).toList());

        U6FleetBenchmarkAppRunner.Outcome outcome = U6FleetBenchmarkAppRunner.run(
                mem,
                SPEC,
                adapter,
                null,
                BudgetVector.defaultsForTabular(),
                RankingDirection.HIGHER_IS_BETTER,
                5,
                "asset-0",
                U6DemoAppProfiles.profileDigest(U6DemoAppProfiles.Surface.FLEET_BENCHMARK));

        // Two pages for 40 members at pageSize 25 — fixed batch fetches, not 40 model tool calls.
        assertEquals(2, adapter.fetchCount());
        assertEquals(EvidenceStatus.SUCCESS, outcome.result().status());
        assertEquals(40, outcome.result().publishedCoverage().comparableN());
        AnalysisEnvelope envelope = outcome.envelope();
        AnalysisEnvelopeValidator.validateOrThrow(envelope);
        assertNotNull(envelope.budget());
        assertEquals(40L, envelope.budget().consumedRows());
        assertTrue(outcome.result().focusOutsideTopN());
    }

    @Test
    void permissionLimitedDisclosedWithoutUnauthorizedIdentities() {
        DemoPeerCohortBatchAdapter adapter = DemoPeerCohortBatchAdapter.permissionLimited(
                List.of(valued("a", 1), valued("b", 2), valued("c", 3)));
        FrozenCohortMembership mem =
                FrozenCohortMembership.freeze("peer-v1", "metric-v1", WINDOW, List.of("a", "b", "c"));

        U6FleetBenchmarkAppRunner.Outcome outcome = U6FleetBenchmarkAppRunner.run(
                mem,
                SPEC,
                adapter,
                null,
                BudgetVector.defaultsForTabular(),
                RankingDirection.HIGHER_IS_BETTER,
                10,
                "a",
                DemoPeerCohortBatchAdapter.PROFILE_DIGEST);

        assertTrue(outcome.result().publishedCoverage().permissionLimited());
        assertTrue(outcome.result().reasonCodes().contains(FleetOutcomeCodes.COHORT_PARTIAL));
        assertEquals(1, adapter.fetchCount());
        String envelopeJson = outcome.envelope().metrics().toString();
        assertFalse(envelopeJson.contains("unauthorizedN"));
        assertFalse(envelopeJson.contains("peerSetSize"));
    }

    @Test
    void demoProfilesDoNotAdvertiseModelSurface() {
        assertTrue(U6DemoAppProfiles.advertisedOperations().isEmpty());
        assertEquals("u6-demo-fleet-peer-v1", U6DemoAppProfiles.profileDigest(
                U6DemoAppProfiles.Surface.FLEET_BENCHMARK));
    }
}
