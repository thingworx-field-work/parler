package com.thingworx.things.agent.fleet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.evidence.EvidenceStatus;

/**
 * FRC-2: compute distribution, competition rank / percentile / MAD, focus-preserving top-N, and
 * compact position evidence from a gated {@link CohortCollectionResult}.
 */
public final class FleetDistributionEngine {

    private FleetDistributionEngine() {}

    public static FleetBenchmarkResult distribute(
            CohortCollectionResult collection,
            RankingDirection direction,
            int topN,
            String focusAssetId) {
        Objects.requireNonNull(collection, "collection");
        Objects.requireNonNull(direction, "direction");
        if (topN <= 0) {
            throw new IllegalArgumentException("topN must be > 0");
        }
        String focus = blankToNull(focusAssetId);
        List<ComparableMemberMetric> comparable = collection.comparableMetrics();
        List<String> reasons = new ArrayList<>();

        if (collection.publishedCoverage().permissionLimited() || collection.batchSourcePartial()) {
            reasons.add(FleetOutcomeCodes.COHORT_PARTIAL);
        }

        // FOCUS_NOT_IN_COHORT is reserved for focus outside the frozen authorized set (or
        // unknowable without naming unauthorized peers). Authorized-but-non-comparable focus
        // (NO_DATA / INCOMPARABLE / INSUFFICIENT_EVIDENCE) must not be mislabeled — that status
        // already lives on gated outcomes / coverage (fleet-rca §8 evidence distinctions).
        if (focus != null && !collection.membership().contains(focus)) {
            reasons.add(FleetOutcomeCodes.FOCUS_NOT_IN_COHORT);
        }

        if (comparable.isEmpty()) {
            reasons.add(FleetOutcomeCodes.NO_COMPARABLE_MEMBERS);
            return FleetBenchmarkResult.builder()
                    .collection(collection)
                    .direction(direction)
                    .topN(topN)
                    .focusAssetId(focus)
                    .topWithFocus(List.of())
                    .focusGatedStatus(focusGatedStatus(collection, focus))
                    .reasonCodes(reasons)
                    .status(EvidenceStatus.NO_FINDING)
                    .build();
        }

        FleetDistributionStats stats = FleetDistributionStats.of(comparable);
        List<MemberPosition> top = CompetitionRank.topNWithFocus(comparable, direction, topN, focus);
        MemberPosition focusPosition = findFocus(top, focus);

        return FleetBenchmarkResult.builder()
                .collection(collection)
                .direction(direction)
                .topN(topN)
                .focusAssetId(focus)
                .distribution(stats)
                .topWithFocus(top)
                .focusPosition(focusPosition)
                .focusGatedStatus(focusGatedStatus(collection, focus))
                .reasonCodes(reasons)
                .status(EvidenceStatus.SUCCESS)
                .build();
    }

    /**
     * Gated status of an authorized focus member when it did not enter the comparable set.
     * {@code null} when focus is absent, not authorized, or comparable (has a position).
     */
    static CohortMemberStatus focusGatedStatus(CohortCollectionResult collection, String focus) {
        if (focus == null || !collection.membership().contains(focus)) {
            return null;
        }
        for (GatedMemberOutcome o : collection.outcomes()) {
            if (focus.equals(o.semanticAssetId())) {
                if (o.status() == CohortMemberStatus.ELIGIBLE_VALUE) {
                    return null;
                }
                return o.status();
            }
        }
        return null;
    }

    private static MemberPosition findFocus(List<MemberPosition> positions, String focus) {
        if (focus == null) {
            return null;
        }
        for (MemberPosition p : positions) {
            if (focus.equals(p.semanticAssetId())) {
                return p;
            }
        }
        return null;
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
