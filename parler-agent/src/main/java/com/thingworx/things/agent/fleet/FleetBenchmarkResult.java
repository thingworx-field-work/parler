package com.thingworx.things.agent.fleet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

/**
 * FRC-2 distribution result over a gated {@link CohortCollectionResult}: stats, top-N + focus
 * positions, compact evidence rows, and topic reason codes. Does not advertise tools.
 */
public final class FleetBenchmarkResult {

    private final CohortCollectionResult collection;
    private final RankingDirection direction;
    private final int topN;
    private final String focusAssetId;
    private final FleetDistributionStats distribution;
    private final List<MemberPosition> topWithFocus;
    private final MemberPosition focusPosition;
    private final CohortMemberStatus focusGatedStatus;
    private final List<String> reasonCodes;
    private final EvidenceStatus status;

    private FleetBenchmarkResult(Builder b) {
        this.collection = Objects.requireNonNull(b.collection, "collection");
        this.direction = Objects.requireNonNull(b.direction, "direction");
        this.topN = b.topN;
        this.focusAssetId = b.focusAssetId;
        this.distribution = b.distribution;
        this.topWithFocus = Collections.unmodifiableList(new ArrayList<>(b.topWithFocus));
        this.focusPosition = b.focusPosition;
        this.focusGatedStatus = b.focusGatedStatus;
        this.reasonCodes = Collections.unmodifiableList(new ArrayList<>(b.reasonCodes));
        this.status = Objects.requireNonNull(b.status, "status");
    }

    public static Builder builder() {
        return new Builder();
    }

    public CohortCollectionResult collection() {
        return collection;
    }

    public RankingDirection direction() {
        return direction;
    }

    public int topN() {
        return topN;
    }

    public String focusAssetId() {
        return focusAssetId;
    }

    /** {@code null} when there are no comparable members. */
    public FleetDistributionStats distribution() {
        return distribution;
    }

    public List<MemberPosition> topWithFocus() {
        return topWithFocus;
    }

    /** Focus member position when focus is among comparable members; otherwise {@code null}. */
    public MemberPosition focusPosition() {
        return focusPosition;
    }

    /**
     * Gated status of an authorized focus that did not enter the comparable set ({@code NO_DATA},
     * {@code INCOMPARABLE}, …). {@code null} when focus is comparable, absent, or not authorized.
     */
    public CohortMemberStatus focusGatedStatus() {
        return focusGatedStatus;
    }

    public List<String> reasonCodes() {
        return reasonCodes;
    }

    public EvidenceStatus status() {
        return status;
    }

    public CohortCoverageCounts publishedCoverage() {
        return collection.publishedCoverage();
    }

    public CompletenessStatus sourceCompleteness() {
        return collection.sourceCompleteness();
    }

    public boolean batchSourcePartial() {
        return collection.batchSourcePartial();
    }

    public boolean focusOutsideTopN() {
        return focusPosition != null && focusPosition.focusOutsideTopN();
    }

    public boolean zeroDispersion() {
        return distribution != null && distribution.zeroDispersion();
    }

    public List<com.thingworx.things.agent.cache.TypedRow> compactPositionRows() {
        return FleetPositionEvidence.toTypedRows(topWithFocus);
    }

    public static final class Builder {
        private CohortCollectionResult collection;
        private RankingDirection direction = RankingDirection.HIGHER_IS_BETTER;
        private int topN = 10;
        private String focusAssetId;
        private FleetDistributionStats distribution;
        private List<MemberPosition> topWithFocus = List.of();
        private MemberPosition focusPosition;
        private CohortMemberStatus focusGatedStatus;
        private List<String> reasonCodes = List.of();
        private EvidenceStatus status = EvidenceStatus.SUCCESS;

        public Builder collection(CohortCollectionResult v) {
            this.collection = v;
            return this;
        }

        public Builder direction(RankingDirection v) {
            this.direction = v;
            return this;
        }

        public Builder topN(int v) {
            this.topN = v;
            return this;
        }

        public Builder focusAssetId(String v) {
            this.focusAssetId = v;
            return this;
        }

        public Builder distribution(FleetDistributionStats v) {
            this.distribution = v;
            return this;
        }

        public Builder topWithFocus(List<MemberPosition> v) {
            this.topWithFocus = v == null ? List.of() : v;
            return this;
        }

        public Builder focusPosition(MemberPosition v) {
            this.focusPosition = v;
            return this;
        }

        public Builder focusGatedStatus(CohortMemberStatus v) {
            this.focusGatedStatus = v;
            return this;
        }

        public Builder reasonCodes(List<String> v) {
            this.reasonCodes = v == null ? List.of() : v;
            return this;
        }

        public Builder status(EvidenceStatus v) {
            this.status = v;
            return this;
        }

        public FleetBenchmarkResult build() {
            if (topN <= 0) {
                throw new IllegalArgumentException("topN must be > 0");
            }
            return new FleetBenchmarkResult(this);
        }
    }
}
