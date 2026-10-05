package com.thingworx.things.agent.configrepo;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

import com.thingworx.things.agent.skillregistry.RepositoryReader;

/**
 * SHA-256 over raw repository bytes (see {@code docs/agent/collection-tool.md}) with an upper bound on hashed size.
 */
public final class RepositoryFileFingerprint {

    /** Same order of magnitude as repository parsers; avoids hashing huge blobs in snapshot paths. */
    public static final long MAX_BYTES_FOR_SHA256 = 512 * 1024L;

    public enum Status {
        present,
        missing,
        read_error,
        oversized_for_hash;

        public String wireName() {
            return name();
        }
    }

    private RepositoryFileFingerprint() {}

    /**
     * @param capturedAtUtc logical capture instant for metadata (typically prompt-cache refresh time)
     */
    public static Result capture(RepositoryReader reader, String path, Instant capturedAtUtc) {
        if (reader == null || path == null || path.isBlank()) {
            return new Result(path, Status.missing, 0, null, capturedAtUtc);
        }
        byte[] raw;
        try {
            raw = reader.loadBinary(path);
        } catch (Exception e) {
            if (RepositoryTextLoads.isProbablyMissingFile(e)) {
                return new Result(path, Status.missing, 0, null, capturedAtUtc);
            }
            return new Result(path, Status.read_error, 0, null, capturedAtUtc);
        }
        if (raw == null) {
            return new Result(path, Status.missing, 0, null, capturedAtUtc);
        }
        long len = raw.length;
        if (len > MAX_BYTES_FOR_SHA256) {
            return new Result(path, Status.oversized_for_hash, len, null, capturedAtUtc);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw);
            String hex = HexFormat.of().formatHex(digest);
            return new Result(path, Status.present, len, hex, capturedAtUtc);
        } catch (Exception e) {
            return new Result(path, Status.read_error, len, null, capturedAtUtc);
        }
    }

    /** Fingerprint outcome for one repository path. */
    public static final class Result {
        private final String path;
        private final Status status;
        private final long byteSize;
        private final String sha256Hex;
        private final Instant capturedAtUtc;

        public Result(String path, Status status, long byteSize, String sha256Hex, Instant capturedAtUtc) {
            this.path = path != null ? path : "";
            this.status = status != null ? status : Status.read_error;
            this.byteSize = Math.max(0, byteSize);
            this.sha256Hex = sha256Hex;
            this.capturedAtUtc = capturedAtUtc != null ? capturedAtUtc : Instant.EPOCH;
        }

        public String path() {
            return path;
        }

        public Status status() {
            return status;
        }

        public long byteSize() {
            return byteSize;
        }

        /** Hex SHA-256 when {@link #status()} is {@link Status#present}; otherwise {@code null}. */
        public String sha256Hex() {
            return sha256Hex;
        }

        public Instant capturedAtUtc() {
            return capturedAtUtc;
        }

        public String statusWireLower() {
            return status.name().toLowerCase(Locale.ROOT);
        }
    }
}
