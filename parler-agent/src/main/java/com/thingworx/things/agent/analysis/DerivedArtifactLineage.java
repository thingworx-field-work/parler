package com.thingworx.things.agent.analysis;

import java.util.ArrayList;
import java.util.List;

import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;
import com.thingworx.things.agent.source.SourceDescriptorSupport;
import com.thingworx.types.InfoTable;

/**
 * TQJ-1 lineage + monotone completeness for derived tabular artifacts. Uses U2
 * {@link SourceDescriptorSupport} and U4 {@link CompletenessPropagation}; never opens cache paths.
 */
public final class DerivedArtifactLineage {

    private DerivedArtifactLineage() {}

    public static SourceDescriptor forTransform(SourceDescriptor parent, String parentCacheId,
            InfoTable derived, String derivedRouteId, boolean inputsFullyScanned,
            boolean windowAndPolicyProven) {
        CompletenessStatus parentStatus = parent == null || parent.completenessStatus() == null
                ? CompletenessStatus.UNKNOWN
                : parent.completenessStatus();
        CompletenessStatus resultStatus = CompletenessPropagation.forTransform(
                parentStatus, inputsFullyScanned, windowAndPolicyProven);
        SourceDescriptor base = SourceDescriptorSupport.forDerivedStore(
                parent, parentCacheId, derived, derivedRouteId);
        return withCompleteness(base, resultStatus);
    }

    public static SourceDescriptor forJoin(SourceDescriptor left, String leftCacheId,
            SourceDescriptor right, String rightCacheId, InfoTable derived, String derivedRouteId,
            boolean inputsFullyScanned) {
        CompletenessStatus merged = CompletenessPropagation.mergeParents(
                left == null ? null : left.completenessStatus(),
                right == null ? null : right.completenessStatus());
        if (!inputsFullyScanned) {
            merged = CompletenessPropagation.worse(merged, CompletenessStatus.UNKNOWN);
        }
        SourceDescriptor base = SourceDescriptorSupport.forDerivedStore(
                left, leftCacheId, derived, derivedRouteId);
        base = SourceDescriptorSupport.appendParent(base, rightCacheId);
        return withCompleteness(base, merged);
    }

    public static SourceDescriptor withCompleteness(SourceDescriptor base, CompletenessStatus status) {
        if (base == null) {
            return SourceDescriptor.builder()
                    .completenessStatus(status == null ? CompletenessStatus.UNKNOWN : status)
                    .build();
        }
        SourceDescriptor.Builder b = SourceDescriptor.builder()
                .sourceRouteId(base.sourceRouteId())
                .thingOrServiceKind(base.thingOrServiceKind())
                .sanitizedParamDigest(base.sanitizedParamDigest())
                .rowsExamined(base.rowsExamined())
                .rowsReturned(base.rowsReturned())
                .rowsAvailable(base.rowsAvailable())
                .completenessStatus(status == null ? CompletenessStatus.UNKNOWN : status)
                .completenessReasons(base.completenessReasons())
                .principalBinding(base.principalBinding())
                .executionScopeId(base.executionScopeId())
                .producerToolCallId(base.producerToolCallId())
                .requestId(base.requestId())
                .parentSourceCacheIds(base.parentSourceCacheIds());
        SourceDescriptor.copySemanticProvenance(b, base);
        SourceDescriptor.copyColumnRoles(b, base);
        SourceDescriptor.copySubjectIdentity(b, base);
        return b.build();
    }

    public static List<String> parentCacheIds(SourceDescriptor descriptor) {
        if (descriptor == null || descriptor.parentSourceCacheIds() == null) {
            return List.of();
        }
        return new ArrayList<>(descriptor.parentSourceCacheIds());
    }
}
