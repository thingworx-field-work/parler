package com.thingworx.things.agent.fleet;

import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.cache.ArtifactAccessContext;
import com.thingworx.things.agent.execution.BudgetVector;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

/**
 * FRC-1 orchestrator: freeze → batch fetch (budgeted) → comparability/quality gates →
 * membership reconcile → evidence rows. Distribution/rank (FRC-2) and model advertisement (D3)
 * are out of scope.
 */
public final class CohortCollector {

    private CohortCollector() {}

    public static CohortCollectionResult collect(
            FrozenCohortMembership membership,
            MetricComparabilitySpec spec,
            CohortBatchSource source,
            ArtifactAccessContext access,
            BudgetVector budget,
            MemberQualityGate qualityGate) {
        Objects.requireNonNull(membership, "membership");
        CohortBatchFetcher.FetchedBatch fetched =
                CohortBatchFetcher.fetchAll(source, membership, access, budget);
        return CohortGatePipeline.apply(
                membership,
                spec,
                fetched.rows(),
                fetched.permissionLimited(),
                fetched.completeness(),
                qualityGate);
    }

    /** Test/source seam: gate already-fetched rows without invoking a batch Service. */
    public static CohortCollectionResult collectFromRows(
            FrozenCohortMembership membership,
            MetricComparabilitySpec spec,
            List<CohortBatchMemberRow> sourceRows,
            boolean permissionLimited,
            CompletenessStatus sourceCompleteness,
            MemberQualityGate qualityGate) {
        return CohortGatePipeline.apply(
                membership, spec, sourceRows, permissionLimited, sourceCompleteness, qualityGate);
    }
}
