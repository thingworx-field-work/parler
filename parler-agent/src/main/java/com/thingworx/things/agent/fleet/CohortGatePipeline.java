package com.thingworx.things.agent.fleet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.thingworx.things.agent.quality.QualitySeverity;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

/**
 * FRC-1 steps 4–5: apply unit/grain/method + quality gates to source rows, rebuild published
 * coverage (demotions leave {@code valuedN} and enter the demotion bucket), reconcile the frozen
 * authorized set, and assemble per-member evidence (no permission-limited identities).
 */
public final class CohortGatePipeline {

    private CohortGatePipeline() {}

    public static CohortCollectionResult apply(
            FrozenCohortMembership membership,
            MetricComparabilitySpec spec,
            List<CohortBatchMemberRow> sourceRows,
            boolean permissionLimited,
            CompletenessStatus sourceCompleteness,
            MemberQualityGate qualityGate) {
        Objects.requireNonNull(membership, "membership");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(sourceRows, "sourceRows");
        CompletenessStatus completeness =
                sourceCompleteness == null ? CompletenessStatus.COMPLETE : sourceCompleteness;
        MemberQualityGate gate = qualityGate == null ? MemberQualityGate.allowAll() : qualityGate;

        List<GatedMemberOutcome> outcomes = new ArrayList<>();
        Set<String> seenAuthorized = new HashSet<>();
        for (CohortBatchMemberRow row : sourceRows) {
            if (row == null) {
                continue;
            }
            if (row.status() != CohortMemberStatus.PERMISSION_LIMITED) {
                String id = row.semanticAssetId();
                if (id != null && membership.contains(id) && !seenAuthorized.add(id.trim())) {
                    throw new CohortCollectionException(
                            CohortCollectionException.COHORT_MEMBER_DUPLICATE,
                            "duplicate cohort member row for frozen asset " + id.trim());
                }
            }
            outcomes.add(gateOne(membership, spec, row, gate));
        }

        reconcileAuthorizedSet(membership, seenAuthorized, completeness);

        CohortCoverageCounts coverage = publishCoverage(membership.authorizedN(), outcomes, permissionLimited);
        List<FleetMemberEvidence> evidence = new ArrayList<>();
        for (GatedMemberOutcome o : outcomes) {
            if (o.status() != CohortMemberStatus.PERMISSION_LIMITED) {
                evidence.add(FleetMemberEvidence.of(o));
            }
        }
        return new CohortCollectionResult(membership, outcomes, coverage, evidence, completeness);
    }

    /**
     * Every frozen authorized member must appear exactly once among non-permission source rows.
     * Duplicates are rejected above. Omissions fail when completeness is {@code COMPLETE}; when
     * {@code PARTIAL}/{@code UNKNOWN}, shortfall is explained by {@link CohortCollectionResult#batchSourcePartial()}.
     *
     * <p>Contract note: a {@code COMPLETE} source that returns a frozen authorized id
     * only as {@link CohortMemberStatus#PERMISSION_LIMITED} does not count that id in
     * {@code seenAuthorized}, so reconcile fails with {@code COHORT_MEMBER_OMITTED}. That is
     * intentional fail-closed behavior — a member frozen as authorized under the current principal
     * must not reappear unauthorized under a COMPLETE claim (decision 2 freeze × decision 5
     * permission≠absence).
     */
    static void reconcileAuthorizedSet(
            FrozenCohortMembership membership,
            Set<String> seenAuthorized,
            CompletenessStatus completeness) {
        if (completeness != CompletenessStatus.COMPLETE) {
            return;
        }
        for (String authId : membership.authorizedSemanticAssetIds()) {
            if (!seenAuthorized.contains(authId)) {
                throw new CohortCollectionException(
                        CohortCollectionException.COHORT_MEMBER_OMITTED,
                        "COMPLETE batch source omitted frozen authorized member " + authId);
            }
        }
    }

    private static GatedMemberOutcome gateOne(
            FrozenCohortMembership membership,
            MetricComparabilitySpec spec,
            CohortBatchMemberRow row,
            MemberQualityGate gate) {
        if (row.status() == CohortMemberStatus.PERMISSION_LIMITED) {
            return GatedMemberOutcome.fromSource(row);
        }
        String id = row.semanticAssetId();
        if (id == null || !membership.contains(id)) {
            // Unauthorized / unknown identity must not become a named "missing" member.
            return GatedMemberOutcome.builder()
                    .status(CohortMemberStatus.PERMISSION_LIMITED)
                    .reasonCode("NOT_IN_FROZEN_AUTHORIZED_SET")
                    .build();
        }
        if (row.status() != CohortMemberStatus.ELIGIBLE_VALUE) {
            return GatedMemberOutcome.fromSource(row);
        }
        if (!spec.matches(row)) {
            return GatedMemberOutcome.builder()
                    .semanticAssetId(id)
                    .status(CohortMemberStatus.INCOMPARABLE)
                    .unit(row.unit())
                    .grain(row.grain())
                    .methodId(row.methodId())
                    .reasonCode("UNIT_OR_GRAIN_OR_METHOD_MISMATCH")
                    .build();
        }
        QualitySeverity severity = gate.severityFor(id);
        if (severity == QualitySeverity.BLOCKING) {
            return GatedMemberOutcome.builder()
                    .semanticAssetId(id)
                    .status(CohortMemberStatus.INSUFFICIENT_EVIDENCE)
                    .unit(row.unit())
                    .grain(row.grain())
                    .methodId(row.methodId())
                    .reasonCode("QUALITY_BLOCKING")
                    .build();
        }
        return GatedMemberOutcome.fromSource(row);
    }

    /**
     * Published coverage after gates. Comparable members are the post-gate {@code ELIGIBLE_VALUE}
     * set; they remain in {@code valuedN}. Demotions move members <em>out</em> of {@code valuedN}
     * into {@code incomparableN} / {@code insufficientEvidenceN} so the partition stays true.
     */
    static CohortCoverageCounts publishCoverage(
            int requestedAuthorizedN, List<GatedMemberOutcome> outcomes, boolean permissionLimited) {
        int returned = 0;
        int valued = 0;
        int comparable = 0;
        int noData = 0;
        int incomparable = 0;
        int insufficient = 0;
        int error = 0;
        boolean perm = permissionLimited;
        for (GatedMemberOutcome o : outcomes) {
            if (o.status() == CohortMemberStatus.PERMISSION_LIMITED) {
                perm = true;
                continue;
            }
            returned++;
            switch (o.status()) {
                case ELIGIBLE_VALUE:
                    valued++;
                    comparable++;
                    break;
                case NO_DATA:
                    noData++;
                    break;
                case INCOMPARABLE:
                    incomparable++;
                    break;
                case INSUFFICIENT_EVIDENCE:
                    insufficient++;
                    break;
                case ERROR:
                    error++;
                    break;
                case PERMISSION_LIMITED:
                    break;
            }
        }
        return CohortCoverageCounts.builder()
                .requestedAuthorizedN(requestedAuthorizedN)
                .returnedN(returned)
                .valuedN(valued)
                .comparableN(comparable)
                .noDataN(noData)
                .incomparableN(incomparable)
                .insufficientEvidenceN(insufficient)
                .errorN(error)
                .permissionLimited(perm)
                .build();
    }
}
