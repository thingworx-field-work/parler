package com.thingworx.things.agent.fleet;

import java.util.Objects;

/** One comparable finite member metric for rank/percentile/MAD (FRC-0 ranking freeze). */
public final class ComparableMemberMetric {

    private final String semanticAssetId;
    private final double metricValue;

    public ComparableMemberMetric(String semanticAssetId, double metricValue) {
        if (semanticAssetId == null || semanticAssetId.isBlank()) {
            throw new IllegalArgumentException("semanticAssetId required");
        }
        if (!Double.isFinite(metricValue)) {
            throw new IllegalArgumentException("metricValue must be finite");
        }
        this.semanticAssetId = semanticAssetId.trim();
        this.metricValue = metricValue;
    }

    public String semanticAssetId() {
        return semanticAssetId;
    }

    public double metricValue() {
        return metricValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ComparableMemberMetric)) {
            return false;
        }
        ComparableMemberMetric other = (ComparableMemberMetric) o;
        return Double.compare(metricValue, other.metricValue) == 0
                && semanticAssetId.equals(other.semanticAssetId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(semanticAssetId, metricValue);
    }
}
