package com.thingworx.things.agent.cache;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Opaque bounded reader over an indexed artifact. Early {@link #close()} releases only; natural EOF
 * requires a one-byte terminal probe.
 */
final class IndexedArtifactReader implements ArtifactReader {

    private final ArtifactRecord record;
    private final ArtifactIoLimits limits;
    private final InputStream in;
    private final long startedNanos;

    private long bytesRead;
    private boolean closed;
    private boolean naturalEofReached;

    IndexedArtifactReader(ArtifactRecord record, InputStream in, ArtifactIoLimits limits) {
        this.record = Objects.requireNonNull(record, "record");
        this.in = Objects.requireNonNull(in, "in");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.startedNanos = System.nanoTime();
    }

    @Override
    public ArtifactRecord record() {
        return record;
    }

    @Override
    public byte[] readBytes(int maxLen) throws ArtifactCacheException {
        ensureOpen();
        if (maxLen <= 0) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.INVALID_REQUEST,
                    "readBytes maxLen must be positive");
        }
        recheckWallTime();
        long unreadClaimed = record.byteCount() - bytesRead;
        if (unreadClaimed < 0) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.PAYLOAD_FAULT,
                    "Read exceeded claimed byteCount");
        }
        long remainingOpBytes = limits.maxBytes() - bytesRead;
        if (remainingOpBytes < 0) {
            remainingOpBytes = 0;
        }
        if (remainingOpBytes == 0 && unreadClaimed > 0) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.IO_LIMIT_EXCEEDED,
                    "Read exceeds maxBytes before claimed payload end");
        }
        long wantLong = Math.min(maxLen, Math.min(remainingOpBytes,
                Math.min(limits.maxInternalBufferBytes(), unreadClaimed)));
        if (wantLong > 0) {
            int want = (int) wantLong;
            byte[] buf = new byte[want];
            int got = readFully(buf, want);
            if (got < want) {
                throw new ArtifactCacheException(ArtifactCacheFaultCode.PAYLOAD_FAULT,
                        "Cached payload is truncated or missing");
            }
            bytesRead += got;
            recheckWallTime();
            return buf;
        }
        // unreadClaimed == 0: required natural-EOF probe
        int probe = readOne();
        if (probe >= 0) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.PAYLOAD_FAULT,
                    "Cached payload is longer than claimed byteCount");
        }
        naturalEofReached = true;
        return new byte[0];
    }

    @Override
    public void close() throws ArtifactCacheException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            in.close();
        } catch (IOException e) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.PAYLOAD_FAULT,
                    "Reader close failed", e);
        }
    }

    private void ensureOpen() throws ArtifactCacheException {
        if (closed) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.INVALID_REQUEST,
                    "Reader is closed");
        }
    }

    private void recheckWallTime() throws ArtifactCacheException {
        long elapsed = (System.nanoTime() - startedNanos) / 1_000_000L;
        if (limits.exceedsWallTime(elapsed)) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.IO_LIMIT_EXCEEDED,
                    "Read exceeds maxWallTimeMillis");
        }
    }

    private int readFully(byte[] buf, int want) throws ArtifactCacheException {
        int off = 0;
        while (off < want) {
            int n;
            try {
                n = in.read(buf, off, want - off);
            } catch (IOException e) {
                throw new ArtifactCacheException(ArtifactCacheFaultCode.PAYLOAD_FAULT,
                        "Payload read failed", e);
            }
            if (n < 0) {
                return off;
            }
            off += n;
        }
        return off;
    }

    private int readOne() throws ArtifactCacheException {
        try {
            return in.read();
        } catch (IOException e) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.PAYLOAD_FAULT,
                    "Payload probe failed", e);
        }
    }
}
