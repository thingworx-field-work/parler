package com.thingworx.things.agent.cache;

import java.io.OutputStream;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Per-FileRepository-Thing cache: payload store + current-JVM index. Close-before-index publication
 * under the writer publish/abort mutex; opaque handle returned only after index insert wins.
 * Construction is package-private — Core owns instances via {@link FileArtifactCacheRegistry} /
 * {@link ArtifactCacheCore}.
 */
public final class FileArtifactCache implements ArtifactCache {

    private static final int MAX_CREATE_ATTEMPTS = 3;

    private final String repositoryThingName;
    private final ArtifactPayloadStore store;
    private final ConcurrentHashMap<IndexKey, ArtifactRecord> index = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Supplier<String> artifactIdSupplier;
    private volatile boolean shutDown;

    /**
     * One-shot Core/U2 restore bridge: next {@link #create} on this JVM uses this well-formed id
     * instead of {@link #artifactIdSupplier}. Cleared after consume. Used only for historical
     * compact-evidence restore until U2 M4 retires live-cache resurrection.
     */
    private static final ThreadLocal<String> NEXT_FORCED_ARTIFACT_ID = new ThreadLocal<>();

    /** Package-private: force the next create id on the calling thread (restore bridge). */
    static void forceNextArtifactId(String artifactId) {
        if (artifactId == null || !ArtifactRef.isWellFormedId(artifactId)) {
            throw new IllegalArgumentException("forced artifactId must be a well-formed lowercase UUID");
        }
        NEXT_FORCED_ARTIFACT_ID.set(artifactId);
    }

    static void clearForcedArtifactId() {
        NEXT_FORCED_ARTIFACT_ID.remove();
    }

    FileArtifactCache(String repositoryThingName, ArtifactPayloadStore store) {
        this(repositoryThingName, store, Clock.systemUTC(), ArtifactRef::newArtifactId);
    }

    FileArtifactCache(String repositoryThingName, ArtifactPayloadStore store, Clock clock,
            Supplier<String> artifactIdSupplier) {
        this.repositoryThingName = Objects.requireNonNull(repositoryThingName, "repositoryThingName");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.artifactIdSupplier = Objects.requireNonNull(artifactIdSupplier, "artifactIdSupplier");
    }

    public String repositoryThingName() {
        return repositoryThingName;
    }

    /** Reject new operations and drop current-JVM index metadata. Does not delete payloads. */
    void shutdown() {
        shutDown = true;
        index.clear();
    }

    boolean isShutDown() {
        return shutDown;
    }

    @Override
    public ArtifactWriter create(ArtifactCreateRequest request, ArtifactAccessContext context,
            ArtifactIoLimits limits) throws ArtifactCacheException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(limits, "limits");
        requireNotShutDown();
        PasswordSchemaPreflight.rejectIfPasswordPresent(request.typedPasswordProof());

        ArtifactCacheException lastCollision = null;
        for (int attempt = 1; attempt <= MAX_CREATE_ATTEMPTS; attempt++) {
            String forced = NEXT_FORCED_ARTIFACT_ID.get();
            String artifactId;
            if (forced != null) {
                NEXT_FORCED_ARTIFACT_ID.remove();
                artifactId = forced;
            } else {
                artifactId = artifactIdSupplier.get();
            }
            if (!ArtifactRef.isWellFormedId(artifactId)) {
                throw new ArtifactCacheException(ArtifactCacheFaultCode.INTERNAL,
                        "Core artifact id is not well-formed");
            }
            String relativePath = ArtifactPathLayout.buildRelativePath(
                    context.principalName(),
                    ArtifactPathLayout.utcDateOfEpochMilli(clock.millis()),
                    artifactId);
            try {
                OutputStream out = store.exclusiveCreate(relativePath);
                try {
                    return new StreamingArtifactWriter(this, store, relativePath, out, context, request,
                            limits, artifactId, clock.millis());
                } catch (RuntimeException e) {
                    try {
                        out.close();
                    } catch (Exception ignored) {
                        // best-effort
                    }
                    store.bestEffortDelete(relativePath);
                    throw e;
                }
            } catch (ArtifactCacheException e) {
                if (e.code() != ArtifactCacheFaultCode.CREATE_COLLISION) {
                    throw e;
                }
                lastCollision = e;
            }
        }
        throw new ArtifactCacheException(ArtifactCacheFaultCode.CREATE_COLLISION,
                "Exclusive create failed after " + MAX_CREATE_ATTEMPTS + " fresh-ID attempts",
                lastCollision);
    }

    @Override
    public ArtifactRef publish(ArtifactWriter writer, ArtifactAccessContext context)
            throws ArtifactCacheException {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(context, "context");
        requireNotShutDown();
        if (!(writer instanceof StreamingArtifactWriter)) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.INVALID_REQUEST, "Unknown writer type");
        }
        StreamingArtifactWriter saw = (StreamingArtifactWriter) writer;
        if (saw.owner() != this) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.INVALID_REQUEST,
                    "Writer was not created by this cache instance");
        }
        if (!saw.context().sameNamespace(context)) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.INVALID_REQUEST,
                    "Publish context does not match writer namespace");
        }
        synchronized (saw.publishAbortLock()) {
            if (saw.state() == StreamingArtifactWriter.State.PUBLISHED) {
                return saw.publishedRef();
            }
            if (saw.state() == StreamingArtifactWriter.State.OPEN) {
                throw new ArtifactCacheException(ArtifactCacheFaultCode.INVALID_REQUEST,
                        "Writer must close successfully before publish");
            }
            if (saw.state() == StreamingArtifactWriter.State.ABORTED
                    || saw.state() == StreamingArtifactWriter.State.FAILED) {
                throw new ArtifactCacheException(ArtifactCacheFaultCode.INVALID_REQUEST,
                        "Writer is not publishable");
            }
            if (saw.state() != StreamingArtifactWriter.State.CLOSED_UNPUBLISHED) {
                throw new ArtifactCacheException(ArtifactCacheFaultCode.INTERNAL,
                        "Unexpected writer state");
            }
            ArtifactRecord record = saw.finalizedRecord();
            IndexKey key = new IndexKey(record.namespaceKey(), record.artifactId());
            ArtifactRecord prior = index.putIfAbsent(key, record);
            if (prior != null) {
                saw.markFailed();
                store.bestEffortDelete(saw.relativePath());
                throw new ArtifactCacheException(ArtifactCacheFaultCode.CREATE_COLLISION,
                        "Index already contains artifact id");
            }
            ArtifactRef ref = ArtifactRef.ofValidated(record.artifactId());
            saw.markPublished(ref);
            return ref;
        }
    }

    @Override
    public ArtifactReader open(ArtifactRef ref, ArtifactAccessContext context, ArtifactIoLimits limits)
            throws ArtifactCacheException {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(limits, "limits");
        requireNotShutDown();
        if (!ArtifactRef.isWellFormedId(ref.artifactId())) {
            throw miss();
        }
        IndexKey key = new IndexKey(context.namespaceKey(), ref.artifactId());
        ArtifactRecord record = index.get(key);
        if (record == null) {
            throw miss();
        }
        if (!record.namespaceKey().equals(context.namespaceKey())) {
            throw miss();
        }
        if (isExpired(record)) {
            index.remove(key, record);
            throw miss();
        }
        java.io.InputStream in = null;
        try {
            in = store.openRead(record.relativePath());
            return new IndexedArtifactReader(record, in, limits);
        } catch (ArtifactCacheException e) {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // best-effort
                }
            }
            throw e;
        }
    }

    @Override
    public void invalidate(ArtifactRef ref, ArtifactAccessContext context) {
        if (ref == null || context == null || shutDown) {
            return;
        }
        index.remove(new IndexKey(context.namespaceKey(), ref.artifactId()));
    }

    @Override
    public void invalidateScope(ArtifactAccessContext context) {
        if (context == null || shutDown) {
            return;
        }
        String ns = context.namespaceKey();
        index.keySet().removeIf(k -> k.namespaceKey.equals(ns));
    }

    private void requireNotShutDown() throws ArtifactCacheException {
        if (shutDown) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.INVALID_REQUEST,
                    "Artifact cache is shut down");
        }
    }

    private boolean isExpired(ArtifactRecord record) {
        return clock.millis() >= record.logicalExpiryEpochMilli();
    }

    private static ArtifactCacheException miss() {
        return new ArtifactCacheException(ArtifactCacheFaultCode.CACHE_MISS,
                "Artifact not found in current-JVM cache; re-fetch or recompute");
    }

    /**
     * §3.6 logical liveness: whether this artifact is usable right now in {@code context}'s principal/scope
     * namespace.
     *
     * <p>Index containment is not liveness. {@link #open} validates namespace, TTL, and — through the record it
     * hands the reader — completeness; a key can remain in the map past its expiry until some unrelated lookup
     * evicts it, so a containment check would report an expired or half-written artifact as usable. This mirrors
     * {@code open}'s validations exactly, including the same lazy eviction of an expired record, and stops short
     * of the payload read: no repository scan, no reader, no decode.
     */
    boolean isLogicallyLive(ArtifactAccessContext context, ArtifactRef ref) {
        if (context == null || ref == null || shutDown) {
            return false;
        }
        if (!ArtifactRef.isWellFormedId(ref.artifactId())) {
            return false;
        }
        IndexKey key = new IndexKey(context.namespaceKey(), ref.artifactId());
        ArtifactRecord record = index.get(key);
        if (record == null || !record.namespaceKey().equals(context.namespaceKey())) {
            return false;
        }
        if (isExpired(record)) {
            index.remove(key, record);
            return false;
        }
        return record.complete();
    }

    /** Package-private test helper: whether a path still has an index entry (not a public API). */
    boolean isIndexed(ArtifactAccessContext context, ArtifactRef ref) {
        if (context == null || ref == null || shutDown) {
            return false;
        }
        return index.containsKey(new IndexKey(context.namespaceKey(), ref.artifactId()));
    }

    /** Package-private test helper: payload still present after logical removal. */
    boolean payloadExists(String relativePath) {
        return store.exists(relativePath);
    }

    private static final class IndexKey {
        private final String namespaceKey;
        private final String artifactId;

        private IndexKey(String namespaceKey, String artifactId) {
            this.namespaceKey = namespaceKey;
            this.artifactId = artifactId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof IndexKey)) {
                return false;
            }
            IndexKey that = (IndexKey) o;
            return namespaceKey.equals(that.namespaceKey) && artifactId.equals(that.artifactId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(namespaceKey, artifactId);
        }
    }
}
