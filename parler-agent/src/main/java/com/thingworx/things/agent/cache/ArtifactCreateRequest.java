package com.thingworx.things.agent.cache;

import java.util.Objects;

/** Validated create request. Typed PASSWORD proof MUST be resolved before create. */
public final class ArtifactCreateRequest {

    private final ArtifactKind kind;
    private final ArtifactSchemaNode typedPasswordProof;
    private final String producer;
    private final String lineage;
    private final boolean complete;
    private final boolean truncated;
    private final boolean sampled;
    private final long logicalExpiryEpochMilli;
    private final String schemaHint;

    private ArtifactCreateRequest(Builder b) {
        this.kind = Objects.requireNonNull(b.kind, "kind");
        this.typedPasswordProof = Objects.requireNonNull(b.typedPasswordProof, "typedPasswordProof");
        this.producer = b.producer == null ? "" : b.producer;
        this.lineage = b.lineage == null ? "" : b.lineage;
        this.complete = b.complete;
        this.truncated = b.truncated;
        this.sampled = b.sampled;
        this.logicalExpiryEpochMilli = b.logicalExpiryEpochMilli;
        this.schemaHint = b.schemaHint == null ? "" : b.schemaHint;
    }

    public static Builder builder(ArtifactKind kind, ArtifactSchemaNode typedPasswordProof) {
        return new Builder(kind, typedPasswordProof);
    }

    public ArtifactKind kind() {
        return kind;
    }

    /** Required typed schema/proof walked for PASSWORD-before-create. */
    public ArtifactSchemaNode typedPasswordProof() {
        return typedPasswordProof;
    }

    public String producer() {
        return producer;
    }

    public String lineage() {
        return lineage;
    }

    public boolean complete() {
        return complete;
    }

    public boolean truncated() {
        return truncated;
    }

    public boolean sampled() {
        return sampled;
    }

    public long logicalExpiryEpochMilli() {
        return logicalExpiryEpochMilli;
    }

    /** Optional structure/format hint; not walked for PASSWORD. */
    public String schemaHint() {
        return schemaHint;
    }

    public static final class Builder {
        private final ArtifactKind kind;
        private final ArtifactSchemaNode typedPasswordProof;
        private String producer = "";
        private String lineage = "";
        private boolean complete = true;
        private boolean truncated = false;
        private boolean sampled = false;
        private long logicalExpiryEpochMilli = Long.MAX_VALUE;
        private String schemaHint = "";

        private Builder(ArtifactKind kind, ArtifactSchemaNode typedPasswordProof) {
            this.kind = kind;
            this.typedPasswordProof = typedPasswordProof;
        }

        public Builder producer(String producer) {
            this.producer = producer;
            return this;
        }

        public Builder lineage(String lineage) {
            this.lineage = lineage;
            return this;
        }

        public Builder complete(boolean complete) {
            this.complete = complete;
            return this;
        }

        public Builder truncated(boolean truncated) {
            this.truncated = truncated;
            return this;
        }

        public Builder sampled(boolean sampled) {
            this.sampled = sampled;
            return this;
        }

        public Builder logicalExpiryEpochMilli(long logicalExpiryEpochMilli) {
            this.logicalExpiryEpochMilli = logicalExpiryEpochMilli;
            return this;
        }

        public Builder schemaHint(String schemaHint) {
            this.schemaHint = schemaHint;
            return this;
        }

        public ArtifactCreateRequest build() {
            return new ArtifactCreateRequest(this);
        }
    }
}
