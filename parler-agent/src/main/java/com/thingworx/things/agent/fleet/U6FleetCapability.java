package com.thingworx.things.agent.fleet;

import java.util.Objects;

import com.thingworx.things.agent.analysis.AnalysisMethodDescriptor;
import com.thingworx.things.agent.analysis.AnalysisOperation;

/**
 * Frozen U6 fleet capability descriptor (FRC-0). Shape only — not production admission or
 * model-visible advertisement (those land with FRC-1/FRC-4 consumers under D3 packaging rules).
 */
public final class U6FleetCapability {

    private final String methodId;
    private final String version;
    private final AnalysisOperation operation;
    private final String complexityNote;
    private final int maxCohortMembers;
    private final boolean enabledByDefault;

    private U6FleetCapability(Builder b) {
        this.methodId = requireNonBlank(b.methodId, "methodId");
        this.version = requireNonBlank(b.version, "version");
        this.operation = Objects.requireNonNull(b.operation, "operation");
        if (!operation.isU6()) {
            throw new IllegalArgumentException("U6 fleet capability requires a U6 AnalysisOperation");
        }
        this.complexityNote = requireNonBlank(b.complexityNote, "complexityNote");
        if (b.maxCohortMembers <= 0) {
            throw new IllegalArgumentException("maxCohortMembers must be > 0");
        }
        this.maxCohortMembers = b.maxCohortMembers;
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

    public int maxCohortMembers() {
        return maxCohortMembers;
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

    public static final class Builder {
        private String methodId;
        private String version = "1";
        private AnalysisOperation operation = AnalysisOperation.FLEET_BENCHMARK;
        private String complexityNote;
        private int maxCohortMembers = 500;
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

        public Builder maxCohortMembers(int v) {
            this.maxCohortMembers = v;
            return this;
        }

        public Builder enabledByDefault(boolean v) {
            this.enabledByDefault = v;
            return this;
        }

        public U6FleetCapability build() {
            return new U6FleetCapability(this);
        }
    }
}
