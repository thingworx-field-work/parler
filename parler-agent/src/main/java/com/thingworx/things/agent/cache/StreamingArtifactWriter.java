package com.thingworx.things.agent.cache;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Opaque streaming writer bound to one {@link FileArtifactCache}. Implements the writer-local
 * publish/abort mutex state machine from the opaque-kernel design.
 */
final class StreamingArtifactWriter implements ArtifactWriter {

    enum State {
        OPEN,
        CLOSED_UNPUBLISHED,
        PUBLISHED,
        ABORTED,
        FAILED
    }

    private final Object publishAbortLock = new Object();
    private final FileArtifactCache owner;
    private final ArtifactPayloadStore store;
    private final String relativePath;
    private final ArtifactAccessContext context;
    private final ArtifactCreateRequest request;
    private final ArtifactIoLimits limits;
    private final String artifactId;
    private final long createdEpochMilli;
    private final long startedNanos;
    private final OutputStream out;
    private final byte[] windowScratch;

    private long bytes;
    private long itemsAccumulated;
    private State state = State.OPEN;
    private ArtifactRecord finalized;
    private ArtifactRef publishedRef;

    StreamingArtifactWriter(FileArtifactCache owner, ArtifactPayloadStore store, String relativePath,
            OutputStream out, ArtifactAccessContext context, ArtifactCreateRequest request,
            ArtifactIoLimits limits, String artifactId, long createdEpochMilli) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.store = Objects.requireNonNull(store, "store");
        this.relativePath = Objects.requireNonNull(relativePath, "relativePath");
        this.out = Objects.requireNonNull(out, "out");
        this.context = Objects.requireNonNull(context, "context");
        this.request = Objects.requireNonNull(request, "request");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.artifactId = Objects.requireNonNull(artifactId, "artifactId");
        this.createdEpochMilli = createdEpochMilli;
        this.startedNanos = System.nanoTime();
        this.windowScratch = new byte[limits.maxInternalBufferBytes()];
    }

    Object publishAbortLock() {
        return publishAbortLock;
    }

    FileArtifactCache owner() {
        return owner;
    }

    ArtifactAccessContext context() {
        return context;
    }

    ArtifactRecord finalizedRecord() {
        return finalized;
    }

    ArtifactRef publishedRef() {
        return publishedRef;
    }

    void markPublished(ArtifactRef ref) {
        this.publishedRef = Objects.requireNonNull(ref, "ref");
        this.state = State.PUBLISHED;
    }

    void markFailed() {
        this.state = State.FAILED;
    }

    State state() {
        return state;
    }

    String relativePath() {
        return relativePath;
    }

    @Override
    public void writeBytes(byte[] chunk) throws ArtifactCacheException {
        ensureOpen();
        if (chunk == null || chunk.length == 0) {
            return;
        }
        recheckWallTime();
        int offset = 0;
        while (offset < chunk.length) {
            int slice = Math.min(windowScratch.length, chunk.length - offset);
            long nextBytes = bytes + slice;
            if (limits.exceedsBytes(nextBytes)) {
                failAndCleanupUnlocked();
                throw new ArtifactCacheException(ArtifactCacheFaultCode.IO_LIMIT_EXCEEDED,
                        "Write exceeds maxBytes");
            }
            try {
                out.write(chunk, offset, slice);
            } catch (IOException e) {
                failAndCleanupUnlocked();
                throw new ArtifactCacheException(ArtifactCacheFaultCode.PAYLOAD_FAULT,
                        "Payload write failed", e);
            }
            bytes = nextBytes;
            offset += slice;
            recheckWallTime();
        }
    }

    @Override
    public void addProducerItemDelta(long n) throws ArtifactCacheException {
        ensureOpen();
        if (n < 0) {
            failAndCleanupUnlocked();
            throw new ArtifactCacheException(ArtifactCacheFaultCode.INVALID_REQUEST,
                    "Producer item delta must be non-negative");
        }
        if (n == 0) {
            return;
        }
        long next;
        try {
            next = Math.addExact(itemsAccumulated, n);
        } catch (ArithmeticException e) {
            failAndCleanupUnlocked();
            throw new ArtifactCacheException(ArtifactCacheFaultCode.IO_LIMIT_EXCEEDED,
                    "Producer item delta overflow", e);
        }
        if (limits.exceedsItems(next)) {
            failAndCleanupUnlocked();
            throw new ArtifactCacheException(ArtifactCacheFaultCode.IO_LIMIT_EXCEEDED,
                    "Write exceeds maxItems");
        }
        itemsAccumulated = next;
        recheckWallTime();
    }

    @Override
    public boolean isClosedSuccessfully() {
        return state == State.CLOSED_UNPUBLISHED || state == State.PUBLISHED;
    }

    @Override
    public boolean isAborted() {
        return state == State.ABORTED;
    }

    @Override
    public boolean isFailed() {
        return state == State.FAILED;
    }

    @Override
    public boolean isPublished() {
        return state == State.PUBLISHED;
    }

    @Override
    public void abort() throws ArtifactCacheException {
        synchronized (publishAbortLock) {
            if (state == State.PUBLISHED || state == State.ABORTED || state == State.FAILED) {
                return;
            }
            closeStreamQuietly();
            state = State.ABORTED;
            store.bestEffortDelete(relativePath);
        }
    }

    @Override
    public void close() throws ArtifactCacheException {
        if (state != State.OPEN) {
            if (state == State.CLOSED_UNPUBLISHED || state == State.PUBLISHED) {
                return;
            }
            throw new ArtifactCacheException(ArtifactCacheFaultCode.INVALID_REQUEST,
                    "Writer is not open for successful close");
        }
        try {
            recheckWallTime();
            out.close();
            finalized = new ArtifactRecord.Builder()
                    .artifactId(artifactId)
                    .namespaceKey(context.namespaceKey())
                    .principalName(context.principalName())
                    .opaqueScopeId(context.opaqueScopeId())
                    .relativePath(relativePath)
                    .kind(request.kind())
                    .schema(request.typedPasswordProof())
                    .schemaHint(request.schemaHint())
                    .itemCount(itemsAccumulated)
                    .byteCount(bytes)
                    .createdEpochMilli(createdEpochMilli)
                    .producer(request.producer())
                    .lineage(request.lineage())
                    .complete(request.complete())
                    .truncated(request.truncated())
                    .sampled(request.sampled())
                    .logicalExpiryEpochMilli(request.logicalExpiryEpochMilli())
                    .build();
            state = State.CLOSED_UNPUBLISHED;
        } catch (ArtifactCacheException e) {
            failAndCleanupUnlocked();
            throw e;
        } catch (IOException e) {
            failAndCleanupUnlocked();
            throw new ArtifactCacheException(ArtifactCacheFaultCode.PAYLOAD_FAULT,
                    "Payload close failed", e);
        }
    }

    private void ensureOpen() throws ArtifactCacheException {
        if (state != State.OPEN) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.INVALID_REQUEST,
                    "Writer is not open");
        }
    }

    private void recheckWallTime() throws ArtifactCacheException {
        long elapsed = (System.nanoTime() - startedNanos) / 1_000_000L;
        if (limits.exceedsWallTime(elapsed)) {
            failAndCleanupUnlocked();
            throw new ArtifactCacheException(ArtifactCacheFaultCode.IO_LIMIT_EXCEEDED,
                    "Write exceeds maxWallTimeMillis");
        }
    }

    private void failAndCleanupUnlocked() {
        if (state == State.PUBLISHED || state == State.ABORTED || state == State.FAILED) {
            return;
        }
        closeStreamQuietly();
        state = State.FAILED;
        store.bestEffortDelete(relativePath);
    }

    void failAndCleanupUnderLock() {
        synchronized (publishAbortLock) {
            if (state == State.PUBLISHED || state == State.ABORTED || state == State.FAILED) {
                return;
            }
            closeStreamQuietly();
            state = State.FAILED;
            store.bestEffortDelete(relativePath);
        }
    }

    private void closeStreamQuietly() {
        try {
            out.close();
        } catch (Exception ignored) {
            // best-effort
        }
    }
}
