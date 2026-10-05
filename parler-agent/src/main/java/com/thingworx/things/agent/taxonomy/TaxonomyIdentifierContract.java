package com.thingworx.things.agent.taxonomy;

/** Pure contract helpers for identifier truncation (no platform / logging dependencies). */
public final class TaxonomyIdentifierContract {

    public static final int QIT_MAX_ITEMS = 5000;

    private TaxonomyIdentifierContract() {}

    public static boolean contractTruncationFromTotals(Long platformTotal, Long inferredTotal) {
        if (platformTotal != null) {
            return platformTotal > QIT_MAX_ITEMS;
        }
        return inferredTotal != null && inferredTotal > QIT_MAX_ITEMS;
    }

    public static Integer contractTotalUnderlying(Long platformTotal, Long inferredTotal) {
        Long t = platformTotal != null ? platformTotal : inferredTotal;
        if (t == null || t <= QIT_MAX_ITEMS) {
            return null;
        }
        return t > Integer.MAX_VALUE ? Integer.MAX_VALUE : t.intValue();
    }

    /** Contract-defined truncation for QIT scan (platform total or probe-inferred total only). */
    public static ScanTruncation scanTruncation(Long platformTotal, Long inferredTotal) {
        if (!contractTruncationFromTotals(platformTotal, inferredTotal)) {
            return new ScanTruncation(false, null);
        }
        return new ScanTruncation(true, contractTotalUnderlying(platformTotal, inferredTotal));
    }

    public static final class ScanTruncation {
        public final boolean truncated;
        public final Integer totalUnderlyingCount;

        public ScanTruncation(boolean truncated, Integer totalUnderlyingCount) {
            this.truncated = truncated;
            this.totalUnderlyingCount = totalUnderlyingCount;
        }
    }
}
