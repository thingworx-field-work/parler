package com.thingworx.things.agent.analysis;

import java.util.Objects;

/**
 * Non-advertised method descriptor <em>shape</em> frozen in TQJ-0 (B8). This is not production
 * admission, tool advertisement, or an executable binding — those land with real TQJ-2/3/4
 * consumers.
 */
public final class AnalysisMethodDescriptor {

    private final String id;
    private final String version;
    private final String profileDigest;
    private final AnalysisOperation operation;

    private AnalysisMethodDescriptor(Builder b) {
        this.id = requireNonBlank(b.id, "id");
        this.version = requireNonBlank(b.version, "version");
        this.profileDigest = blankToNull(b.profileDigest);
        this.operation = Objects.requireNonNull(b.operation, "operation");
    }

    public static Builder builder() {
        return new Builder();
    }

    public String id() {
        return id;
    }

    public String version() {
        return version;
    }

    public String profileDigest() {
        return profileDigest;
    }

    public AnalysisOperation operation() {
        return operation;
    }

    private static String requireNonBlank(String s, String name) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return s.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public static final class Builder {
        private String id;
        private String version = "1";
        private String profileDigest;
        private AnalysisOperation operation;

        public Builder id(String v) {
            this.id = v;
            return this;
        }

        public Builder version(String v) {
            this.version = v;
            return this;
        }

        public Builder profileDigest(String v) {
            this.profileDigest = v;
            return this;
        }

        public Builder operation(AnalysisOperation v) {
            this.operation = v;
            return this;
        }

        public AnalysisMethodDescriptor build() {
            return new AnalysisMethodDescriptor(this);
        }
    }
}
