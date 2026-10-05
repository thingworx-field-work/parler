package com.thingworx.things.agent.fleet;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.analysis.HalfOpenWindow;

/**
 * Authorized cohort membership frozen at execution start (fleet-rca §7.1 step 2). Mid-run profile
 * changes must not alter the digest or authorized set for this execution.
 */
public final class FrozenCohortMembership {

    private final String membershipDigest;
    private final String peerProfileId;
    private final String metricProfileId;
    private final HalfOpenWindow window;
    private final List<String> authorizedSemanticAssetIds;

    private FrozenCohortMembership(
            String membershipDigest,
            String peerProfileId,
            String metricProfileId,
            HalfOpenWindow window,
            List<String> authorizedSemanticAssetIds) {
        this.membershipDigest = membershipDigest;
        this.peerProfileId = peerProfileId;
        this.metricProfileId = metricProfileId;
        this.window = window;
        this.authorizedSemanticAssetIds = authorizedSemanticAssetIds;
    }

    /**
     * Freeze membership from the authorized semantic ids visible to the current principal.
     * Ids are sorted deterministically; duplicates are removed.
     */
    public static FrozenCohortMembership freeze(
            String peerProfileId,
            String metricProfileId,
            HalfOpenWindow window,
            List<String> authorizedSemanticAssetIds) {
        String peer = requireNonBlank(peerProfileId, "peerProfileId");
        String metric = requireNonBlank(metricProfileId, "metricProfileId");
        Objects.requireNonNull(window, "window");
        List<String> authorized = normalizeIds(authorizedSemanticAssetIds);
        String digest = digestOf(peer, metric, window, authorized);
        return new FrozenCohortMembership(digest, peer, metric, window, authorized);
    }

    public String membershipDigest() {
        return membershipDigest;
    }

    public String peerProfileId() {
        return peerProfileId;
    }

    public String metricProfileId() {
        return metricProfileId;
    }

    public HalfOpenWindow window() {
        return window;
    }

    /** Opaque authorized set for this execution — never expose unauthorized peers. */
    public List<String> authorizedSemanticAssetIds() {
        return authorizedSemanticAssetIds;
    }

    public int authorizedN() {
        return authorizedSemanticAssetIds.size();
    }

    public boolean contains(String semanticAssetId) {
        if (semanticAssetId == null || semanticAssetId.isBlank()) {
            return false;
        }
        return authorizedSemanticAssetIds.contains(semanticAssetId.trim());
    }

    private static List<String> normalizeIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                unique.add(id.trim());
            }
        }
        List<String> sorted = new ArrayList<>(unique);
        Collections.sort(sorted);
        return Collections.unmodifiableList(sorted);
    }

    private static String digestOf(
            String peer, String metric, HalfOpenWindow window, List<String> authorized) {
        StringBuilder sb = new StringBuilder();
        sb.append(peer).append('\n')
                .append(metric).append('\n')
                .append(window.startInclusive()).append('|').append(window.endExclusive()).append('\n');
        for (String id : authorized) {
            sb.append(id).append('\n');
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 required", e);
        }
    }

    private static String requireNonBlank(String s, String name) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return s.trim();
    }
}
