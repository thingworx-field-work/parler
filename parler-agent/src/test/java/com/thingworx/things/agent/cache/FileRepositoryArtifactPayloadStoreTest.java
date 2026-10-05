package com.thingworx.things.agent.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Exclusive-create must not pre-call
 * {@code CreateFolder}; platform {@code CreateBinaryFile} creates parents. Focused absent-parent /
 * existing-parent / collision coverage.
 */
class FileRepositoryArtifactPayloadStoreTest {

    @Test
    void exclusiveCreate_absentParent_usesCreateBinaryFileOnly() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        FileRepositoryArtifactPayloadStore store = new FileRepositoryArtifactPayloadStore(backend);

        try (OutputStream out = store.exclusiveCreate("41646d696e6973747261746f72/2026-07-19/id.payload")) {
            out.write(1);
        }

        assertEquals(List.of("41646d696e6973747261746f72/2026-07-19/id.payload"), backend.createBinaryCalls);
        assertTrue(backend.createFolderCalls.isEmpty(),
                "must not pre-call CreateFolder (live ERROR pollution)");
        assertTrue(backend.createdParents.contains("41646d696e6973747261746f72/2026-07-19"),
                "backend CreateBinaryFile path must create missing parents");
        assertTrue(backend.exists("41646d696e6973747261746f72/2026-07-19/id.payload"));
    }

    @Test
    void exclusiveCreate_existingParent_stillSkipsCreateFolder() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        backend.ensureParent("41646d696e6973747261746f72/2026-07-19");
        FileRepositoryArtifactPayloadStore store = new FileRepositoryArtifactPayloadStore(backend);

        try (OutputStream out = store.exclusiveCreate("41646d696e6973747261746f72/2026-07-19/id2.payload")) {
            out.write(2);
        }

        assertEquals(1, backend.createBinaryCalls.size());
        assertTrue(backend.createFolderCalls.isEmpty(),
                "existing parent must not trigger CreateFolder");
    }

    @Test
    void exclusiveCreate_collision_mapsToCreateCollision() {
        RecordingBackend backend = new RecordingBackend();
        backend.seedFile("already/path.payload", new byte[] {9});
        FileRepositoryArtifactPayloadStore store = new FileRepositoryArtifactPayloadStore(backend);

        ArtifactCacheException ex = assertThrows(ArtifactCacheException.class,
                () -> store.exclusiveCreate("already/path.payload"));
        assertEquals(ArtifactCacheFaultCode.CREATE_COLLISION, ex.code());
        assertTrue(backend.createFolderCalls.isEmpty());
        assertEquals(List.of("already/path.payload"), backend.createBinaryCalls);
    }

    /**
     * Records CreateBinaryFile / openAppend; synthesizes parent creation like platform
     * {@code prepareForWrite}. Exposes {@code createFolderCalls} only to prove the adapter never
     * requests folders (the production adapter has no CreateFolder path).
     */
    private static final class RecordingBackend implements FileRepositoryArtifactPayloadStore.BinaryBackend {
        final List<String> createBinaryCalls = new ArrayList<>();
        final List<String> createFolderCalls = new ArrayList<>();
        final Set<String> createdParents = new LinkedHashSet<>();
        final Map<String, byte[]> files = new LinkedHashMap<>();

        void ensureParent(String parentPath) {
            createdParents.add(parentPath);
        }

        void seedFile(String path, byte[] bytes) {
            ensureParentsFor(path);
            files.put(path, bytes.clone());
        }

        boolean exists(String path) {
            return files.containsKey(path);
        }

        @Override
        public void createBinaryFileExclusive(String relativePath) throws Exception {
            createBinaryCalls.add(relativePath);
            if (files.containsKey(relativePath)) {
                throw new Exception("File Already Exists");
            }
            // Mirror platform prepareForWrite: create missing parent directories without CreateFolder.
            ensureParentsFor(relativePath);
            files.put(relativePath, new byte[0]);
        }

        @Override
        public OutputStream openAppend(String relativePath) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            return new OutputStream() {
                @Override
                public void write(int b) {
                    buf.write(b);
                }

                @Override
                public void close() {
                    byte[] prior = files.getOrDefault(relativePath, new byte[0]);
                    byte[] next = new byte[prior.length + buf.size()];
                    System.arraycopy(prior, 0, next, 0, prior.length);
                    System.arraycopy(buf.toByteArray(), 0, next, prior.length, buf.size());
                    files.put(relativePath, next);
                }
            };
        }

        @Override
        public void delete(String relativePath) {
            files.remove(relativePath);
        }

        @Override
        public InputStream openRead(String relativePath) throws Exception {
            byte[] data = files.get(relativePath);
            if (data == null) {
                throw new Exception("not found");
            }
            return new ByteArrayInputStream(data);
        }

        private void ensureParentsFor(String relativePath) {
            int last = relativePath.lastIndexOf('/');
            if (last <= 0) {
                return;
            }
            String parent = relativePath.substring(0, last);
            String[] parts = parent.split("/");
            StringBuilder cur = new StringBuilder();
            for (String part : parts) {
                if (part.isEmpty()) {
                    continue;
                }
                if (cur.length() > 0) {
                    cur.append('/');
                }
                cur.append(part);
                createdParents.add(cur.toString());
            }
        }
    }
}
