package com.thingworx.things.agent.cache;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only store that counts exclusive-create attempts. */
final class CountingPayloadStore implements ArtifactPayloadStore {

    private final ArtifactPayloadStore delegate;
    final AtomicInteger exclusiveCreates = new AtomicInteger();

    CountingPayloadStore(ArtifactPayloadStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public OutputStream exclusiveCreate(String relativePath) throws ArtifactCacheException {
        exclusiveCreates.incrementAndGet();
        return delegate.exclusiveCreate(relativePath);
    }

    @Override
    public InputStream openRead(String relativePath) throws ArtifactCacheException {
        return delegate.openRead(relativePath);
    }

    @Override
    public void bestEffortDelete(String relativePath) {
        delegate.bestEffortDelete(relativePath);
    }

    @Override
    public boolean exists(String relativePath) {
        return delegate.exists(relativePath);
    }
}
