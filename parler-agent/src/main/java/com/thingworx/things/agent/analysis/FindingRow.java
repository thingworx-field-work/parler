package com.thingworx.things.agent.analysis;

import java.time.Instant;
import java.util.Objects;

/**
 * Compact finding row for U5 detection/quantification artifacts (DIK-1). Does not duplicate source
 * columns; protected fields never enter unless an egress contract later allows them.
 */
public final class FindingRow {

    private final long sourceOrdinal;
    private final Instant timestamp;
    private final Double score;
    private final Double effect;
    private final Double limit;
    private final String segmentOrPair;
    private final String methodId;
    private final String outcomeCode;

    private FindingRow(Builder b) {
        if (b.sourceOrdinal < 0L) {
            throw new IllegalArgumentException("sourceOrdinal must be non-negative");
        }
        this.sourceOrdinal = b.sourceOrdinal;
        this.timestamp = b.timestamp;
        this.score = b.score;
        this.effect = b.effect;
        this.limit = b.limit;
        this.segmentOrPair = blankToNull(b.segmentOrPair);
        this.methodId = requireNonBlank(b.methodId, "methodId");
        this.outcomeCode = blankToNull(b.outcomeCode);
    }

    public static Builder builder() {
        return new Builder();
    }

    public long sourceOrdinal() {
        return sourceOrdinal;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public Double score() {
        return score;
    }

    public Double effect() {
        return effect;
    }

    public Double limit() {
        return limit;
    }

    public String segmentOrPair() {
        return segmentOrPair;
    }

    public String methodId() {
        return methodId;
    }

    public String outcomeCode() {
        return outcomeCode;
    }

    private static String requireNonBlank(String s, String name) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return s.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public static final class Builder {
        private long sourceOrdinal;
        private Instant timestamp;
        private Double score;
        private Double effect;
        private Double limit;
        private String segmentOrPair;
        private String methodId;
        private String outcomeCode;

        public Builder sourceOrdinal(long v) {
            this.sourceOrdinal = v;
            return this;
        }

        public Builder timestamp(Instant v) {
            this.timestamp = v;
            return this;
        }

        public Builder score(Double v) {
            this.score = v;
            return this;
        }

        public Builder effect(Double v) {
            this.effect = v;
            return this;
        }

        public Builder limit(Double v) {
            this.limit = v;
            return this;
        }

        public Builder segmentOrPair(String v) {
            this.segmentOrPair = v;
            return this;
        }

        public Builder methodId(String v) {
            this.methodId = v;
            return this;
        }

        public Builder outcomeCode(String v) {
            this.outcomeCode = v;
            return this;
        }

        public FindingRow build() {
            Objects.requireNonNull(methodId, "methodId");
            return new FindingRow(this);
        }
    }
}
