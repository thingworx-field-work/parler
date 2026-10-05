package com.thingworx.things.agent.cache;

/**
 * Bounds for one ArtifactCache create/open storage operation: bytes, items, wall time, and fixed
 * internal buffer/window ceiling. U2 may map an invocation budget into these fields.
 */
public final class ArtifactIoLimits {

    /** Core fixed ceiling for {@link #maxInternalBufferBytes()}. */
    public static final int MAX_U1A_INTERNAL_BUFFER_BYTES = 65536;

    private final long maxBytes;
    private final long maxItems;
    private final long maxWallTimeMillis;
    private final int maxInternalBufferBytes;

    private ArtifactIoLimits(long maxBytes, long maxItems, long maxWallTimeMillis,
            int maxInternalBufferBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        if (maxItems <= 0) {
            throw new IllegalArgumentException("maxItems must be positive");
        }
        if (maxWallTimeMillis <= 0) {
            throw new IllegalArgumentException("maxWallTimeMillis must be positive");
        }
        if (maxInternalBufferBytes < 1 || maxInternalBufferBytes > MAX_U1A_INTERNAL_BUFFER_BYTES) {
            throw new IllegalArgumentException(
                    "maxInternalBufferBytes must be in 1.." + MAX_U1A_INTERNAL_BUFFER_BYTES);
        }
        this.maxBytes = maxBytes;
        this.maxItems = maxItems;
        this.maxWallTimeMillis = maxWallTimeMillis;
        this.maxInternalBufferBytes = maxInternalBufferBytes;
    }

    public static ArtifactIoLimits of(long maxBytes, long maxItems, long maxWallTimeMillis,
            int maxInternalBufferBytes) {
        return new ArtifactIoLimits(maxBytes, maxItems, maxWallTimeMillis, maxInternalBufferBytes);
    }

    public long maxBytes() {
        return maxBytes;
    }

    public long maxItems() {
        return maxItems;
    }

    public long maxWallTimeMillis() {
        return maxWallTimeMillis;
    }

    public int maxInternalBufferBytes() {
        return maxInternalBufferBytes;
    }

    public boolean exceedsBytes(long bytes) {
        return bytes > maxBytes;
    }

    public boolean exceedsItems(long items) {
        return items > maxItems;
    }

    public boolean exceedsWallTime(long elapsedMillis) {
        return elapsedMillis > maxWallTimeMillis;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ArtifactIoLimits)) {
            return false;
        }
        ArtifactIoLimits that = (ArtifactIoLimits) o;
        return maxBytes == that.maxBytes
                && maxItems == that.maxItems
                && maxWallTimeMillis == that.maxWallTimeMillis
                && maxInternalBufferBytes == that.maxInternalBufferBytes;
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(maxBytes);
        result = 31 * result + Long.hashCode(maxItems);
        result = 31 * result + Long.hashCode(maxWallTimeMillis);
        result = 31 * result + maxInternalBufferBytes;
        return result;
    }

    @Override
    public String toString() {
        return "ArtifactIoLimits{maxBytes=" + maxBytes
                + ", maxItems=" + maxItems
                + ", maxWallTimeMillis=" + maxWallTimeMillis
                + ", maxInternalBufferBytes=" + maxInternalBufferBytes
                + '}';
    }
}
