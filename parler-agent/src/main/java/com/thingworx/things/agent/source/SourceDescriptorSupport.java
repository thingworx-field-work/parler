package com.thingworx.things.agent.source;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.execution.RunInvocationContext;
import com.thingworx.things.agent.semantics.SemanticResolveResult;
import com.thingworx.things.agent.semantics.SemanticResolveStatus;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.types.InfoTable;

/**
 * Build {@link SourceDescriptor} for producers. Full descriptor stays runtime-only.
 *
 * <p>BP6 public wire fields ({@code completeness}, {@code counts}) are emitted on
 * {@code tabulate_cached_result} / {@code summarize_cached_result} success via
 * {@link #putPublicEnvelopeFields} per {@code CONTRACTS/TABULAR_INSIGHT.md} §5 (bundle 0.1.146).
 */
public final class SourceDescriptorSupport {

    private SourceDescriptorSupport() {}

    /**
     * Conservative primary-store descriptor. Completeness stays {@code UNKNOWN}.
     * {@code rowsAvailable} stays absent unless a caller proves a source total — never set equal
     * to the table row count while completeness is unknown (BP5 / invariant 5).
     */
    public static SourceDescriptor forPrimaryStore(InfoTable table, String sourceRouteId) {
        long rows = table == null || table.getRowCount() == null ? 0L : table.getRowCount().longValue();
        SourceDescriptor.Builder b = SourceDescriptor.builder()
                .sourceRouteId(sourceRouteId)
                .rowsExamined(rows)
                .rowsReturned(rows)
                // rowsAvailable intentionally omitted — unproven
                .completenessStatus(SourceDescriptor.CompletenessStatus.UNKNOWN)
                .requestId(AgentToolContext.getParlerRequestId());
        RunInvocationContext inv = AgentToolContext.getRunInvocationContext();
        if (inv != null) {
            b.executionScopeId(inv.opaqueScopeId());
        }
        return b.build();
    }

    /**
     * Apply U3S SP5 provenance from a {@link SemanticResolveResult} when status is {@code RESOLVED}.
     * Other statuses leave the descriptor unchanged (no guessed fallback).
     */
    public static SourceDescriptor withSemanticProvenance(SourceDescriptor base, SemanticResolveResult resolved) {
        if (base == null || resolved == null || resolved.status() != SemanticResolveStatus.RESOLVED
                || resolved.role() == null) {
            return base;
        }
        SourceDescriptor.Builder b = SourceDescriptor.builder()
                .sourceRouteId(base.sourceRouteId())
                .thingOrServiceKind(base.thingOrServiceKind())
                .sanitizedParamDigest(base.sanitizedParamDigest())
                .rowsExamined(base.rowsExamined())
                .rowsReturned(base.rowsReturned())
                .rowsAvailable(base.rowsAvailable())
                .completenessStatus(base.completenessStatus() == null
                        ? SourceDescriptor.CompletenessStatus.UNKNOWN
                        : base.completenessStatus())
                .completenessReasons(base.completenessReasons())
                .principalBinding(base.principalBinding())
                .executionScopeId(base.executionScopeId())
                .producerToolCallId(base.producerToolCallId())
                .requestId(base.requestId())
                .parentSourceCacheIds(base.parentSourceCacheIds())
                .propertyRoleRef(resolved.propertyRoleRef())
                .unitRef(resolved.role().unit())
                .grainRef(resolved.role().grain())
                .cadenceRef(resolved.role().expectedCadence())
                .semanticProfileId(resolved.profileId())
                .semanticProfileVersion(resolved.profileVersion())
                .semanticProfileDigest(resolved.profileDigest());
        SourceDescriptor.copyColumnRoles(b, base);
        SourceDescriptor.copySubjectIdentity(b, base);
        return b.build();
    }

    /**
     * Same-artifact rebuild that records the writer-declared subject identity (CM-4). A declaration
     * replaces any earlier identity: both fields are cleared first and set only when the new pair is
     * complete (both non-blank). Column roles and other provenance are untouched.
     */
    /**
     * Same-artifact rebuild that adds {@link ReadLimitFact#REASON} when the reader observed it. Status, counts,
     * column roles, subject identity, semantic facts and lineage are kept; the reason is not duplicated. A fact that
     * was not reached, or a {@code null} fact, returns {@code base} unchanged. There is no overload that guesses the
     * fact from a table's size.
     */
    public static SourceDescriptor withReadLimitFact(SourceDescriptor base, ReadLimitFact fact) {
        if (base == null || fact == null || !fact.reached() || base.completenessReasons().contains(ReadLimitFact.REASON)) {
            return base;
        }
        java.util.List<String> reasons = new java.util.ArrayList<>(base.completenessReasons());
        reasons.add(ReadLimitFact.REASON);
        return copyAll(base).completenessReasons(reasons).build();
    }

    public static SourceDescriptor withSubjectIdentity(SourceDescriptor base, String thingName, String propertyName) {
        if (base == null) {
            return null;
        }
        SourceDescriptor.Builder b = copyAll(base).subjectThingName(null).subjectPropertyName(null);
        String t = thingName == null ? "" : thingName.trim();
        String pn = propertyName == null ? "" : propertyName.trim();
        if (!t.isEmpty() && !pn.isEmpty()) {
            b.subjectThingName(t).subjectPropertyName(pn);
        }
        return b.build();
    }

    /**
     * Same-artifact rebuild that records writer-declared column roles (CM-0). Roles are validated
     * against {@code visibleColumnNames} (the table being written, PASSWORD columns excluded); an
     * invalid pair is omitted rather than failing the store, and any roles the base carried are
     * cleared either way (this declaration replaces them; it never falls back to stale roles).
     */
    public static SourceDescriptor withColumnRoles(SourceDescriptor base, String timeColumn, String valueColumn,
            java.util.Collection<String> visibleColumnNames) {
        if (base == null) {
            return null;
        }
        ColumnRoles roles = ColumnRoles.validate(timeColumn, valueColumn, visibleColumnNames);
        // A declaration replaces any earlier roles: clear first, then set only what validated.
        SourceDescriptor.Builder b = copyAll(base).timeColumn(null).valueColumn(null);
        if (roles != null) {
            b.timeColumn(roles.timeColumn()).valueColumn(roles.valueColumn());
        }
        return b.build();
    }

    private static SourceDescriptor.Builder copyAll(SourceDescriptor base) {
        SourceDescriptor.Builder b = SourceDescriptor.builder()
                .sourceRouteId(base.sourceRouteId())
                .thingOrServiceKind(base.thingOrServiceKind())
                .sanitizedParamDigest(base.sanitizedParamDigest())
                .rowsExamined(base.rowsExamined())
                .rowsReturned(base.rowsReturned())
                .rowsAvailable(base.rowsAvailable())
                .completenessStatus(base.completenessStatus() == null
                        ? SourceDescriptor.CompletenessStatus.UNKNOWN
                        : base.completenessStatus())
                .completenessReasons(base.completenessReasons())
                .principalBinding(base.principalBinding())
                .executionScopeId(base.executionScopeId())
                .producerToolCallId(base.producerToolCallId())
                .requestId(base.requestId())
                .parentSourceCacheIds(base.parentSourceCacheIds());
        SourceDescriptor.copySemanticProvenance(b, base);
        SourceDescriptor.copyColumnRoles(b, base);
        SourceDescriptor.copySubjectIdentity(b, base);
        return b;
    }

    /**
     * Derive composition: parent facts + {@code parentCacheId} lineage.
     * {@code rowsReturned} = derived output rows; {@code rowsExamined} prefers the parent's
     * examined/returned count when present (source rows scanned), never the derived output size
     * alone; {@code rowsAvailable} inherited only when the parent already had a proven total.
     */
    public static SourceDescriptor forDerivedStore(SourceDescriptor parent, String parentCacheId,
            InfoTable derived, String derivedRouteId) {
        long outRows = derived == null || derived.getRowCount() == null ? 0L : derived.getRowCount().longValue();
        SourceDescriptor base = parent == null
                ? forPrimaryStore(derived, derivedRouteId)
                : parent.composeDerived(derivedRouteId, parentCacheId);
        Long examined = null;
        Long available = null;
        if (parent != null) {
            if (parent.rowsExamined() != null) {
                examined = parent.rowsExamined();
            } else if (parent.rowsReturned() != null) {
                examined = parent.rowsReturned();
            }
            available = parent.rowsAvailable(); // may be null (unproven)
        }
        SourceDescriptor.Builder b = SourceDescriptor.builder()
                .sourceRouteId(base.sourceRouteId())
                .thingOrServiceKind(base.thingOrServiceKind())
                .sanitizedParamDigest(base.sanitizedParamDigest())
                .rowsExamined(examined)
                .rowsReturned(outRows)
                .rowsAvailable(available)
                .completenessStatus(base.completenessStatus() == null
                        ? SourceDescriptor.CompletenessStatus.UNKNOWN
                        : base.completenessStatus())
                .completenessReasons(base.completenessReasons())
                .principalBinding(base.principalBinding())
                .executionScopeId(base.executionScopeId())
                .producerToolCallId(base.producerToolCallId())
                .requestId(base.requestId() != null ? base.requestId() : AgentToolContext.getParlerRequestId())
                .parentSourceCacheIds(base.parentSourceCacheIds());
        SourceDescriptor.copySemanticProvenance(b, base);
        return b.build();
    }

    /** Append another parent cache id without dropping row counts or other facts. */
    public static SourceDescriptor appendParent(SourceDescriptor base, String parentCacheId) {
        if (base == null) {
            return SourceDescriptor.builder()
                    .addParentSourceCacheId(parentCacheId)
                    .completenessStatus(SourceDescriptor.CompletenessStatus.UNKNOWN)
                    .build();
        }
        SourceDescriptor.Builder b = SourceDescriptor.builder()
                .sourceRouteId(base.sourceRouteId())
                .thingOrServiceKind(base.thingOrServiceKind())
                .sanitizedParamDigest(base.sanitizedParamDigest())
                .rowsExamined(base.rowsExamined())
                .rowsReturned(base.rowsReturned())
                .rowsAvailable(base.rowsAvailable())
                .completenessStatus(base.completenessStatus() == null
                        ? SourceDescriptor.CompletenessStatus.UNKNOWN
                        : base.completenessStatus())
                .completenessReasons(base.completenessReasons())
                .principalBinding(base.principalBinding())
                .executionScopeId(base.executionScopeId())
                .producerToolCallId(base.producerToolCallId())
                .requestId(base.requestId())
                .parentSourceCacheIds(base.parentSourceCacheIds())
                .addParentSourceCacheId(parentCacheId);
        SourceDescriptor.copySemanticProvenance(b, base);
        SourceDescriptor.copyColumnRoles(b, base);
        SourceDescriptor.copySubjectIdentity(b, base);
        return b.build();
    }

    /**
     * BP6 public packaging subset for tabulate/summarize success JSON
     * ({@code CONTRACTS/TABULAR_INSIGHT.md} §5). Omits {@code counts.totalAvailable} when
     * {@link SourceDescriptor#rowsAvailable()} is null (unproven).
     */
    public static void putPublicEnvelopeFields(ObjectNode root, SourceDescriptor descriptor) {
        if (root == null || descriptor == null) {
            return;
        }
        ObjectNode completeness = root.putObject("completeness");
        SourceDescriptor.CompletenessStatus st = descriptor.completenessStatus();
        completeness.put("status", st == null ? SourceDescriptor.CompletenessStatus.UNKNOWN.name() : st.name());
        ArrayNode reasons = completeness.putArray("reasons");
        for (String r : descriptor.completenessReasons()) {
            if (r != null && !r.isBlank()) {
                reasons.add(r);
            }
        }
        ObjectNode counts = root.putObject("counts");
        if (descriptor.rowsExamined() != null) {
            counts.put("rowsRead", descriptor.rowsExamined());
        }
        if (descriptor.rowsReturned() != null) {
            counts.put("rowsOutput", descriptor.rowsReturned());
        }
        if (descriptor.rowsAvailable() != null) {
            counts.put("totalAvailable", descriptor.rowsAvailable());
        }
        if (descriptor.parentSourceCacheIds() != null && !descriptor.parentSourceCacheIds().isEmpty()) {
            String parent = descriptor.parentSourceCacheIds()
                    .get(descriptor.parentSourceCacheIds().size() - 1);
            if (parent != null && !parent.isBlank() && !root.has("sourceCacheId")) {
                root.put("sourceCacheId", parent);
            }
        }
    }
}
