package com.thingworx.things.agent.analysis;

import java.util.Objects;

/**
 * Frozen U5 method capability descriptor (DIK-0). Shape only — not production admission,
 * advertisement, or executable binding (those land with DIK-2/3/4 consumers).
 */
public final class U5MethodCapability {

    private final String methodId;
    private final String version;
    private final AnalysisOperation operation;
    private final String complexityNote;
    private final long maxEligiblePoints;
    private final long maxMaterializedPairs;
    private final boolean enabledByDefault;

    private U5MethodCapability(Builder b) {
        this.methodId = requireNonBlank(b.methodId, "methodId");
        this.version = requireNonBlank(b.version, "version");
        this.operation = Objects.requireNonNull(b.operation, "operation");
        if (!operation.isU5()) {
            throw new IllegalArgumentException("U5 capability requires a U5 AnalysisOperation");
        }
        this.complexityNote = requireNonBlank(b.complexityNote, "complexityNote");
        this.maxEligiblePoints = requirePositive(b.maxEligiblePoints, "maxEligiblePoints");
        this.maxMaterializedPairs = b.maxMaterializedPairs;
        if (maxMaterializedPairs < 0L) {
            throw new IllegalArgumentException("maxMaterializedPairs must be non-negative");
        }
        this.enabledByDefault = b.enabledByDefault;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String methodId() {
        return methodId;
    }

    public String version() {
        return version;
    }

    public AnalysisOperation operation() {
        return operation;
    }

    public String complexityNote() {
        return complexityNote;
    }

    public long maxEligiblePoints() {
        return maxEligiblePoints;
    }

    public long maxMaterializedPairs() {
        return maxMaterializedPairs;
    }

    public boolean enabledByDefault() {
        return enabledByDefault;
    }

    public AnalysisMethodDescriptor toMethodDescriptor(String profileDigest) {
        return AnalysisMethodDescriptor.builder()
                .id(methodId)
                .version(version)
                .profileDigest(profileDigest)
                .operation(operation)
                .build();
    }

    private static String requireNonBlank(String s, String name) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return s.trim();
    }

    private static long requirePositive(long v, String name) {
        if (v <= 0L) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
        return v;
    }

    public static final class Builder {
        private String methodId;
        private String version = "1";
        private AnalysisOperation operation;
        private String complexityNote;
        private long maxEligiblePoints = 100_000L;
        private long maxMaterializedPairs;
        private boolean enabledByDefault = true;

        public Builder methodId(String v) {
            this.methodId = v;
            return this;
        }

        public Builder version(String v) {
            this.version = v;
            return this;
        }

        public Builder operation(AnalysisOperation v) {
            this.operation = v;
            return this;
        }

        public Builder complexityNote(String v) {
            this.complexityNote = v;
            return this;
        }

        public Builder maxEligiblePoints(long v) {
            this.maxEligiblePoints = v;
            return this;
        }

        public Builder maxMaterializedPairs(long v) {
            this.maxMaterializedPairs = v;
            return this;
        }

        public Builder enabledByDefault(boolean v) {
            this.enabledByDefault = v;
            return this;
        }

        public U5MethodCapability build() {
            return new U5MethodCapability(this);
        }
    }
}
