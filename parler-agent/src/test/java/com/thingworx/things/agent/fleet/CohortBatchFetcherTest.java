package com.thingworx.things.agent.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.execution.BudgetVector;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

class CohortBatchFetcherTest {

    private static final HalfOpenWindow WINDOW = HalfOpenWindow.of(
            Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-02T00:00:00Z"));

    @Test
    void fetchAllMergesPagesAndCompleteness() {
        FrozenCohortMembership mem = FrozenCohortMembership.freeze(
                "peer", "metric", WINDOW, List.of("a", "b"));
        CohortBatchMemberRow a = CohortBatchMemberRow.builder()
                .semanticAssetId("a")
                .status(CohortMemberStatus.ELIGIBLE_VALUE)
                .metricValue(1.0)
                .unit("C")
                .grain("1m")
                .methodId("kpi_v1")
                .build();
        CohortBatchMemberRow b = CohortBatchMemberRow.builder()
                .semanticAssetId("b")
                .status(CohortMemberStatus.NO_DATA)
                .build();
        FixtureCohortBatchSource source = FixtureCohortBatchSource.pages(
                List.of(List.of(a), List.of(b)),
                List.of(CompletenessStatus.PARTIAL, CompletenessStatus.COMPLETE),
                true);
        CohortBatchFetcher.FetchedBatch fetched =
                CohortBatchFetcher.fetchAll(source, mem, null, BudgetVector.defaultsForTabular());
        assertEquals(2, fetched.rows().size());
        assertTrue(fetched.permissionLimited());
        assertEquals(CompletenessStatus.PARTIAL, fetched.completeness());
    }
}
