package com.thingworx.things.agent.fleet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

/**
 * FRC-1 collection + gate result: frozen membership, post-gate outcomes, published coverage,
 * aggregate source completeness, and per-member evidence rows (distribution/rank remain FRC-2).
 */
public final class CohortCollectionResult {

    private final FrozenCohortMembership membership;
    private final List<GatedMemberOutcome> outcomes;
    private final CohortCoverageCounts publishedCoverage;
    private final List<FleetMemberEvidence> memberEvidence;
    private final CompletenessStatus sourceCompleteness;

    public CohortCollectionResult(
            FrozenCohortMembership membership,
            List<GatedMemberOutcome> outcomes,
            CohortCoverageCounts publishedCoverage,
            List<FleetMemberEvidence> memberEvidence,
            CompletenessStatus sourceCompleteness) {
        this.membership = Objects.requireNonNull(membership, "membership");
        this.outcomes = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(outcomes, "outcomes")));
        this.publishedCoverage = Objects.requireNonNull(publishedCoverage, "publishedCoverage");
        this.memberEvidence = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(memberEvidence, "memberEvidence")));
        this.sourceCompleteness =
                sourceCompleteness == null ? CompletenessStatus.COMPLETE : sourceCompleteness;
    }

    public FrozenCohortMembership membership() {
        return membership;
    }

    public List<GatedMemberOutcome> outcomes() {
        return outcomes;
    }

    public CohortCoverageCounts publishedCoverage() {
        return publishedCoverage;
    }

    public List<FleetMemberEvidence> memberEvidence() {
        return memberEvidence;
    }

    public CompletenessStatus sourceCompleteness() {
        return sourceCompleteness;
    }

    /** True when the batch source reported incomplete paging/coverage ({@code BATCH_SOURCE_PARTIAL}). */
    public boolean batchSourcePartial() {
        return sourceCompleteness != CompletenessStatus.COMPLETE;
    }

    /** Comparable members admitted to the numeric distribution (FRC-2 input). */
    public List<ComparableMemberMetric> comparableMetrics() {
        List<ComparableMemberMetric> out = new ArrayList<>();
        for (GatedMemberOutcome o : outcomes) {
            if (o.comparable()) {
                out.add(new ComparableMemberMetric(o.semanticAssetId(), o.metricValue()));
            }
        }
        return List.copyOf(out);
    }
}
