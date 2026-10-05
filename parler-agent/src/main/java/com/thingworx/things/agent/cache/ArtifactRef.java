package com.thingworx.things.agent.cache;

import java.util.Objects;
import java.util.UUID;

/**
 * Opaque Core-generated artifact identity for current-JVM index lookup. Not the public wire
 * {@code cacheId} / TOKEN vocabulary (U2).
 */
public final class ArtifactRef {

    private final String artifactId;

    ArtifactRef(String artifactId) {
        this.artifactId = Objects.requireNonNull(artifactId, "artifactId");
        if (artifactId.isEmpty()) {
            throw new IllegalArgumentException("artifactId must be non-empty");
        }
    }

    /** Lowercase UUID text (36 chars with hyphens) when minted by Core. */
    public String artifactId() {
        return artifactId;
    }

    static ArtifactRef ofValidated(String artifactId) {
        return new ArtifactRef(artifactId);
    }

    static String newArtifactId() {
        return UUID.randomUUID().toString().toLowerCase();
    }

    static boolean isWellFormedId(String artifactId) {
        if (artifactId == null || artifactId.length() != 36) {
            return false;
        }
        for (int i = 0; i < artifactId.length(); i++) {
            char c = artifactId.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') {
                    return false;
                }
            } else if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ArtifactRef)) {
            return false;
        }
        return artifactId.equals(((ArtifactRef) o).artifactId);
    }

    @Override
    public int hashCode() {
        return artifactId.hashCode();
    }

    @Override
    public String toString() {
        return "ArtifactRef{" + artifactId + '}';
    }
}
