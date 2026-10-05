package com.thingworx.things.agent.fleet;

import java.util.Objects;

/**
 * One typed row from a conforming U6 cohort batch source (FRC-0 contract).
 * {@link CohortMemberStatus#PERMISSION_LIMITED} rows MUST omit identity fields that would reveal
 * unauthorized membership.
 */
public final class CohortBatchMemberRow {

    private final String semanticAssetId;
    private final CohortMemberStatus status;
    private final Double metricValue;
    private final String unit;
    private final String grain;
    private final String methodId;
    private final String reasonCode;

    private CohortBatchMemberRow(Builder b) {
        this.status = Objects.requireNonNull(b.status, "status");
        if (status == CohortMemberStatus.PERMISSION_LIMITED) {
            this.semanticAssetId = null;
            this.metricValue = null;
            this.unit = null;
            this.grain = null;
            this.methodId = null;
            this.reasonCode = blankToNull(b.reasonCode);
            return;
        }
        this.semanticAssetId = requireNonBlank(b.semanticAssetId, "semanticAssetId");
        this.metricValue = b.metricValue;
        this.unit = blankToNull(b.unit);
        this.grain = blankToNull(b.grain);
        this.methodId = blankToNull(b.methodId);
        this.reasonCode = blankToNull(b.reasonCode);
        if (status == CohortMemberStatus.ELIGIBLE_VALUE) {
            if (metricValue == null || !Double.isFinite(metricValue)) {
                throw new IllegalArgumentException("ELIGIBLE_VALUE requires a finite metricValue");
            }
        } else if (metricValue != null) {
            throw new IllegalArgumentException(status + " must not carry metricValue");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public String semanticAssetId() {
        return semanticAssetId;
    }

    public CohortMemberStatus status() {
        return status;
    }

    public Double metricValue() {
        return metricValue;
    }

    public String unit() {
        return unit;
    }

    public String grain() {
        return grain;
    }

    public String methodId() {
        return methodId;
    }

    public String reasonCode() {
        return reasonCode;
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
        private String semanticAssetId;
        private CohortMemberStatus status;
        private Double metricValue;
        private String unit;
        private String grain;
        private String methodId;
        private String reasonCode;

        public Builder semanticAssetId(String v) {
            this.semanticAssetId = v;
            return this;
        }

        public Builder status(CohortMemberStatus v) {
            this.status = v;
            return this;
        }

        public Builder metricValue(Double v) {
            this.metricValue = v;
            return this;
        }

        public Builder unit(String v) {
            this.unit = v;
            return this;
        }

        public Builder grain(String v) {
            this.grain = v;
            return this;
        }

        public Builder methodId(String v) {
            this.methodId = v;
            return this;
        }

        public Builder reasonCode(String v) {
            this.reasonCode = v;
            return this;
        }

        public CohortBatchMemberRow build() {
            return new CohortBatchMemberRow(this);
        }
    }
}
