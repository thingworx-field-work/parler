package com.thingworx.things.agent.cache;

import java.io.InputStream;
import java.io.OutputStream;

/** Payload I/O seam. Package-private — Core owns construction via registry adapters. */
interface ArtifactPayloadStore {

    /**
     * Atomically exclusive-create {@code relativePath} (create-if-absent). Returns a write stream
     * for the new empty payload. MUST NOT overwrite an existing file.
     *
     * @throws ArtifactCacheException {@link ArtifactCacheFaultCode#CREATE_COLLISION} when the path
     *         already exists
     */
    OutputStream exclusiveCreate(String relativePath) throws ArtifactCacheException;

    InputStream openRead(String relativePath) throws ArtifactCacheException;

    /** Best-effort delete of one incomplete payload; never throws to callers. */
    void bestEffortDelete(String relativePath);

    boolean exists(String relativePath);
}
