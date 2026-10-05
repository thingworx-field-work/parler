package com.thingworx.things.agent.cache;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Case-fold-safe path layout: {@code <hex-username>/<yyyy-MM-dd>/<artifact-id>.payload}.
 * Encoded-username ≤192 ASCII bytes; artifact-id segment ≤64; full relative path ≤256.
 */
public final class ArtifactPathLayout {

    public static final int MAX_ENCODED_USERNAME_BYTES = 192;
    public static final int MAX_ARTIFACT_ID_SEGMENT_BYTES = 64;
    public static final int MAX_RELATIVE_PATH_BYTES = 256;
    private static final DateTimeFormatter UTC_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private ArtifactPathLayout() {}

    /** Lowercase hexadecimal encoding of UTF-8 username bytes (alphabet {@code [0-9a-f]} only). */
    public static String encodeUsername(String principalName) {
        Objects.requireNonNull(principalName, "principalName");
        byte[] utf8 = principalName.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(utf8.length * 2);
        for (byte b : utf8) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    public static String utcDateSegment(LocalDate utcDate) {
        return UTC_DATE.format(Objects.requireNonNull(utcDate, "utcDate"));
    }

    public static LocalDate utcDateOfEpochMilli(long epochMilli) {
        return java.time.Instant.ofEpochMilli(epochMilli).atZone(ZoneOffset.UTC).toLocalDate();
    }

    /**
     * Build and validate the repository-relative path. Counts ASCII byte length of encoded segments
     * and of the complete relative path string.
     */
    public static String buildRelativePath(String principalName, LocalDate utcDate, String artifactId)
            throws ArtifactCacheException {
        String encodedUser = encodeUsername(principalName);
        if (asciiLength(encodedUser) > MAX_ENCODED_USERNAME_BYTES) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.PATH_OVERFLOW,
                    "Encoded username segment exceeds " + MAX_ENCODED_USERNAME_BYTES + " bytes");
        }
        String date = utcDateSegment(utcDate);
        String idSeg = Objects.requireNonNull(artifactId, "artifactId") + ".payload";
        if (asciiLength(idSeg) > MAX_ARTIFACT_ID_SEGMENT_BYTES) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.PATH_OVERFLOW,
                    "Artifact-id segment exceeds " + MAX_ARTIFACT_ID_SEGMENT_BYTES + " bytes");
        }
        String relative = encodedUser + "/" + date + "/" + idSeg;
        if (asciiLength(relative) > MAX_RELATIVE_PATH_BYTES) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.PATH_OVERFLOW,
                    "Relative artifact path exceeds " + MAX_RELATIVE_PATH_BYTES + " bytes");
        }
        return relative;
    }

    static int asciiLength(String s) {
        return s.length(); // path alphabet is ASCII after encoding
    }
}
