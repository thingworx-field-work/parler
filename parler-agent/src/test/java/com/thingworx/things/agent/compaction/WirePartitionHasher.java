package com.thingworx.things.agent.compaction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

/**
 * Deterministic partition hashes for CC-8 wire snapshots ({@code tools}, {@code system}, messages projection).
 */
public final class WirePartitionHasher {

    private WirePartitionHasher() {}

    public static String hashTools(Map<String, Object> wireBody, StructuredMessageProjection.WireKind kind) {
        return hash(StructuredMessageProjection.projectTools(wireBody, kind));
    }

    public static String hashSystem(Map<String, Object> wireBody, StructuredMessageProjection.WireKind kind) {
        return hash(StructuredMessageProjection.projectSystem(wireBody, kind));
    }

    public static String hashMessages(Map<String, Object> wireBody, StructuredMessageProjection.WireKind kind) {
        return hash(StructuredMessageProjection.projectMessages(wireBody, kind));
    }

    public static String hash(Object partition) {
        return sha256Hex(StructuredMessageProjection.canonicalJson(partition));
    }

    public static int commonPrefixLength(
            List<Map<String, Object>> left,
            List<Map<String, Object>> right) {
        int limit = Math.min(left.size(), right.size());
        for (int i = 0; i < limit; i++) {
            if (!left.get(i).equals(right.get(i))) {
                return i;
            }
        }
        return limit;
    }

    private static String sha256Hex(String canonical) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
