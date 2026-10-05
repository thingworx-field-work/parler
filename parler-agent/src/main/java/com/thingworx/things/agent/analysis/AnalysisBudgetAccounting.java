package com.thingworx.things.agent.analysis;

import java.util.Objects;

import com.thingworx.things.agent.execution.BudgetVector;

/**
 * Requested / effective / consumed budget facts on the base envelope. Dimensions reuse
 * {@link BudgetVector}; U4 does not redefine them.
 */
public final class AnalysisBudgetAccounting {

    private final BudgetVector requested;
    private final BudgetVector effective;
    private final long consumedRows;
    private final long consumedBytes;
    private final long consumedWallTimeMillis;
    private final boolean clamped;

    private AnalysisBudgetAccounting(Builder b) {
        this.requested = Objects.requireNonNull(b.requested, "requested");
        this.effective = Objects.requireNonNull(b.effective, "effective");
        this.consumedRows = requireNonNegative(b.consumedRows, "consumedRows");
        this.consumedBytes = requireNonNegative(b.consumedBytes, "consumedBytes");
        this.consumedWallTimeMillis =
                requireNonNegative(b.consumedWallTimeMillis, "consumedWallTimeMillis");
        this.clamped = b.clamped;
    }

    private static long requireNonNegative(long v, String name) {
        if (v < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return v;
    }

    public static Builder builder() {
        return new Builder();
    }

    public BudgetVector requested() {
        return requested;
    }

    public BudgetVector effective() {
        return effective;
    }

    public long consumedRows() {
        return consumedRows;
    }

    public long consumedBytes() {
        return consumedBytes;
    }

    public long consumedWallTimeMillis() {
        return consumedWallTimeMillis;
    }

    public boolean clamped() {
        return clamped;
    }

    public static final class Builder {
        private BudgetVector requested = BudgetVector.defaultsForTabular();
        private BudgetVector effective = BudgetVector.defaultsForTabular();
        private long consumedRows;
        private long consumedBytes;
        private long consumedWallTimeMillis;
        private boolean clamped;

        public Builder requested(BudgetVector v) {
            this.requested = v;
            return this;
        }

        public Builder effective(BudgetVector v) {
            this.effective = v;
            return this;
        }

        public Builder consumedRows(long v) {
            this.consumedRows = v;
            return this;
        }

        public Builder consumedBytes(long v) {
            this.consumedBytes = v;
            return this;
        }

        public Builder consumedWallTimeMillis(long v) {
            this.consumedWallTimeMillis = v;
            return this;
        }

        public Builder clamped(boolean v) {
            this.clamped = v;
            return this;
        }

        public AnalysisBudgetAccounting build() {
            return new AnalysisBudgetAccounting(this);
        }
    }
}
