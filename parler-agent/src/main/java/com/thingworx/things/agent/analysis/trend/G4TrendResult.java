package com.thingworx.things.agent.analysis.trend;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.thingworx.things.agent.evidence.EvidenceStatus;

/**
 * Quantification result shell for G4 {@code trend} (§6.3.1). Valid slope/fit — including near-zero
 * / flat — is {@link EvidenceStatus#SUCCESS}. Never {@link EvidenceStatus#NO_FINDING}.
 */
public final class G4TrendResult {

    private final EvidenceStatus status;
    private final String outcomeCode;
    private final String methodId;
    private final long supportN;
    private final Double slope;
    private final Double intercept;
    private final Map<String, String> metrics;

    private G4TrendResult(Builder b) {
        this.status = Objects.requireNonNull(b.status, "status");
        this.outcomeCode = Objects.requireNonNull(b.outcomeCode, "outcomeCode").trim();
        this.methodId = Objects.requireNonNull(b.methodId, "methodId").trim();
        this.supportN = b.supportN;
        if (supportN < 0L) {
            throw new IllegalArgumentException("supportN must be non-negative");
        }
        this.slope = b.slope;
        this.intercept = b.intercept;
        this.metrics = copyMetrics(b.metrics);
        if (status == EvidenceStatus.NO_FINDING) {
            throw new IllegalArgumentException("trend quantification must not use NO_FINDING");
        }
        if (status == EvidenceStatus.SUCCESS && supportN < 2L) {
            throw new IllegalArgumentException("SUCCESS trend requires supportN >= 2");
        }
        if (status == EvidenceStatus.SUCCESS && (slope == null || intercept == null
                || !Double.isFinite(slope) || !Double.isFinite(intercept))) {
            throw new IllegalArgumentException("SUCCESS trend requires finite slope/intercept");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public EvidenceStatus status() {
        return status;
    }

    public String outcomeCode() {
        return outcomeCode;
    }

    public String methodId() {
        return methodId;
    }

    public long supportN() {
        return supportN;
    }

    public Double slope() {
        return slope;
    }

    public Double intercept() {
        return intercept;
    }

    public Map<String, String> metrics() {
        return metrics;
    }

    private static Map<String, String> copyMetrics(Map<String, String> in) {
        if (in == null || in.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : in.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) {
                continue;
            }
            out.put(e.getKey().trim(), e.getValue() == null ? "" : e.getValue());
        }
        return Collections.unmodifiableMap(out);
    }

    public static final class Builder {
        private EvidenceStatus status;
        private String outcomeCode;
        private String methodId;
        private long supportN;
        private Double slope;
        private Double intercept;
        private Map<String, String> metrics = Map.of();

        public Builder status(EvidenceStatus v) {
            this.status = v;
            return this;
        }

        public Builder outcomeCode(String v) {
            this.outcomeCode = v;
            return this;
        }

        public Builder methodId(String v) {
            this.methodId = v;
            return this;
        }

        public Builder supportN(long v) {
            this.supportN = v;
            return this;
        }

        public Builder slope(Double v) {
            this.slope = v;
            return this;
        }

        public Builder intercept(Double v) {
            this.intercept = v;
            return this;
        }

        public Builder metrics(Map<String, String> v) {
            this.metrics = v;
            return this;
        }

        public G4TrendResult build() {
            return new G4TrendResult(this);
        }
    }
}
