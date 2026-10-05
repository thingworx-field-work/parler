package com.thingworx.things.agent.fleet;

import java.util.Objects;

import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.cache.ArtifactAccessContext;
import com.thingworx.things.agent.execution.BudgetVector;

/**
 * Internal G5 request shape (FRC-0). Public model-facing args use profile references only — never
 * member rows. Execution remains FRC-1+.
 */
public final class FleetBenchmarkRequest {

    private final String peerProfileId;
    private final String metricProfileId;
    private final String focusAssetId;
    private final HalfOpenWindow window;
    private final RankingDirection direction;
    private final int topN;
    private final ArtifactAccessContext access;
    private final BudgetVector budget;

    private FleetBenchmarkRequest(Builder b) {
        this.peerProfileId = requireNonBlank(b.peerProfileId, "peerProfileId");
        this.metricProfileId = requireNonBlank(b.metricProfileId, "metricProfileId");
        this.focusAssetId = blankToNull(b.focusAssetId);
        this.window = Objects.requireNonNull(b.window, "window");
        this.direction = Objects.requireNonNull(b.direction, "direction");
        if (b.topN <= 0) {
            throw new IllegalArgumentException("topN must be > 0");
        }
        this.topN = b.topN;
        this.access = Objects.requireNonNull(b.access, "access");
        this.budget = Objects.requireNonNull(b.budget, "budget");
    }

    public static Builder builder() {
        return new Builder();
    }

    public String peerProfileId() {
        return peerProfileId;
    }

    public String metricProfileId() {
        return metricProfileId;
    }

    public String focusAssetId() {
        return focusAssetId;
    }

    public HalfOpenWindow window() {
        return window;
    }

    public RankingDirection direction() {
        return direction;
    }

    public int topN() {
        return topN;
    }

    public ArtifactAccessContext access() {
        return access;
    }

    public BudgetVector budget() {
        return budget;
    }

    private static String requireNonBlank(String s, String name) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return s.trim();
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    public static final class Builder {
        private String peerProfileId;
        private String metricProfileId;
        private String focusAssetId;
        private HalfOpenWindow window;
        private RankingDirection direction = RankingDirection.HIGHER_IS_BETTER;
        private int topN = 10;
        private ArtifactAccessContext access;
        private BudgetVector budget = BudgetVector.defaultsForTabular();

        public Builder peerProfileId(String v) {
            this.peerProfileId = v;
            return this;
        }

        public Builder metricProfileId(String v) {
            this.metricProfileId = v;
            return this;
        }

        public Builder focusAssetId(String v) {
            this.focusAssetId = v;
            return this;
        }

        public Builder window(HalfOpenWindow v) {
            this.window = v;
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

        public Builder access(ArtifactAccessContext v) {
            this.access = v;
            return this;
        }

        public Builder budget(BudgetVector v) {
            this.budget = v;
            return this;
        }

        public FleetBenchmarkRequest build() {
            return new FleetBenchmarkRequest(this);
        }
    }
}
