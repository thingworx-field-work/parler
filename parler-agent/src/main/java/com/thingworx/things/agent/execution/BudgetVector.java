package com.thingworx.things.agent.execution;

import com.thingworx.things.agent.cache.ArtifactIoLimits;

/**
 * U2 shared operation budgets. Decoder/projection dimensions stay in U2; only the storage-I/O
 * slice maps into {@link ArtifactIoLimits}.
 */
public final class BudgetVector {

    private final long maxDecodeChars;
    private final long maxDecodeBytes;
    private final long maxReturnedRows;
    private final long maxProjectionNodes;
    private final int maxNestingDepth;
    private final long maxWallTimeMillis;
    private final long storageMaxBytes;
    private final long storageMaxItems;
    private final int storageMaxInternalBufferBytes;

    private BudgetVector(Builder b) {
        this.maxDecodeChars = b.maxDecodeChars;
        this.maxDecodeBytes = b.maxDecodeBytes;
        this.maxReturnedRows = b.maxReturnedRows;
        this.maxProjectionNodes = b.maxProjectionNodes;
        this.maxNestingDepth = b.maxNestingDepth;
        this.maxWallTimeMillis = b.maxWallTimeMillis;
        this.storageMaxBytes = b.storageMaxBytes;
        this.storageMaxItems = b.storageMaxItems;
        this.storageMaxInternalBufferBytes = b.storageMaxInternalBufferBytes;
    }

    /** Default budgets for current tabular adapters (M1). */
    public static BudgetVector defaultsForTabular() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public long maxDecodeChars() {
        return maxDecodeChars;
    }

    public long maxDecodeBytes() {
        return maxDecodeBytes;
    }

    public long maxReturnedRows() {
        return maxReturnedRows;
    }

    public long maxProjectionNodes() {
        return maxProjectionNodes;
    }

    public int maxNestingDepth() {
        return maxNestingDepth;
    }

    public long maxWallTimeMillis() {
        return maxWallTimeMillis;
    }

    /** Map only the storage-I/O slice into U1A limits. */
    public ArtifactIoLimits toArtifactIoLimits() {
        return ArtifactIoLimits.of(storageMaxBytes, storageMaxItems, maxWallTimeMillis,
                storageMaxInternalBufferBytes);
    }

    public static final class Builder {
        private long maxDecodeChars = 2_000_000L;
        private long maxDecodeBytes = 4_000_000L;
        private long maxReturnedRows = 5000L;
        private long maxProjectionNodes = 50_000L;
        private int maxNestingDepth = 32;
        private long maxWallTimeMillis = 60_000L;
        private long storageMaxBytes = 32_000_000L;
        /** Above tabulate {@code MAX_SCANNED_ROWS_DECISION} so store can accept oversize sources that tabulate rejects. */
        private long storageMaxItems = 200_000L;
        private int storageMaxInternalBufferBytes = ArtifactIoLimits.MAX_U1A_INTERNAL_BUFFER_BYTES;

        public Builder maxDecodeChars(long v) {
            this.maxDecodeChars = v;
            return this;
        }

        public Builder maxDecodeBytes(long v) {
            this.maxDecodeBytes = v;
            return this;
        }

        public Builder maxReturnedRows(long v) {
            this.maxReturnedRows = v;
            return this;
        }

        public Builder maxProjectionNodes(long v) {
            this.maxProjectionNodes = v;
            return this;
        }

        public Builder maxNestingDepth(int v) {
            this.maxNestingDepth = v;
            return this;
        }

        public Builder maxWallTimeMillis(long v) {
            this.maxWallTimeMillis = v;
            return this;
        }

        public Builder storageMaxBytes(long v) {
            this.storageMaxBytes = v;
            return this;
        }

        public Builder storageMaxItems(long v) {
            this.storageMaxItems = v;
            return this;
        }

        public Builder storageMaxInternalBufferBytes(int v) {
            this.storageMaxInternalBufferBytes = v;
            return this;
        }

        public BudgetVector build() {
            return new BudgetVector(this);
        }
    }
}
