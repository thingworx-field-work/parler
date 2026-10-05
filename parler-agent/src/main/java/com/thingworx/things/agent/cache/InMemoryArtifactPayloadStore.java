package com.thingworx.things.agent.cache;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Test / local payload store with atomic exclusive-create via {@code putIfAbsent}. */
final class InMemoryArtifactPayloadStore implements ArtifactPayloadStore {

    private final ConcurrentHashMap<String, byte[]> files = new ConcurrentHashMap<>();

    @Override
    public OutputStream exclusiveCreate(String relativePath) throws ArtifactCacheException {
        Objects.requireNonNull(relativePath, "relativePath");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] placeholder = new byte[0];
        byte[] prior = files.putIfAbsent(relativePath, placeholder);
        if (prior != null) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.CREATE_COLLISION,
                    "Payload path already exists");
        }
        return new OutputStream() {
            private boolean closed;

            @Override
            public void write(int b) {
                buf.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                buf.write(b, off, len);
            }

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                files.put(relativePath, buf.toByteArray());
            }
        };
    }

    @Override
    public InputStream openRead(String relativePath) throws ArtifactCacheException {
        byte[] data = files.get(relativePath);
        if (data == null) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.PAYLOAD_FAULT, "Payload missing");
        }
        return new ByteArrayInputStream(data);
    }

    @Override
    public void bestEffortDelete(String relativePath) {
        if (relativePath != null) {
            files.remove(relativePath);
        }
    }

    @Override
    public boolean exists(String relativePath) {
        return relativePath != null && files.containsKey(relativePath);
    }

    /** Test helper: pre-seed a retained payload without going through exclusive-create. */
    public void seed(String relativePath, byte[] bytes) {
        files.put(Objects.requireNonNull(relativePath), bytes == null ? new byte[0] : bytes.clone());
    }
}
