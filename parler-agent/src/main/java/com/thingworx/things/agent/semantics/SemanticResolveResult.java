package com.thingworx.things.agent.semantics;

import java.util.Objects;

/** Internal typed resolve result (SP6). */
public final class SemanticResolveResult {

    private final SemanticResolveStatus status;
    private final String propertyRoleRef;
    private final SemanticPropertyRole role;
    private final String profileId;
    private final String profileVersion;
    private final String profileDigest;
    private final String message;

    private SemanticResolveResult(SemanticResolveStatus status, String propertyRoleRef, SemanticPropertyRole role,
            String profileId, String profileVersion, String profileDigest, String message) {
        this.status = Objects.requireNonNull(status, "status");
        this.propertyRoleRef = propertyRoleRef;
        this.role = role;
        this.profileId = profileId;
        this.profileVersion = profileVersion;
        this.profileDigest = profileDigest;
        this.message = message != null ? message : "";
    }

    public static SemanticResolveResult resolved(SemanticPropertyRole role, String assetTypeKey, String profileId,
            String profileVersion, String profileDigest) {
        return new SemanticResolveResult(SemanticResolveStatus.RESOLVED, role.propertyRoleRef(assetTypeKey), role,
                profileId, profileVersion, profileDigest, "");
    }

    public static SemanticResolveResult ambiguous(String message) {
        return new SemanticResolveResult(SemanticResolveStatus.AMBIGUOUS, null, null, null, null, null, message);
    }

    public static SemanticResolveResult notFound(String message) {
        return new SemanticResolveResult(SemanticResolveStatus.NOT_FOUND, null, null, null, null, null, message);
    }

    public static SemanticResolveResult unavailable(String message) {
        return new SemanticResolveResult(SemanticResolveStatus.UNAVAILABLE, null, null, null, null, null, message);
    }

    public SemanticResolveStatus status() {
        return status;
    }

    public String propertyRoleRef() {
        return propertyRoleRef;
    }

    public SemanticPropertyRole role() {
        return role;
    }

    public String profileId() {
        return profileId;
    }

    public String profileVersion() {
        return profileVersion;
    }

    public String profileDigest() {
        return profileDigest;
    }

    public String message() {
        return message;
    }
}
