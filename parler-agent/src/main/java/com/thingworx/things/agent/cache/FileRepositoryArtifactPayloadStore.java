package com.thingworx.things.agent.cache;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

import com.thingworx.things.repository.FileRepositoryThing;

/**
 * FileRepository adapter: exclusive-create via {@code CreateBinaryFile(path, empty, overwrite=false)}
 * under the platform file lock, then append streaming writes. Never list-then-write.
 *
 * <p>Parent directories are created by the platform {@code CreateBinaryFile} → {@code prepareForWrite}
 * path ({@code createDirectories}). This adapter MUST NOT pre-call {@code CreateFolder}, which is
 * non-idempotent and logs ERROR when the folder already exists.
 */
final class FileRepositoryArtifactPayloadStore implements ArtifactPayloadStore {

    /** Package-visible seam for focused exclusive-create tests (no {@code CreateFolder}). */
    interface BinaryBackend {
        void createBinaryFileExclusive(String relativePath) throws Exception;

        OutputStream openAppend(String relativePath) throws Exception;

        void delete(String relativePath);

        InputStream openRead(String relativePath) throws Exception;
    }

    private final BinaryBackend backend;

    public FileRepositoryArtifactPayloadStore(FileRepositoryThing repository) {
        this(new ThingBinaryBackend(Objects.requireNonNull(repository, "repository")));
    }

    FileRepositoryArtifactPayloadStore(BinaryBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public OutputStream exclusiveCreate(String relativePath) throws ArtifactCacheException {
        Objects.requireNonNull(relativePath, "relativePath");
        try {
            backend.createBinaryFileExclusive(relativePath);
        } catch (Exception e) {
            if (isAlreadyExists(e)) {
                throw new ArtifactCacheException(ArtifactCacheFaultCode.CREATE_COLLISION,
                        "Payload path already exists", e);
            }
            throw new ArtifactCacheException(ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE,
                    "Exclusive create failed", e);
        }
        try {
            return backend.openAppend(relativePath);
        } catch (Exception e) {
            bestEffortDelete(relativePath);
            throw new ArtifactCacheException(ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE,
                    "Open for append failed after exclusive create", e);
        }
    }

    @Override
    public InputStream openRead(String relativePath) throws ArtifactCacheException {
        try {
            return backend.openRead(relativePath);
        } catch (Exception e) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.PAYLOAD_FAULT, "Open read failed", e);
        }
    }

    @Override
    public void bestEffortDelete(String relativePath) {
        if (relativePath == null) {
            return;
        }
        backend.delete(relativePath);
    }

    @Override
    public boolean exists(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        try {
            backend.openRead(relativePath).close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isAlreadyExists(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.toLowerCase().contains("already exists")) {
                return true;
            }
        }
        return false;
    }

    private static final class ThingBinaryBackend implements BinaryBackend {
        private final FileRepositoryThing repository;

        ThingBinaryBackend(FileRepositoryThing repository) {
            this.repository = repository;
        }

        @Override
        public void createBinaryFileExclusive(String relativePath) throws Exception {
            repository.CreateBinaryFile(relativePath, new byte[0], Boolean.FALSE);
        }

        @Override
        public OutputStream openAppend(String relativePath) throws Exception {
            return repository.openFileForWrite(relativePath, FileRepositoryThing.FileMode.APPEND);
        }

        @Override
        public void delete(String relativePath) {
            try {
                repository.DeleteFile(relativePath);
            } catch (Exception ignored) {
                // administrator cleanup owns retained residue
            }
        }

        @Override
        public InputStream openRead(String relativePath) throws Exception {
            return repository.openFileForRead(relativePath);
        }
    }
}
