package com.thingworx.things.agent.analysis.relationship;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.thingworx.things.agent.analysis.FindingRow;
import com.thingworx.things.agent.evidence.EvidenceStatus;

/**
 * Shared result shell for G2 quantification methods (DIK-3). Status follows §6.3.1: valid metrics
 * (including near-zero association) are {@link EvidenceStatus#SUCCESS}; gates that block the metric
 * are {@link EvidenceStatus#INSUFFICIENT_EVIDENCE}. {@link EvidenceStatus#NO_FINDING} is forbidden.
 */
public final class G2RelationshipResult {

    private final EvidenceStatus status;
    private final String outcomeCode;
    private final String methodId;
    private final long supportN;
    private final List<FindingRow> findings;
    private final Map<String, String> metrics;

    private G2RelationshipResult(Builder b) {
        this.status = Objects.requireNonNull(b.status, "status");
        this.outcomeCode = Objects.requireNonNull(b.outcomeCode, "outcomeCode").trim();
        this.methodId = Objects.requireNonNull(b.methodId, "methodId").trim();
        this.supportN = b.supportN;
        if (supportN < 0L) {
            throw new IllegalArgumentException("supportN must be non-negative");
        }
        this.findings = List.copyOf(b.findings == null ? List.of() : b.findings);
        this.metrics = copyMetrics(b.metrics);
        if (status == EvidenceStatus.NO_FINDING) {
            throw new IllegalArgumentException("relationship quantification must not use NO_FINDING");
        }
        if (status == EvidenceStatus.SUCCESS && supportN < 2L) {
            throw new IllegalArgumentException("SUCCESS quantification requires supportN >= 2");
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

    public List<FindingRow> findings() {
        return findings;
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
        private List<FindingRow> findings = List.of();
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

        public Builder findings(List<FindingRow> v) {
            this.findings = v;
            return this;
        }

        public Builder metrics(Map<String, String> v) {
            this.metrics = v;
            return this;
        }

        public G2RelationshipResult build() {
            return new G2RelationshipResult(this);
        }
    }
}
