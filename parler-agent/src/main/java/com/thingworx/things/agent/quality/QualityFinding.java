package com.thingworx.things.agent.quality;

import java.util.Objects;

/**
 * One versioned quality finding. Cadence-dependent metrics may be {@link #unknown()} when expected
 * cadence is absent — never invent coverage.
 */
public final class QualityFinding {

    private final QualityMetricId metric;
    private final QualitySeverity severity;
    private final String threshold;
    private final String observed;
    private final long support;
    private final boolean unknown;
    private final String detail;

    private QualityFinding(Builder b) {
        this.metric = Objects.requireNonNull(b.metric, "metric");
        this.severity = Objects.requireNonNull(b.severity, "severity");
        this.threshold = blankToNull(b.threshold);
        this.observed = blankToNull(b.observed);
        this.support = Math.max(0L, b.support);
        this.unknown = b.unknown;
        this.detail = blankToNull(b.detail);
    }

    public static Builder builder() {
        return new Builder();
    }

    public QualityMetricId metric() {
        return metric;
    }

    public QualitySeverity severity() {
        return severity;
    }

    public String threshold() {
        return threshold;
    }

    public String observed() {
        return observed;
    }

    public long support() {
        return support;
    }

    public boolean unknown() {
        return unknown;
    }

    public String detail() {
        return detail;
    }

    /** Stable evidence token for envelope attachment. */
    public String evidenceToken() {
        return metric.name().toLowerCase();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public static final class Builder {
        private QualityMetricId metric;
        private QualitySeverity severity = QualitySeverity.INFO;
        private String threshold;
        private String observed;
        private long support;
        private boolean unknown;
        private String detail;

        public Builder metric(QualityMetricId v) {
            this.metric = v;
            return this;
        }

        public Builder severity(QualitySeverity v) {
            this.severity = v;
            return this;
        }

        public Builder threshold(String v) {
            this.threshold = v;
            return this;
        }

        public Builder observed(String v) {
            this.observed = v;
            return this;
        }

        public Builder support(long v) {
            this.support = v;
            return this;
        }

        public Builder unknown(boolean v) {
            this.unknown = v;
            return this;
        }

        public Builder detail(String v) {
            this.detail = v;
            return this;
        }

        public QualityFinding build() {
            return new QualityFinding(this);
        }
    }
}
