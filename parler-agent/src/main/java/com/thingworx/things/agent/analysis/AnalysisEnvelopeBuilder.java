package com.thingworx.things.agent.analysis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.thingworx.things.agent.evidence.EvidenceAssessment;
import com.thingworx.things.agent.evidence.EvidenceMethodRef;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

/**
 * Composes the base {@link AnalysisEnvelope} from U3 evidence vocabulary and U2 source/budget
 * facts. Does not advertise tools or bind executors (B8).
 */
public final class AnalysisEnvelopeBuilder {

    private EvidenceStatus status;
    private AnalysisOperation operation;
    private final List<String> sourceCacheIds = new ArrayList<>();
    private String findingCacheId;
    private AnalysisMethodDescriptor method;
    private CompletenessStatus completeness = CompletenessStatus.UNKNOWN;
    private String coverage;
    private long n;
    private final List<String> quality = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> applicability = new ArrayList<>();
    private final List<String> conflicts = new ArrayList<>();
    private Map<String, String> metrics = Map.of();
    private List<String> summaryFacts = List.of();
    private String chartIntent;
    private AnalysisBudgetAccounting budget;
    private long rowsRead;
    private long rowsOutput;
    private boolean inputsFullyScanned;

    public static AnalysisEnvelopeBuilder create() {
        return new AnalysisEnvelopeBuilder();
    }

    public AnalysisEnvelopeBuilder status(EvidenceStatus v) {
        this.status = v;
        return this;
    }

    public AnalysisEnvelopeBuilder operation(AnalysisOperation v) {
        this.operation = v;
        return this;
    }

    public AnalysisEnvelopeBuilder addSourceCacheId(String cacheId) {
        if (cacheId != null && !cacheId.isBlank()) {
            this.sourceCacheIds.add(cacheId.trim());
        }
        return this;
    }

    public AnalysisEnvelopeBuilder sourceCacheIds(List<String> ids) {
        this.sourceCacheIds.clear();
        if (ids != null) {
            for (String id : ids) {
                addSourceCacheId(id);
            }
        }
        return this;
    }

    public AnalysisEnvelopeBuilder findingCacheId(String v) {
        this.findingCacheId = v;
        return this;
    }

    public AnalysisEnvelopeBuilder method(AnalysisMethodDescriptor v) {
        this.method = v;
        return this;
    }

    public AnalysisEnvelopeBuilder completeness(CompletenessStatus v) {
        this.completeness = v == null ? CompletenessStatus.UNKNOWN : v;
        return this;
    }

    /** Merge parent completeness conservatively before setting the envelope completeness. */
    public AnalysisEnvelopeBuilder completenessFromParents(CompletenessStatus... parents) {
        this.completeness = CompletenessPropagation.mergeParents(parents);
        return this;
    }

    public AnalysisEnvelopeBuilder coverage(String v) {
        this.coverage = v;
        return this;
    }

    public AnalysisEnvelopeBuilder n(long v) {
        this.n = v;
        return this;
    }

    public AnalysisEnvelopeBuilder addQuality(String token) {
        if (token != null && !token.isBlank()) {
            this.quality.add(token.trim());
        }
        return this;
    }

    public AnalysisEnvelopeBuilder addWarning(String token) {
        if (token != null && !token.isBlank()) {
            this.warnings.add(token.trim());
        }
        return this;
    }

    public AnalysisEnvelopeBuilder addApplicability(String token) {
        if (token != null && !token.isBlank()) {
            String t = token.trim();
            if (!this.applicability.contains(t)) {
                this.applicability.add(t);
            }
        }
        return this;
    }

    public AnalysisEnvelopeBuilder addBlockingQuality(String token) {
        addQuality(token);
        if (token != null && !token.isBlank()) {
            String marked = "BLOCKING:" + token.trim();
            if (!this.quality.contains(marked)) {
                this.quality.add(marked);
            }
        }
        return this;
    }

    public AnalysisEnvelopeBuilder metrics(Map<String, String> v) {
        this.metrics = v == null ? Map.of() : v;
        return this;
    }

    public AnalysisEnvelopeBuilder summaryFacts(List<String> v) {
        this.summaryFacts = v == null ? List.of() : v;
        return this;
    }

    public AnalysisEnvelopeBuilder chartIntent(String v) {
        this.chartIntent = v;
        return this;
    }

    public AnalysisEnvelopeBuilder budget(AnalysisBudgetAccounting v) {
        this.budget = v;
        return this;
    }

    public AnalysisEnvelopeBuilder rowsRead(long v) {
        this.rowsRead = v;
        return this;
    }

    public AnalysisEnvelopeBuilder rowsOutput(long v) {
        this.rowsOutput = v;
        return this;
    }

    public AnalysisEnvelopeBuilder inputsFullyScanned(boolean v) {
        this.inputsFullyScanned = v;
        return this;
    }

    public AnalysisEnvelope build() {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(operation, "operation");

        Set<String> cacheIds = new LinkedHashSet<>(sourceCacheIds);
        EvidenceMethodRef methodRef = null;
        if (method != null) {
            methodRef = EvidenceMethodRef.of(method.id(), method.version(), method.profileDigest());
        }

        EvidenceAssessment assessment = EvidenceAssessment.builder()
                .status(status)
                .completeness(completeness)
                .coverage(coverage)
                .n(n)
                .quality(List.copyOf(quality))
                .warnings(List.copyOf(warnings))
                .applicability(List.copyOf(applicability))
                .conflicts(List.copyOf(conflicts))
                .sourceCacheIds(List.copyOf(cacheIds))
                .method(methodRef)
                .build();

        AnalysisEnvelope envelope = AnalysisEnvelope.builder()
                .status(status)
                .operation(operation)
                .sourceCacheIds(List.copyOf(cacheIds))
                .findingCacheId(findingCacheId)
                .method(method)
                .evidence(assessment)
                .metrics(metrics)
                .summaryFacts(summaryFacts)
                .chartIntent(chartIntent)
                .budget(budget)
                .rowsRead(rowsRead)
                .rowsOutput(rowsOutput)
                .inputsFullyScanned(inputsFullyScanned)
                .build();

        AnalysisEnvelopeValidator.validateOrThrow(envelope);
        return envelope;
    }
}
