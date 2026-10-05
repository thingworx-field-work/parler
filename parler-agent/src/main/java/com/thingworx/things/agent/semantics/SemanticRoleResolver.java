package com.thingworx.things.agent.semantics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Exact role-ID or declared-alias resolution within one taxonomy {@code assetTypeKey} (SP6).
 * Never guesses across asset types or similarly named properties.
 */
public final class SemanticRoleResolver {

    private SemanticRoleResolver() {}

    public static SemanticResolveResult resolve(SemanticProfileSnapshot snapshot, String assetTypeKey,
            String roleIdOrAlias) {
        if (snapshot == null || !snapshot.isLoaded()) {
            return SemanticResolveResult.unavailable("semantic profile snapshot is not available");
        }
        if (assetTypeKey == null || assetTypeKey.isBlank()) {
            return SemanticResolveResult.notFound("assetTypeKey is required");
        }
        if (roleIdOrAlias == null || roleIdOrAlias.isBlank()) {
            return SemanticResolveResult.notFound("roleId or alias is required");
        }
        String key = assetTypeKey.trim();
        String needle = roleIdOrAlias.trim();
        List<SemanticPropertyRole> roles = snapshot.rolesForAssetType(key);
        if (roles.isEmpty()) {
            return SemanticResolveResult.notFound("no property roles for assetTypeKey=" + key);
        }

        List<SemanticPropertyRole> exactId = new ArrayList<>();
        List<SemanticPropertyRole> aliasHits = new ArrayList<>();
        for (SemanticPropertyRole role : roles) {
            if (role.roleId().equals(needle)) {
                exactId.add(role);
            }
            for (String alias : role.aliases()) {
                if (alias.equals(needle)) {
                    aliasHits.add(role);
                    break;
                }
            }
        }

        if (exactId.size() > 1) {
            return SemanticResolveResult.ambiguous("multiple roles share roleId=" + needle + " under " + key);
        }
        if (exactId.size() == 1) {
            if (!aliasHits.isEmpty() && (aliasHits.size() > 1 || !aliasHits.get(0).roleId().equals(exactId.get(0).roleId()))) {
                return SemanticResolveResult.ambiguous(
                        "roleId=" + needle + " collides with another role alias under " + key);
            }
            return SemanticResolveResult.resolved(exactId.get(0), key, snapshot.profileId(), snapshot.version(),
                    snapshot.digest());
        }

        if (aliasHits.size() > 1) {
            return SemanticResolveResult.ambiguous("alias=" + needle + " matches multiple roles under " + key);
        }
        if (aliasHits.size() == 1) {
            return SemanticResolveResult.resolved(aliasHits.get(0), key, snapshot.profileId(), snapshot.version(),
                    snapshot.digest());
        }

        // Case-insensitive alias/role collision check only for diagnostics clarity — still NOT_FOUND
        // (exact match required). Avoids guessing a case fold as RESOLVED.
        String lower = needle.toLowerCase(Locale.ROOT);
        for (SemanticPropertyRole role : roles) {
            if (role.roleId().toLowerCase(Locale.ROOT).equals(lower)) {
                return SemanticResolveResult.notFound(
                        "no exact role/alias match for '" + needle + "' under " + key + " (case differs)");
            }
        }

        return SemanticResolveResult.notFound("no role or alias '" + needle + "' under assetTypeKey=" + key);
    }
}
