package com.thingworx.things.agent.skillregistry;

import java.nio.charset.StandardCharsets;

import com.thingworx.types.InfoTable;

/**
 * Test seam for FileRepository directory listing / {@code LoadText} (see {@code docs/agent/skill-management.md}).
 */
public interface RepositoryReader {

    InfoTable getFileListing(String path, String nameMask) throws Exception;

    String loadText(String path) throws Exception;

    /**
     * Raw file bytes from the backing store. Production {@link FileRepositoryRepositoryReader} uses FileRepository
     * {@code LoadBinary}. Test doubles may default to UTF-8 bytes of {@link #loadText(String)} when not overridden.
     */
    default byte[] loadBinary(String path) throws Exception {
        String t = loadText(path);
        if (t == null) {
            return null;
        }
        return t.getBytes(StandardCharsets.UTF_8);
    }
}
