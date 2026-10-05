package com.thingworx.things.agent.cache;

/**
 * Bounded ArtifactCache fault. Never carries repository-relative paths.
 *
 * <p>This is an unchecked signal so typed cache faults can cross legacy helper APIs that do not
 * declare checked exceptions. Cache interfaces retain explicit {@code throws} declarations as
 * part of their contract. Callers must distinguish {@link ArtifactCacheFaultCode#CACHE_MISS}
 * from every non-miss fault rather than treating an uncaught fault as absence.
 */
public final class ArtifactCacheException extends RuntimeException {

    private final ArtifactCacheFaultCode code;

    public ArtifactCacheException(ArtifactCacheFaultCode code, String message) {
        super(message);
        this.code = code == null ? ArtifactCacheFaultCode.INTERNAL : code;
    }

    public ArtifactCacheException(ArtifactCacheFaultCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code == null ? ArtifactCacheFaultCode.INTERNAL : code;
    }

    public ArtifactCacheFaultCode code() {
        return code;
    }
}
