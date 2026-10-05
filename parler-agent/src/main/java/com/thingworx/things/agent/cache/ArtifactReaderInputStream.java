package com.thingworx.things.agent.cache;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Adapts an opaque {@link ArtifactReader} to a sequential {@link InputStream} so Jackson can
 * parse TABULAR JSON without assembling a single byte[] of the whole payload.
 */
final class ArtifactReaderInputStream extends InputStream {

    private final ArtifactReader reader;
    private final int chunkSize;
    private byte[] buf = new byte[0];
    private int pos;
    private boolean eof;
    private long bytesReadFromArtifact;

    ArtifactReaderInputStream(ArtifactReader reader, int chunkSize) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.chunkSize = Math.max(1, chunkSize);
    }

    /** Opaque payload bytes pulled from {@link ArtifactReader} (chunk totals). */
    long bytesReadFromArtifact() {
        return bytesReadFromArtifact;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n <= 0 ? -1 : (one[0] & 0xff);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException("b");
        }
        if (off < 0 || len < 0 || off + len > b.length) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }
        if (!ensureBuffered()) {
            return -1;
        }
        int n = Math.min(len, buf.length - pos);
        System.arraycopy(buf, pos, b, off, n);
        pos += n;
        return n;
    }

    private boolean ensureBuffered() throws IOException {
        if (pos < buf.length) {
            return true;
        }
        if (eof) {
            return false;
        }
        try {
            byte[] chunk = reader.readBytes(chunkSize);
            if (chunk == null || chunk.length == 0) {
                eof = true;
                buf = new byte[0];
                pos = 0;
                return false;
            }
            bytesReadFromArtifact += chunk.length;
            buf = chunk;
            pos = 0;
            return true;
        } catch (ArtifactCacheException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}
