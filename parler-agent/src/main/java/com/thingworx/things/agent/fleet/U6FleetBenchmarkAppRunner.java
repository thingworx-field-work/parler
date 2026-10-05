package com.thingworx.things.agent.fleet;

import java.util.Objects;

import com.thingworx.things.agent.analysis.AnalysisBudgetAccounting;
import com.thingworx.things.agent.analysis.AnalysisEnvelope;
import com.thingworx.things.agent.cache.ArtifactAccessContext;
import com.thingworx.things.agent.execution.BudgetVector;

/**
 * App-facing G5 orchestration (FRC-4): freeze → batch collect → distribute → compact envelope.
 * Invoked by Apps / reference Playbook drivers — not model-advertised (D3 packaging).
 */
public final class U6FleetBenchmarkAppRunner {

    private U6FleetBenchmarkAppRunner() {}

    public static final class Outcome {
        private final FleetBenchmarkResult result;
        private final AnalysisEnvelope envelope;

        Outcome(FleetBenchmarkResult result, AnalysisEnvelope envelope) {
            this.result = result;
            this.envelope = envelope;
        }

        public FleetBenchmarkResult result() {
            return result;
        }

        public AnalysisEnvelope envelope() {
            return envelope;
        }
    }

    public static Outcome run(
            FrozenCohortMembership membership,
            MetricComparabilitySpec spec,
            CohortBatchSource source,
            ArtifactAccessContext access,
            BudgetVector budget,
            RankingDirection direction,
            int topN,
            String focusAssetId,
            String profileDigest) {
        Objects.requireNonNull(membership, "membership");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(direction, "direction");

        long started = System.nanoTime();
        CohortCollectionResult collection = CohortCollector.collect(
                membership, spec, source, access, budget, MemberQualityGate.allowAll());
        FleetBenchmarkResult result =
                FleetDistributionEngine.distribute(collection, direction, topN, focusAssetId);
        long wallMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        AnalysisBudgetAccounting accounting = AnalysisBudgetAccounting.builder()
                .requested(budget)
                .effective(budget)
                .consumedRows(collection.publishedCoverage().returnedN())
                .consumedBytes(0L)
                .consumedWallTimeMillis(wallMs)
                .clamped(false)
                .build();
        AnalysisEnvelope envelope =
                U6FleetEnvelopeFactory.fromBenchmark(result, profileDigest, accounting);
        return new Outcome(result, envelope);
    }
}
