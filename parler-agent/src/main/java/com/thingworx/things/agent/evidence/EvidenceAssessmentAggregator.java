package com.thingworx.things.agent.evidence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.taskstate.AgentTaskEvidence;
import com.thingworx.things.agent.taskstate.AgentTaskState;
import com.thingworx.things.agent.taskstate.TaskStateErrorCode;

/**
 * Deterministic EG4 aggregator: {@link AgentTaskEvidence} rows → compact
 * {@link EvidenceAssessment}. Never copies raw row payloads. Completeness never silently upgrades
 * partial/unknown/sampled/protected/inferred evidence — {@code COMPLETE} must be proven.
 */
public final class EvidenceAssessmentAggregator {

    private EvidenceAssessmentAggregator() {}

    public static EvidenceAssessment fromTaskState(AgentTaskState state) {
        return fromTaskState(state, null);
    }

    /**
     * @param optionalMethod lineage when a current consumer already knows method/profile facts
     *        (otherwise omitted — U3 does not invent methods)
     */
    public static EvidenceAssessment fromTaskState(AgentTaskState state, EvidenceMethodRef optionalMethod) {
        if (state == null
                || (state.getEvidenceRows().isEmpty() && state.getAnalysisAssessments().isEmpty())) {
            return EvidenceAssessment.builder()
                    .status(EvidenceStatus.INSUFFICIENT_EVIDENCE)
                    .completeness(SourceDescriptor.CompletenessStatus.UNKNOWN)
                    .warnings(List.of("no_evidence_rows"))
                    .method(optionalMethod)
                    .build();
        }
        if (state.getEvidenceRows().isEmpty()) {
            // Analysis assessments are the sole evidence (e.g. analyze_cached_result with
            // mayPublish=false). Preserve their computed status/completeness — do not seed an
            // independent INSUFFICIENT_EVIDENCE for "no publishable rows."
            return foldAnalysisAssessments(state.getAnalysisAssessments(), optionalMethod);
        }

        boolean anyError = false;
        boolean anyOkWithRows = false;
        boolean anyEmptySuccess = false;
        boolean anySampleOnly = false;
        boolean anyProtected = false;
        boolean anyCacheMiss = false;
        boolean anyInProgress = false;
        boolean anyInferredTotal = false;
        boolean anyUnknownCompleteness = false;
        boolean anyPartialCompleteness = false;
        boolean allOkRowsProvenComplete = true;
        boolean sawOkRow = false;
        long n = 0L;
        Set<String> cacheIds = new LinkedHashSet<>();
        List<String> quality = new ArrayList<>();
        List<String> applicability = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();

        for (AgentTaskEvidence e : state.getEvidenceRows()) {
            if (e == null) {
                continue;
            }
            String st = e.getStatus();
            if ("in-progress".equals(st)) {
                anyInProgress = true;
                allOkRowsProvenComplete = false;
            } else if ("error".equals(st)) {
                anyError = true;
                allOkRowsProvenComplete = false;
                if (e.getErrorCodeEnum() == TaskStateErrorCode.CACHE_MISS) {
                    anyCacheMiss = true;
                }
            } else if ("ok".equals(st)) {
                sawOkRow = true;
                SourceDescriptor.CompletenessStatus rowCompleteness = resolveRowCompleteness(e);
                if (rowCompleteness == SourceDescriptor.CompletenessStatus.PARTIAL) {
                    anyPartialCompleteness = true;
                    allOkRowsProvenComplete = false;
                } else if (rowCompleteness == SourceDescriptor.CompletenessStatus.UNKNOWN) {
                    anyUnknownCompleteness = true;
                    allOkRowsProvenComplete = false;
                } else if (rowCompleteness != SourceDescriptor.CompletenessStatus.COMPLETE) {
                    allOkRowsProvenComplete = false;
                }
                if (e.isTotalCountInferred()) {
                    anyInferredTotal = true;
                    allOkRowsProvenComplete = false;
                }
                if (e.getRowCount() > 0) {
                    anyOkWithRows = true;
                    n += e.getRowCount();
                } else if (isSemanticEmptySuccess(e)) {
                    anyEmptySuccess = true;
                } else {
                    // ok but not classified as empty-success (e.g. unknown rowCount)
                    allOkRowsProvenComplete = false;
                    anyUnknownCompleteness = true;
                }
            }
            if (e.isSampleOnly()) {
                anySampleOnly = true;
            }
            if (e.isProtectedOmissions()
                    || e.getErrorCodeEnum() == TaskStateErrorCode.PROTECTED_VALUE_OMITTED
                    || e.getErrorCodeEnum() == TaskStateErrorCode.PROTECTED_VALUE_READ_BLOCKED
                    || e.getErrorCodeEnum() == TaskStateErrorCode.PROTECTED_VALUE_WRITE_BLOCKED
                    || e.getErrorCodeEnum() == TaskStateErrorCode.PROTECTED_VALUE_INPUT_BLOCKED) {
                anyProtected = true;
            }
            String cid = e.getCacheId();
            if (cid != null && !cid.isBlank()) {
                cacheIds.add(cid.trim());
            }
        }

        if (anySampleOnly) {
            quality.add("sample_only");
        }
        if (anyInferredTotal) {
            quality.add("total_inferred");
        }
        if (anyProtected) {
            warnings.add("protected_omissions");
        }
        if (anyCacheMiss) {
            warnings.add("cache_miss");
        }
        if (anyInProgress) {
            warnings.add("in_progress_rows");
        }
        if ((anyOkWithRows || anyEmptySuccess) && optionalMethod == null) {
            applicability.add("associational");
            applicability.add("not_tested_causal");
        }

        SourceDescriptor.CompletenessStatus completeness = resolveCompleteness(anySampleOnly, anyProtected,
                anyPartialCompleteness, anyUnknownCompleteness, anyInferredTotal, sawOkRow,
                allOkRowsProvenComplete, anyError);

        EvidenceStatus status = resolveStatus(anyError, anyInProgress, anySampleOnly, anyProtected, anyCacheMiss,
                anyOkWithRows, anyEmptySuccess, completeness);

        if (status == EvidenceStatus.INSUFFICIENT_EVIDENCE && anyEmptySuccess && !anyOkWithRows
                && completeness != SourceDescriptor.CompletenessStatus.COMPLETE) {
            conflicts.add("empty_success_under_incomplete");
        }
        if (anyOkWithRows && completeness != SourceDescriptor.CompletenessStatus.COMPLETE) {
            conflicts.add("rows_under_unproven_completeness");
        }

        EvidenceAssessment base = EvidenceAssessment.builder()
                .status(status)
                .completeness(completeness)
                .n(n)
                .quality(quality)
                .applicability(applicability)
                .warnings(warnings)
                .conflicts(conflicts)
                .sourceCacheIds(new ArrayList<>(cacheIds))
                .method(optionalMethod)
                .build();
        return mergeAnalysisAssessments(base, state.getAnalysisAssessments());
    }

    /**
     * Fold sole-source analysis assessments (no evidence rows). Starts from the first assessment so
     * computed U5 {@code NO_FINDING}/{@code SUCCESS}/completeness are preserved.
     */
    static EvidenceAssessment foldAnalysisAssessments(List<EvidenceAssessment> analysisAssessments,
            EvidenceMethodRef optionalMethod) {
        if (analysisAssessments == null || analysisAssessments.isEmpty()) {
            return EvidenceAssessment.builder()
                    .status(EvidenceStatus.INSUFFICIENT_EVIDENCE)
                    .completeness(SourceDescriptor.CompletenessStatus.UNKNOWN)
                    .warnings(List.of("no_evidence_rows"))
                    .method(optionalMethod)
                    .build();
        }
        EvidenceAssessment merged = null;
        for (EvidenceAssessment a : analysisAssessments) {
            if (a == null) {
                continue;
            }
            if (merged == null) {
                merged = a;
                continue;
            }
            merged = combineAssessments(merged, a);
        }
        if (merged == null) {
            return EvidenceAssessment.builder()
                    .status(EvidenceStatus.INSUFFICIENT_EVIDENCE)
                    .completeness(SourceDescriptor.CompletenessStatus.UNKNOWN)
                    .warnings(List.of("no_evidence_rows"))
                    .method(optionalMethod)
                    .build();
        }
        if (optionalMethod != null && merged.method() == null) {
            return EvidenceAssessment.builder()
                    .status(merged.status())
                    .completeness(merged.completeness())
                    .coverage(merged.coverage())
                    .n(merged.n())
                    .quality(merged.quality())
                    .applicability(merged.applicability())
                    .warnings(merged.warnings())
                    .conflicts(merged.conflicts())
                    .sourceCacheIds(merged.sourceCacheIds())
                    .method(optionalMethod)
                    .build();
        }
        return merged;
    }

    /**
     * Merge U4/U5 analysis-envelope assessments into the row-derived assessment. Status prefers the
     * more conservative overclaim-safety rank; applicability/quality/warnings/conflicts/cache ids
     * are unioned.
     */
    static EvidenceAssessment mergeAnalysisAssessments(EvidenceAssessment base,
            List<EvidenceAssessment> analysisAssessments) {
        if (analysisAssessments == null || analysisAssessments.isEmpty()) {
            return base;
        }
        EvidenceAssessment merged = base;
        for (EvidenceAssessment a : analysisAssessments) {
            if (a == null) {
                continue;
            }
            if (merged == null) {
                merged = a;
                continue;
            }
            merged = combineAssessments(merged, a);
        }
        return merged;
    }

    private static EvidenceAssessment combineAssessments(EvidenceAssessment left, EvidenceAssessment right) {
        return EvidenceAssessment.builder()
                .status(moreConservativeStatus(left.status(), right.status()))
                .completeness(moreConservative(left.completeness(), right.completeness()))
                .coverage(left.coverage() != null ? left.coverage() : right.coverage())
                .n(Math.max(left.n(), right.n()))
                .quality(unionTokens(left.quality(), right.quality()))
                .applicability(unionTokens(left.applicability(), right.applicability()))
                .warnings(unionTokens(left.warnings(), right.warnings()))
                .conflicts(unionTokens(left.conflicts(), right.conflicts()))
                .sourceCacheIds(unionTokens(left.sourceCacheIds(), right.sourceCacheIds()))
                .method(left.method() != null ? left.method() : right.method())
                .build();
    }

    static EvidenceStatus moreConservativeStatus(EvidenceStatus a, EvidenceStatus b) {
        return statusRank(a) >= statusRank(b) ? a : b;
    }

    private static int statusRank(EvidenceStatus s) {
        if (s == null) {
            return 0;
        }
        switch (s) {
            case ERROR:
                return 4;
            case INSUFFICIENT_EVIDENCE:
                return 3;
            case NO_FINDING:
                return 2;
            case SUCCESS:
                return 1;
            default:
                return 0;
        }
    }

    private static List<String> unionTokens(List<String> left, List<String> right) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (left != null) {
            out.addAll(left);
        }
        if (right != null) {
            out.addAll(right);
        }
        return new ArrayList<>(out);
    }

    /**
     * Per-row completeness: honor carried source/adapter status, never upgrade past facts that
     * prove partial/unknown (sample, inferred total, missing total, page/cache partial).
     */
    static SourceDescriptor.CompletenessStatus resolveRowCompleteness(AgentTaskEvidence e) {
        SourceDescriptor.CompletenessStatus fromFacts = deriveCompletenessFromFacts(e);
        SourceDescriptor.CompletenessStatus carried = e.getCompletenessStatus();
        if (carried == null) {
            carried = lookupDescriptorCompleteness(e.getCacheId());
        }
        if (carried == null) {
            return fromFacts;
        }
        return moreConservative(carried, fromFacts);
    }

    static SourceDescriptor.CompletenessStatus deriveCompletenessFromFacts(AgentTaskEvidence e) {
        if (e == null || !"ok".equals(e.getStatus())) {
            return SourceDescriptor.CompletenessStatus.UNKNOWN;
        }
        if (e.isSampleOnly() || e.isProtectedOmissions()) {
            return SourceDescriptor.CompletenessStatus.PARTIAL;
        }
        if (e.isTotalCountInferred()) {
            return SourceDescriptor.CompletenessStatus.UNKNOWN;
        }
        String rk = e.getResultKind();
        if ("CACHED_PAGE".equals(rk) || "INFOTABLE_LARGE".equals(rk)) {
            // Paged/large paths are complete only when the adapter already cleared sampleOnly and
            // recorded a non-inferred total equal to the returned rows.
            if (e.getRowCount() >= 0 && e.getTotalCount() >= 0 && e.getRowCount() == e.getTotalCount()
                    && !e.isSampleOnly() && !e.isTotalCountInferred()) {
                return SourceDescriptor.CompletenessStatus.COMPLETE;
            }
            return SourceDescriptor.CompletenessStatus.PARTIAL;
        }
        if (e.getRowCount() < 0) {
            return SourceDescriptor.CompletenessStatus.UNKNOWN;
        }
        if (e.getTotalCount() < 0) {
            return SourceDescriptor.CompletenessStatus.UNKNOWN;
        }
        if (e.getRowCount() < e.getTotalCount()) {
            return SourceDescriptor.CompletenessStatus.PARTIAL;
        }
        // Proven full return: non-inferred total equals returned rows (includes empty total=0).
        if (e.getRowCount() == e.getTotalCount() && !e.isTotalCountInferred()) {
            return SourceDescriptor.CompletenessStatus.COMPLETE;
        }
        return SourceDescriptor.CompletenessStatus.UNKNOWN;
    }

    private static SourceDescriptor.CompletenessStatus lookupDescriptorCompleteness(String cacheId) {
        if (cacheId == null || cacheId.isBlank()) {
            return null;
        }
        try {
            SourceDescriptor desc = TabularArtifactHub.lookupDescriptor(cacheId.trim());
            return desc != null ? desc.completenessStatus() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Aggregate completeness: never upgrade. PARTIAL wins over UNKNOWN/COMPLETE; UNKNOWN wins over
     * COMPLETE; COMPLETE only when every ok row is proven complete and no downgrade facts remain.
     */
    static SourceDescriptor.CompletenessStatus resolveCompleteness(boolean anySampleOnly, boolean anyProtected,
            boolean anyPartialCompleteness, boolean anyUnknownCompleteness, boolean anyInferredTotal,
            boolean sawOkRow, boolean allOkRowsProvenComplete, boolean anyError) {
        if (anySampleOnly || anyProtected || anyPartialCompleteness) {
            return SourceDescriptor.CompletenessStatus.PARTIAL;
        }
        if (anyInferredTotal || anyUnknownCompleteness || !sawOkRow) {
            return SourceDescriptor.CompletenessStatus.UNKNOWN;
        }
        if (allOkRowsProvenComplete) {
            return SourceDescriptor.CompletenessStatus.COMPLETE;
        }
        if (anyError && !sawOkRow) {
            return SourceDescriptor.CompletenessStatus.UNKNOWN;
        }
        return SourceDescriptor.CompletenessStatus.UNKNOWN;
    }

    /**
     * Status monotonicity: ERROR wins for error-only; unproven completeness never becomes
     * {@link EvidenceStatus#SUCCESS} or {@link EvidenceStatus#NO_FINDING}.
     */
    static EvidenceStatus resolveStatus(boolean anyError, boolean anyInProgress, boolean anySampleOnly,
            boolean anyProtected, boolean anyCacheMiss, boolean anyOkWithRows, boolean anyEmptySuccess,
            SourceDescriptor.CompletenessStatus completeness) {
        if (anyError && !anyOkWithRows && !anyEmptySuccess) {
            return EvidenceStatus.ERROR;
        }
        if (anyError && (anyOkWithRows || anyEmptySuccess)) {
            return EvidenceStatus.INSUFFICIENT_EVIDENCE;
        }
        if (anyInProgress || anySampleOnly || anyProtected || anyCacheMiss
                || completeness != SourceDescriptor.CompletenessStatus.COMPLETE) {
            return EvidenceStatus.INSUFFICIENT_EVIDENCE;
        }
        // completeness == COMPLETE and no error/sample/protection/cache-miss/in-progress
        if (anyEmptySuccess && !anyOkWithRows) {
            return EvidenceStatus.NO_FINDING;
        }
        if (anyOkWithRows) {
            return EvidenceStatus.SUCCESS;
        }
        return EvidenceStatus.INSUFFICIENT_EVIDENCE;
    }

    private static boolean isSemanticEmptySuccess(AgentTaskEvidence e) {
        return "ok".equals(e.getStatus())
                && e.getRowCount() == 0
                && e.getErrorCodeEnum() == null
                && !e.isSampleOnly()
                && e.getTotalCount() <= 0;
    }

    /** More conservative of two statuses (PARTIAL &lt; UNKNOWN &lt; COMPLETE for trust). */
    static SourceDescriptor.CompletenessStatus moreConservative(SourceDescriptor.CompletenessStatus a,
            SourceDescriptor.CompletenessStatus b) {
        if (a == SourceDescriptor.CompletenessStatus.PARTIAL
                || b == SourceDescriptor.CompletenessStatus.PARTIAL) {
            return SourceDescriptor.CompletenessStatus.PARTIAL;
        }
        if (a == SourceDescriptor.CompletenessStatus.UNKNOWN
                || b == SourceDescriptor.CompletenessStatus.UNKNOWN) {
            return SourceDescriptor.CompletenessStatus.UNKNOWN;
        }
        return SourceDescriptor.CompletenessStatus.COMPLETE;
    }
}
