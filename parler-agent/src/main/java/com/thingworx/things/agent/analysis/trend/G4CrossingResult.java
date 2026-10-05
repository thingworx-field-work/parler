package com.thingworx.things.agent.analysis.trend;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.thingworx.things.agent.analysis.FindingRow;
import com.thingworx.things.agent.analysis.ThresholdCrossingOutcome;
import com.thingworx.things.agent.evidence.EvidenceStatus;

/**
 * Detection result for {@code threshold_crossing}. Mapped outcomes lock status to
 * {@link ThresholdCrossingOutcome#status()}. Hard faults (auth/cache/budget/internal per §6.3.2)
 * use {@link EvidenceStatus#ERROR} with a fault code and no mapped outcome.
 */
public final class G4CrossingResult {

    private final ThresholdCrossingOutcome outcome;
    private final EvidenceStatus status;
    private final String outcomeCode;
    private final String methodId;
    private final long supportN;
    private final Instant estimatedCrossingAt;
    private final List<FindingRow> findings;
    private final Map<String, String> metrics;

    private G4CrossingResult(Builder b) {
        this.methodId = Objects.requireNonNull(b.methodId, "methodId").trim();
        this.supportN = b.supportN;
        if (supportN < 0L) {
            throw new IllegalArgumentException("supportN must be non-negative");
        }
        this.estimatedCrossingAt = b.estimatedCrossingAt;
        this.findings = List.copyOf(b.findings == null ? List.of() : b.findings);
        this.metrics = copyMetrics(b.metrics);
        if (b.hardFault) {
            this.outcome = null;
            this.status = EvidenceStatus.ERROR;
            this.outcomeCode = requireCode(b.faultCode, "faultCode");
            if (!findings.isEmpty()) {
                throw new IllegalArgumentException("ERROR hard fault must not carry findings");
            }
        } else {
            this.outcome = Objects.requireNonNull(b.outcome, "outcome");
            this.status = outcome.status();
            this.outcomeCode = outcome.code();
            if (status == EvidenceStatus.NO_FINDING && supportN <= 0L) {
                throw new IllegalArgumentException("NO_FINDING requires positive support");
            }
            if (status == EvidenceStatus.SUCCESS && outcome == ThresholdCrossingOutcome.CROSSING_WITHIN_HORIZON
                    && estimatedCrossingAt == null) {
                throw new IllegalArgumentException("crossing_within_horizon requires estimatedCrossingAt");
            }
            if (status == EvidenceStatus.SUCCESS && findings.isEmpty()
                    && outcome == ThresholdCrossingOutcome.CROSSING_WITHIN_HORIZON) {
                throw new IllegalArgumentException("crossing_within_horizon requires a finding row");
            }
            if (status == EvidenceStatus.NO_FINDING && !findings.isEmpty()) {
                throw new IllegalArgumentException("NO_FINDING must not carry findings");
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mapped §6.3.3 outcome, or {@code null} for hard-fault {@link EvidenceStatus#ERROR}. */
    public ThresholdCrossingOutcome outcome() {
        return outcome;
    }

    public String outcomeCode() {
        return outcomeCode;
    }

    public EvidenceStatus status() {
        return status;
    }

    public String methodId() {
        return methodId;
    }

    public long supportN() {
        return supportN;
    }

    public Instant estimatedCrossingAt() {
        return estimatedCrossingAt;
    }

    public List<FindingRow> findings() {
        return findings;
    }

    public Map<String, String> metrics() {
        return metrics;
    }

    private static String requireCode(String code, String name) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return code.trim();
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
        private ThresholdCrossingOutcome outcome;
        private boolean hardFault;
        private String faultCode;
        private String methodId;
        private long supportN;
        private Instant estimatedCrossingAt;
        private List<FindingRow> findings = List.of();
        private Map<String, String> metrics = Map.of();

        public Builder outcome(ThresholdCrossingOutcome v) {
            this.outcome = v;
            this.hardFault = false;
            this.faultCode = null;
            return this;
        }

        /** Hard fault outside the §6.3.3 map (§6.3.2 budget/auth/cache/internal → ERROR). */
        public Builder hardFault(String faultCode) {
            this.hardFault = true;
            this.faultCode = faultCode;
            this.outcome = null;
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

        public Builder estimatedCrossingAt(Instant v) {
            this.estimatedCrossingAt = v;
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

        public G4CrossingResult build() {
            return new G4CrossingResult(this);
        }
    }
}
