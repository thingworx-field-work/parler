package com.thingworx.things.agent.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.source.SourceDescriptor;

/**
 * Server-authored compact G19 assessment (EG4). Raw rows never enter this object. Completeness
 * reuses {@link SourceDescriptor.CompletenessStatus} — no second completeness enum.
 */
public final class EvidenceAssessment {

    private final EvidenceStatus status;
    private final SourceDescriptor.CompletenessStatus completeness;
    private final String coverage;
    private final long n;
    private final List<String> quality;
    private final List<String> applicability;
    private final List<String> warnings;
    private final List<String> conflicts;
    private final List<String> sourceCacheIds;
    private final EvidenceMethodRef method;

    private EvidenceAssessment(Builder b) {
        this.status = Objects.requireNonNull(b.status, "status");
        this.completeness = b.completeness == null
                ? SourceDescriptor.CompletenessStatus.UNKNOWN
                : b.completeness;
        this.coverage = blankToNull(b.coverage);
        this.n = Math.max(0L, b.n);
        this.quality = copyOmitEmpty(b.quality);
        this.applicability = copyOmitEmpty(b.applicability);
        this.warnings = copyOmitEmpty(b.warnings);
        this.conflicts = copyOmitEmpty(b.conflicts);
        this.sourceCacheIds = copyOmitEmpty(b.sourceCacheIds);
        this.method = b.method;
    }

    public static Builder builder() {
        return new Builder();
    }

    public EvidenceStatus status() {
        return status;
    }

    public SourceDescriptor.CompletenessStatus completeness() {
        return completeness;
    }

    public String coverage() {
        return coverage;
    }

    public long n() {
        return n;
    }

    public List<String> quality() {
        return quality;
    }

    public List<String> applicability() {
        return applicability;
    }

    public List<String> warnings() {
        return warnings;
    }

    public List<String> conflicts() {
        return conflicts;
    }

    public List<String> sourceCacheIds() {
        return sourceCacheIds;
    }

    public EvidenceMethodRef method() {
        return method;
    }

    public boolean hasApplicability(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        for (String a : applicability) {
            if (token.equalsIgnoreCase(a)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasQuality(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        for (String q : quality) {
            if (token.equalsIgnoreCase(q)) {
                return true;
            }
        }
        return false;
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

    public static final class Builder {
        private EvidenceStatus status = EvidenceStatus.INSUFFICIENT_EVIDENCE;
        private SourceDescriptor.CompletenessStatus completeness = SourceDescriptor.CompletenessStatus.UNKNOWN;
        private String coverage;
        private long n;
        private List<String> quality = List.of();
        private List<String> applicability = List.of();
        private List<String> warnings = List.of();
        private List<String> conflicts = List.of();
        private List<String> sourceCacheIds = List.of();
        private EvidenceMethodRef method;

        public Builder status(EvidenceStatus v) {
            this.status = v;
            return this;
        }

        public Builder completeness(SourceDescriptor.CompletenessStatus v) {
            this.completeness = v;
            return this;
        }

        public Builder coverage(String v) {
            this.coverage = v;
            return this;
        }

        public Builder n(long v) {
            this.n = v;
            return this;
        }

        public Builder quality(List<String> v) {
            this.quality = v;
            return this;
        }

        public Builder applicability(List<String> v) {
            this.applicability = v;
            return this;
        }

        public Builder warnings(List<String> v) {
            this.warnings = v;
            return this;
        }

        public Builder conflicts(List<String> v) {
            this.conflicts = v;
            return this;
        }

        public Builder sourceCacheIds(List<String> v) {
            this.sourceCacheIds = v;
            return this;
        }

        public Builder method(EvidenceMethodRef v) {
            this.method = v;
            return this;
        }

        public EvidenceAssessment build() {
            return new EvidenceAssessment(this);
        }
    }
}
