package com.thingworx.things.agent.cache;

import java.util.Objects;

/**
 * Immutable current-JVM artifact metadata. Public surface omits the repository-relative path;
 * package-private accessors supply it to the payload store.
 */
public final class ArtifactRecord {

    private final String artifactId;
    private final String namespaceKey;
    private final String principalName;
    private final String opaqueScopeId;
    private final String relativePath;
    private final ArtifactKind kind;
    private final ArtifactSchemaNode schema;
    private final String schemaHint;
    private final long itemCount;
    private final long byteCount;
    private final long createdEpochMilli;
    private final String producer;
    private final String lineage;
    private final boolean complete;
    private final boolean truncated;
    private final boolean sampled;
    private final long logicalExpiryEpochMilli;

    ArtifactRecord(Builder b) {
        this.artifactId = Objects.requireNonNull(b.artifactId, "artifactId");
        this.namespaceKey = Objects.requireNonNull(b.namespaceKey, "namespaceKey");
        this.principalName = Objects.requireNonNull(b.principalName, "principalName");
        this.opaqueScopeId = Objects.requireNonNull(b.opaqueScopeId, "opaqueScopeId");
        this.relativePath = Objects.requireNonNull(b.relativePath, "relativePath");
        this.kind = Objects.requireNonNull(b.kind, "kind");
        this.schema = Objects.requireNonNull(b.schema, "schema");
        this.schemaHint = b.schemaHint == null ? "" : b.schemaHint;
        this.itemCount = b.itemCount;
        this.byteCount = b.byteCount;
        this.createdEpochMilli = b.createdEpochMilli;
        this.producer = b.producer == null ? "" : b.producer;
        this.lineage = b.lineage == null ? "" : b.lineage;
        this.complete = b.complete;
        this.truncated = b.truncated;
        this.sampled = b.sampled;
        this.logicalExpiryEpochMilli = b.logicalExpiryEpochMilli;
    }

    public String artifactId() {
        return artifactId;
    }

    /** Index internal — not part of the U2-facing record view. */
    String namespaceKey() {
        return namespaceKey;
    }

    public ArtifactKind kind() {
        return kind;
    }

    /** Typed PASSWORD proof captured at create (metadata; not a decode program). */
    public ArtifactSchemaNode schema() {
        return schema;
    }

    public String schemaHint() {
        return schemaHint;
    }

    public long itemCount() {
        return itemCount;
    }

    public long byteCount() {
        return byteCount;
    }

    public long createdEpochMilli() {
        return createdEpochMilli;
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

    String relativePath() {
        return relativePath;
    }

    String principalName() {
        return principalName;
    }

    String opaqueScopeId() {
        return opaqueScopeId;
    }

    static final class Builder {
        private String artifactId;
        private String namespaceKey;
        private String principalName;
        private String opaqueScopeId;
        private String relativePath;
        private ArtifactKind kind;
        private ArtifactSchemaNode schema;
        private String schemaHint = "";
        private long itemCount;
        private long byteCount;
        private long createdEpochMilli;
        private String producer = "";
        private String lineage = "";
        private boolean complete = true;
        private boolean truncated;
        private boolean sampled;
        private long logicalExpiryEpochMilli = Long.MAX_VALUE;

        Builder artifactId(String artifactId) {
            this.artifactId = artifactId;
            return this;
        }

        Builder namespaceKey(String namespaceKey) {
            this.namespaceKey = namespaceKey;
            return this;
        }

        Builder principalName(String principalName) {
            this.principalName = principalName;
            return this;
        }

        Builder opaqueScopeId(String opaqueScopeId) {
            this.opaqueScopeId = opaqueScopeId;
            return this;
        }

        Builder relativePath(String relativePath) {
            this.relativePath = relativePath;
            return this;
        }

        Builder kind(ArtifactKind kind) {
            this.kind = kind;
            return this;
        }

        Builder schema(ArtifactSchemaNode schema) {
            this.schema = schema;
            return this;
        }

        Builder schemaHint(String schemaHint) {
            this.schemaHint = schemaHint;
            return this;
        }

        Builder itemCount(long itemCount) {
            this.itemCount = itemCount;
            return this;
        }

        Builder byteCount(long byteCount) {
            this.byteCount = byteCount;
            return this;
        }

        Builder createdEpochMilli(long createdEpochMilli) {
            this.createdEpochMilli = createdEpochMilli;
            return this;
        }

        Builder producer(String producer) {
            this.producer = producer;
            return this;
        }

        Builder lineage(String lineage) {
            this.lineage = lineage;
            return this;
        }

        Builder complete(boolean complete) {
            this.complete = complete;
            return this;
        }

        Builder truncated(boolean truncated) {
            this.truncated = truncated;
            return this;
        }

        Builder sampled(boolean sampled) {
            this.sampled = sampled;
            return this;
        }

        Builder logicalExpiryEpochMilli(long logicalExpiryEpochMilli) {
            this.logicalExpiryEpochMilli = logicalExpiryEpochMilli;
            return this;
        }

        ArtifactRecord build() {
            return new ArtifactRecord(this);
        }
    }
}
