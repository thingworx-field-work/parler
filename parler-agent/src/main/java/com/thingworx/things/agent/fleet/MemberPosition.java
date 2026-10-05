package com.thingworx.things.agent.fleet;

import java.util.Objects;

/** Rank/percentile/robust-z facts for one comparable member (FRC-0). */
public final class MemberPosition {

    private final String semanticAssetId;
    private final double metricValue;
    private final int competitionRank;
    private final double statisticalPercentile;
    private final Double robustZ;
    private final boolean focusOutsideTopN;

    public MemberPosition(
            String semanticAssetId,
            double metricValue,
            int competitionRank,
            double statisticalPercentile,
            Double robustZ,
            boolean focusOutsideTopN) {
        this.semanticAssetId = Objects.requireNonNull(semanticAssetId, "semanticAssetId");
        this.metricValue = metricValue;
        this.competitionRank = competitionRank;
        this.statisticalPercentile = statisticalPercentile;
        this.robustZ = robustZ;
        this.focusOutsideTopN = focusOutsideTopN;
    }

    public String semanticAssetId() {
        return semanticAssetId;
    }

    public double metricValue() {
        return metricValue;
    }

    public int competitionRank() {
        return competitionRank;
    }

    public double statisticalPercentile() {
        return statisticalPercentile;
    }

    /** {@code null} when MAD==0 (undefined robust score). */
    public Double robustZ() {
        return robustZ;
    }

    public boolean focusOutsideTopN() {
        return focusOutsideTopN;
    }
}
