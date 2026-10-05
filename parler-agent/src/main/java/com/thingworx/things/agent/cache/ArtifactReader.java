package com.thingworx.things.agent.cache;

/**
 * Opaque streaming reader over an indexed artifact. Metadata via {@link #record()} — no path or
 * namespace leakage. Consume payload through {@link #readBytes(int)}.
 */
public interface ArtifactReader extends AutoCloseable {

    ArtifactRecord record();

    /**
     * Read up to {@code maxLen} opaque payload bytes under storage limits. {@code maxLen} must be
     * positive. Empty array is returned only at natural EOF after the required terminal probe.
     */
    byte[] readBytes(int maxLen) throws ArtifactCacheException;

    @Override
    void close() throws ArtifactCacheException;
}
