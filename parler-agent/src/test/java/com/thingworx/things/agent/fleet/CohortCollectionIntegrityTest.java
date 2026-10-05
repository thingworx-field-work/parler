package com.thingworx.things.agent.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.execution.BudgetVector;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

class CohortCollectionIntegrityTest {

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
    void duplicateSamePageRejected() {
        FrozenCohortMembership mem = membership("a", "b");
        CohortCollectionException ex = assertThrows(
                CohortCollectionException.class,
                () -> CohortCollector.collectFromRows(
                        mem,
                        SPEC,
                        List.of(valued("a", 1.0), valued("a", 2.0)),
                        false,
                        CompletenessStatus.COMPLETE,
                        MemberQualityGate.allowAll()));
        assertEquals(CohortCollectionException.COHORT_MEMBER_DUPLICATE, ex.reasonCode());
    }

    @Test
    void duplicateAcrossPagesRejected() {
        FrozenCohortMembership mem = membership("a", "b");
        FixtureCohortBatchSource source = FixtureCohortBatchSource.pages(
                List.of(List.of(valued("a", 1.0)), List.of(valued("a", 2.0))),
                List.of(CompletenessStatus.PARTIAL, CompletenessStatus.COMPLETE),
                false);
        CohortCollectionException ex = assertThrows(
                CohortCollectionException.class,
                () -> CohortCollector.collect(mem, SPEC, source, null, BudgetVector.defaultsForTabular(),
                        MemberQualityGate.allowAll()));
        assertEquals(CohortCollectionException.COHORT_MEMBER_DUPLICATE, ex.reasonCode());
    }

    @Test
    void completeSourceOmittingAuthorizedMemberFails() {
        FrozenCohortMembership mem = membership("a", "b");
        CohortCollectionException ex = assertThrows(
                CohortCollectionException.class,
                () -> CohortCollector.collectFromRows(
                        mem,
                        SPEC,
                        List.of(valued("a", 1.0)),
                        false,
                        CompletenessStatus.COMPLETE,
                        MemberQualityGate.allowAll()));
        assertEquals(CohortCollectionException.COHORT_MEMBER_OMITTED, ex.reasonCode());
        assertTrue(ex.getMessage().contains("b"));
    }

    @Test
    void partialSourceMayOmitAuthorizedMember() {
        FrozenCohortMembership mem = membership("a", "b");
        CohortCollectionResult result = CohortCollector.collectFromRows(
                mem,
                SPEC,
                List.of(valued("a", 1.0)),
                false,
                CompletenessStatus.PARTIAL,
                MemberQualityGate.allowAll());
        assertTrue(result.batchSourcePartial());
        assertEquals(CompletenessStatus.PARTIAL, result.sourceCompleteness());
        assertEquals(1, result.publishedCoverage().returnedN());
    }

    @Test
    void fetcherPropagatesPartialFinalPage() {
        FrozenCohortMembership mem = membership("a");
        FixtureCohortBatchSource source = FixtureCohortBatchSource.pages(
                List.of(List.of(valued("a", 1.0))),
                List.of(CompletenessStatus.PARTIAL),
                false);
        CohortBatchFetcher.FetchedBatch fetched =
                CohortBatchFetcher.fetchAll(source, mem, null, BudgetVector.defaultsForTabular());
        assertEquals(CompletenessStatus.PARTIAL, fetched.completeness());
        CohortCollectionResult result = CohortCollector.collect(
                mem, SPEC, source, null, BudgetVector.defaultsForTabular(), MemberQualityGate.allowAll());
        assertTrue(result.batchSourcePartial());
    }

    @Test
    void fetcherEnforcesMaxReturnedRows() {
        FrozenCohortMembership mem = membership("a", "b", "c");
        BudgetVector budget = BudgetVector.builder().maxReturnedRows(1L).maxWallTimeMillis(60_000L).build();
        FixtureCohortBatchSource source = FixtureCohortBatchSource.singlePage(
                List.of(valued("a", 1.0), valued("b", 2.0)), false);
        CohortCollectionException ex = assertThrows(
                CohortCollectionException.class,
                () -> CohortBatchFetcher.fetchAll(source, mem, null, budget));
        assertEquals(CohortCollectionException.COHORT_BUDGET_EXCEEDED, ex.reasonCode());
    }

    @Test
    void completeSourceReportingFrozenMemberAsPermissionLimitedFailsAsOmitted() {
        // Contract: COMPLETE + PERMISSION_LIMITED for a frozen authorized
        // id is fail-closed (treated as omission), not silent permission shortfall.
        FrozenCohortMembership mem = membership("a", "b");
        List<CohortBatchMemberRow> rows = List.of(
                valued("a", 1.0),
                CohortBatchMemberRow.builder()
                        .semanticAssetId("b")
                        .status(CohortMemberStatus.PERMISSION_LIMITED)
                        .build());
        CohortCollectionException ex = assertThrows(
                CohortCollectionException.class,
                () -> CohortCollector.collectFromRows(
                        mem,
                        SPEC,
                        rows,
                        false,
                        CompletenessStatus.COMPLETE,
                        MemberQualityGate.allowAll()));
        assertEquals(CohortCollectionException.COHORT_MEMBER_OMITTED, ex.reasonCode());
    }

    @Test
    void fetcherEnforcesWallTime() {
        FrozenCohortMembership mem = membership("a");
        BudgetVector budget = BudgetVector.builder().maxReturnedRows(100L).maxWallTimeMillis(1L).build();
        FixtureCohortBatchSource source = FixtureCohortBatchSource.singlePage(List.of(valued("a", 1.0)), false);
        AtomicLong t = new AtomicLong(0L);
        CohortCollectionException ex = assertThrows(
                CohortCollectionException.class,
                () -> CohortBatchFetcher.fetchAll(source, mem, null, budget, () -> {
                    // First call: start; subsequent calls exceed 1ms budget.
                    long v = t.getAndAdd(2_000_000L);
                    return v;
                }));
        assertEquals(CohortCollectionException.COHORT_BUDGET_EXCEEDED, ex.reasonCode());
    }
}
