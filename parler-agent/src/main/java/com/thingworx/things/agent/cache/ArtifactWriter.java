package com.thingworx.things.agent.cache;

/**
 * Opaque streaming writer for one exclusive-created payload. Must {@link #close()} successfully
 * before publish, or {@link #abort()} to cancel.
 */
public interface ArtifactWriter extends AutoCloseable {

    /** Append opaque bytes. Empty chunk is a no-op. */
    void writeBytes(byte[] chunk) throws ArtifactCacheException;

    /**
     * Monotonic producer item accounting. Checked-add; never-called yields itemCount 0 at close.
     */
    void addProducerItemDelta(long n) throws ArtifactCacheException;

    /** True after successful {@link #close()} or after successful publish. */
    boolean isClosedSuccessfully();

    boolean isAborted();

    boolean isFailed();

    boolean isPublished();

    /** Cancel/abandon under the writer publish/abort mutex. */
    void abort() throws ArtifactCacheException;

    @Override
    void close() throws ArtifactCacheException;
}
