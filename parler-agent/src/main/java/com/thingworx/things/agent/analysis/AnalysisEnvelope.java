package com.thingworx.things.agent.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.thingworx.things.agent.evidence.EvidenceAssessment;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.source.SourceDescriptor;

/**
 * U4 base analysis result envelope (B1). Internal-only for v1 — not a normative CONTRACTS type.
 * Status and completeness reuse U3/U2 vocabularies; evidence composes {@link EvidenceAssessment}.
 */
public final class AnalysisEnvelope {

    private final EvidenceStatus status;
    private final AnalysisOperation operation;
    private final List<String> sourceCacheIds;
    private final String findingCacheId;
    private final AnalysisMethodDescriptor method;
    private final EvidenceAssessment evidence;
    private final Map<String, String> metrics;
    private final List<String> summaryFacts;
    private final String chartIntent;
    private final AnalysisBudgetAccounting budget;
    private final long rowsRead;
    private final long rowsOutput;
    private final boolean inputsFullyScanned;

    private AnalysisEnvelope(Builder b) {
        this.status = Objects.requireNonNull(b.status, "status");
        this.operation = Objects.requireNonNull(b.operation, "operation");
        this.sourceCacheIds = copyOmitEmpty(b.sourceCacheIds);
        this.findingCacheId = blankToNull(b.findingCacheId);
        this.method = b.method;
        this.evidence = Objects.requireNonNull(b.evidence, "evidence");
        this.metrics = copyMetrics(b.metrics);
        this.summaryFacts = copyOmitEmpty(b.summaryFacts);
        this.chartIntent = blankToNull(b.chartIntent);
        this.budget = b.budget;
        this.rowsRead = requireNonNegative(b.rowsRead, "rowsRead");
        this.rowsOutput = requireNonNegative(b.rowsOutput, "rowsOutput");
        this.inputsFullyScanned = b.inputsFullyScanned;
    }

    public static Builder builder() {
        return new Builder();
    }

    public EvidenceStatus status() {
        return status;
    }

    public AnalysisOperation operation() {
        return operation;
    }

    public List<String> sourceCacheIds() {
        return sourceCacheIds;
    }

    public String findingCacheId() {
        return findingCacheId;
    }

    public AnalysisMethodDescriptor method() {
        return method;
    }

    public EvidenceAssessment evidence() {
        return evidence;
    }

    public Map<String, String> metrics() {
        return metrics;
    }

    public List<String> summaryFacts() {
        return summaryFacts;
    }

    public String chartIntent() {
        return chartIntent;
    }

    public AnalysisBudgetAccounting budget() {
        return budget;
    }

    public long rowsRead() {
        return rowsRead;
    }

    public long rowsOutput() {
        return rowsOutput;
    }

    public boolean inputsFullyScanned() {
        return inputsFullyScanned;
    }

    public SourceDescriptor.CompletenessStatus completeness() {
        return evidence.completeness();
    }

    private static long requireNonNegative(long v, String name) {
        if (v < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static List<String> copyOmitEmpty(List<String> in) {
        if (in == null || in.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) {
            if (s != null && !s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
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
            String v = e.getValue();
            out.put(e.getKey().trim(), v == null ? "" : v);
        }
        return out.isEmpty() ? Map.of() : Collections.unmodifiableMap(out);
    }

    public static final class Builder {
        private EvidenceStatus status;
        private AnalysisOperation operation;
        private List<String> sourceCacheIds = List.of();
        private String findingCacheId;
        private AnalysisMethodDescriptor method;
        private EvidenceAssessment evidence;
        private Map<String, String> metrics = Map.of();
        private List<String> summaryFacts = List.of();
        private String chartIntent;
        private AnalysisBudgetAccounting budget;
        private long rowsRead;
        private long rowsOutput;
        private boolean inputsFullyScanned;

        public Builder status(EvidenceStatus v) {
            this.status = v;
            return this;
        }

        public Builder operation(AnalysisOperation v) {
            this.operation = v;
            return this;
        }

        public Builder sourceCacheIds(List<String> v) {
            this.sourceCacheIds = v;
            return this;
        }

        public Builder findingCacheId(String v) {
            this.findingCacheId = v;
            return this;
        }

        public Builder method(AnalysisMethodDescriptor v) {
            this.method = v;
            return this;
        }

        public Builder evidence(EvidenceAssessment v) {
            this.evidence = v;
            return this;
        }

        public Builder metrics(Map<String, String> v) {
            this.metrics = v;
            return this;
        }

        public Builder summaryFacts(List<String> v) {
            this.summaryFacts = v;
            return this;
        }

        public Builder chartIntent(String v) {
            this.chartIntent = v;
            return this;
        }

        public Builder budget(AnalysisBudgetAccounting v) {
            this.budget = v;
            return this;
        }

        public Builder rowsRead(long v) {
            this.rowsRead = v;
            return this;
        }

        public Builder rowsOutput(long v) {
            this.rowsOutput = v;
            return this;
        }

        public Builder inputsFullyScanned(boolean v) {
            this.inputsFullyScanned = v;
            return this;
        }

        public AnalysisEnvelope build() {
            return new AnalysisEnvelope(this);
        }
    }
}
