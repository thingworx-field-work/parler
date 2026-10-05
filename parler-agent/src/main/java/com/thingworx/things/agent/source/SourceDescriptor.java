package com.thingworx.things.agent.source;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * U2 sole runtime source-facts descriptor, extended by U3S SP5 with optional semantic-profile
 * provenance. Immutable; unknown facts stay absent. Does not authorize cache access.
 */
public final class SourceDescriptor {

    public enum CompletenessStatus {
        COMPLETE,
        PARTIAL,
        UNKNOWN
    }

    private final String sourceRouteId;
    private final String thingOrServiceKind;
    private final String sanitizedParamDigest;
    private final Long rowsExamined;
    private final Long rowsReturned;
    private final Long rowsAvailable;
    private final CompletenessStatus completenessStatus;
    private final List<String> completenessReasons;
    private final String principalBinding;
    private final String executionScopeId;
    private final String producerToolCallId;
    private final String requestId;
    private final List<String> parentSourceCacheIds;
    /** U3S SP5 — absent when no semantic role was resolved for this source. */
    private final String propertyRoleRef;
    private final String unitRef;
    private final String grainRef;
    private final String cadenceRef;
    private final String semanticProfileId;
    private final String semanticProfileVersion;
    private final String semanticProfileDigest;
    /** Optional writer-declared column roles (CM-0); real visible column names or {@code null}. */
    private final String timeColumn;
    private final String valueColumn;
    /** Optional writer-declared subject identity (CM-4): the canonical Thing and property this table was read from. */
    private final String subjectThingName;
    private final String subjectPropertyName;

    private SourceDescriptor(Builder b) {
        this.sourceRouteId = b.sourceRouteId;
        this.thingOrServiceKind = b.thingOrServiceKind;
        this.sanitizedParamDigest = b.sanitizedParamDigest;
        this.rowsExamined = b.rowsExamined;
        this.rowsReturned = b.rowsReturned;
        this.rowsAvailable = b.rowsAvailable;
        this.completenessStatus = b.completenessStatus;
        this.completenessReasons = b.completenessReasons == null
                ? List.of()
                : Collections.unmodifiableList(List.copyOf(b.completenessReasons));
        this.principalBinding = b.principalBinding;
        this.executionScopeId = b.executionScopeId;
        this.producerToolCallId = b.producerToolCallId;
        this.requestId = b.requestId;
        this.parentSourceCacheIds = b.parentSourceCacheIds == null
                ? List.of()
                : Collections.unmodifiableList(List.copyOf(b.parentSourceCacheIds));
        this.propertyRoleRef = b.propertyRoleRef;
        this.unitRef = b.unitRef;
        this.grainRef = b.grainRef;
        this.cadenceRef = b.cadenceRef;
        this.semanticProfileId = b.semanticProfileId;
        this.semanticProfileVersion = b.semanticProfileVersion;
        this.semanticProfileDigest = b.semanticProfileDigest;
        this.timeColumn = b.timeColumn;
        this.valueColumn = b.valueColumn;
        this.subjectThingName = b.subjectThingName;
        this.subjectPropertyName = b.subjectPropertyName;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String sourceRouteId() {
        return sourceRouteId;
    }

    public String thingOrServiceKind() {
        return thingOrServiceKind;
    }

    public String sanitizedParamDigest() {
        return sanitizedParamDigest;
    }

    public Long rowsExamined() {
        return rowsExamined;
    }

    public Long rowsReturned() {
        return rowsReturned;
    }

    public Long rowsAvailable() {
        return rowsAvailable;
    }

    public CompletenessStatus completenessStatus() {
        return completenessStatus;
    }

    public List<String> completenessReasons() {
        return completenessReasons;
    }

    public String principalBinding() {
        return principalBinding;
    }

    public String executionScopeId() {
        return executionScopeId;
    }

    public String producerToolCallId() {
        return producerToolCallId;
    }

    public String requestId() {
        return requestId;
    }

    public List<String> parentSourceCacheIds() {
        return parentSourceCacheIds;
    }

    public String propertyRoleRef() {
        return propertyRoleRef;
    }

    public String unitRef() {
        return unitRef;
    }

    public String grainRef() {
        return grainRef;
    }

    public String cadenceRef() {
        return cadenceRef;
    }

    public String semanticProfileId() {
        return semanticProfileId;
    }

    public String semanticProfileVersion() {
        return semanticProfileVersion;
    }

    public String semanticProfileDigest() {
        return semanticProfileDigest;
    }

    /** Writer-declared time column role, or {@code null} when the writer declared none. */
    public String timeColumn() {
        return timeColumn;
    }

    /** Writer-declared numeric value column role, or {@code null} when the writer declared none. */
    public String valueColumn() {
        return valueColumn;
    }

    /** Writer-declared subject Thing (canonical name), or {@code null} when the writer declared none. */
    public String subjectThingName() {
        return subjectThingName;
    }

    /** Writer-declared subject property, or {@code null} when the writer declared none. */
    public String subjectPropertyName() {
        return subjectPropertyName;
    }

    /** Conservative derive composition: copy parent facts and append parent cache id. */
    public SourceDescriptor composeDerived(String derivedRouteId, String newParentCacheId) {
        Builder b = builder()
                .sourceRouteId(derivedRouteId != null ? derivedRouteId : sourceRouteId)
                .thingOrServiceKind(thingOrServiceKind)
                .sanitizedParamDigest(sanitizedParamDigest)
                .completenessStatus(completenessStatus == null ? CompletenessStatus.UNKNOWN : completenessStatus)
                .principalBinding(principalBinding)
                .executionScopeId(executionScopeId)
                .producerToolCallId(producerToolCallId)
                .requestId(requestId);
        if (!completenessReasons.isEmpty()) {
            b.completenessReasons(completenessReasons);
        }
        if (!parentSourceCacheIds.isEmpty()) {
            b.parentSourceCacheIds(parentSourceCacheIds);
        }
        if (newParentCacheId != null && !newParentCacheId.isBlank()) {
            b.addParentSourceCacheId(newParentCacheId.trim());
        }
        copySemanticProvenance(b, this);
        return b.build();
    }

    /**
     * Copy writer-declared column roles onto a rebuild of the <em>same</em> artifact's descriptor
     * (metadata decoration such as semantic provenance, parent lineage, completeness). New tables
     * never call this: a derived table drops parent roles unless its writer re-declares them.
     */
    public static void copyColumnRoles(Builder b, SourceDescriptor from) {
        if (b == null || from == null) {
            return;
        }
        b.timeColumn(from.timeColumn).valueColumn(from.valueColumn);
    }

    /**
     * Copy writer-declared subject identity onto a rebuild of the <em>same</em> artifact's descriptor
     * (same three decorations as {@link #copyColumnRoles}). New tables never call this: a derived or
     * multi-source result must not claim a single parent's subject.
     */
    public static void copySubjectIdentity(Builder b, SourceDescriptor from) {
        if (b == null || from == null) {
            return;
        }
        b.subjectThingName(from.subjectThingName).subjectPropertyName(from.subjectPropertyName);
    }

    /** Copy U3S SP5 provenance fields when present (derive / rebuild paths). Column roles and subject identity are not part of this. */
    public static void copySemanticProvenance(Builder b, SourceDescriptor from) {
        if (b == null || from == null) {
            return;
        }
        b.propertyRoleRef(from.propertyRoleRef)
                .unitRef(from.unitRef)
                .grainRef(from.grainRef)
                .cadenceRef(from.cadenceRef)
                .semanticProfileId(from.semanticProfileId)
                .semanticProfileVersion(from.semanticProfileVersion)
                .semanticProfileDigest(from.semanticProfileDigest);
    }

    public static final class Builder {
        private String sourceRouteId;
        private String thingOrServiceKind;
        private String sanitizedParamDigest;
        private Long rowsExamined;
        private Long rowsReturned;
        private Long rowsAvailable;
        private CompletenessStatus completenessStatus = CompletenessStatus.UNKNOWN;
        private List<String> completenessReasons;
        private String principalBinding;
        private String executionScopeId;
        private String producerToolCallId;
        private String requestId;
        private List<String> parentSourceCacheIds;
        private String propertyRoleRef;
        private String unitRef;
        private String grainRef;
        private String cadenceRef;
        private String semanticProfileId;
        private String semanticProfileVersion;
        private String semanticProfileDigest;
        private String timeColumn;
        private String valueColumn;
        private String subjectThingName;
        private String subjectPropertyName;

        public Builder sourceRouteId(String v) {
            this.sourceRouteId = v;
            return this;
        }

        public Builder thingOrServiceKind(String v) {
            this.thingOrServiceKind = v;
            return this;
        }

        public Builder sanitizedParamDigest(String v) {
            this.sanitizedParamDigest = v;
            return this;
        }

        public Builder rowsExamined(Long v) {
            this.rowsExamined = v;
            return this;
        }

        public Builder rowsReturned(Long v) {
            this.rowsReturned = v;
            return this;
        }

        public Builder rowsAvailable(Long v) {
            this.rowsAvailable = v;
            return this;
        }

        public Builder completenessStatus(CompletenessStatus v) {
            this.completenessStatus = Objects.requireNonNullElse(v, CompletenessStatus.UNKNOWN);
            return this;
        }

        public Builder completenessReasons(List<String> v) {
            this.completenessReasons = v;
            return this;
        }

        public Builder principalBinding(String v) {
            this.principalBinding = v;
            return this;
        }

        public Builder executionScopeId(String v) {
            this.executionScopeId = v;
            return this;
        }

        public Builder producerToolCallId(String v) {
            this.producerToolCallId = v;
            return this;
        }

        public Builder requestId(String v) {
            this.requestId = v;
            return this;
        }

        public Builder parentSourceCacheIds(List<String> v) {
            this.parentSourceCacheIds = v;
            return this;
        }

        public Builder addParentSourceCacheId(String cacheId) {
            if (cacheId == null || cacheId.isBlank()) {
                return this;
            }
            if (this.parentSourceCacheIds == null) {
                this.parentSourceCacheIds = new java.util.ArrayList<>();
            } else if (!(this.parentSourceCacheIds instanceof java.util.ArrayList)) {
                this.parentSourceCacheIds = new java.util.ArrayList<>(this.parentSourceCacheIds);
            }
            this.parentSourceCacheIds.add(cacheId);
            return this;
        }

        public Builder propertyRoleRef(String v) {
            this.propertyRoleRef = blankToNull(v);
            return this;
        }

        public Builder unitRef(String v) {
            this.unitRef = blankToNull(v);
            return this;
        }

        public Builder grainRef(String v) {
            this.grainRef = blankToNull(v);
            return this;
        }

        public Builder cadenceRef(String v) {
            this.cadenceRef = blankToNull(v);
            return this;
        }

        public Builder semanticProfileId(String v) {
            this.semanticProfileId = blankToNull(v);
            return this;
        }

        public Builder semanticProfileVersion(String v) {
            this.semanticProfileVersion = blankToNull(v);
            return this;
        }

        public Builder semanticProfileDigest(String v) {
            this.semanticProfileDigest = blankToNull(v);
            return this;
        }

        public Builder timeColumn(String v) {
            this.timeColumn = blankToNull(v);
            return this;
        }

        public Builder valueColumn(String v) {
            this.valueColumn = blankToNull(v);
            return this;
        }

        public Builder subjectThingName(String v) {
            this.subjectThingName = blankToNull(v);
            return this;
        }

        public Builder subjectPropertyName(String v) {
            this.subjectPropertyName = blankToNull(v);
            return this;
        }

        public SourceDescriptor build() {
            return new SourceDescriptor(this);
        }

        private static String blankToNull(String v) {
            if (v == null || v.isBlank()) {
                return null;
            }
            return v.trim();
        }
    }
}
