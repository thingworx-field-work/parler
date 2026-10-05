package com.thingworx.things.agent.semantics;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.thingworx.things.Thing;
import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.PromptContextCacheSnapshot;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptorSupport;
import com.thingworx.things.agent.taxonomy.ApplicationSemanticTaxonomySnapshot;
import com.thingworx.things.agent.taxonomy.TaxonomyAssetTypeProof;
import com.thingworx.things.agent.tools.AgentToolContext;

/**
 * U3S M2 source handoff: exact role resolution + invocation-time preflight + SP5 provenance on
 * {@link SourceDescriptor}. Never guesses a property or role.
 */
public final class SemanticSourceHandoff {

    private SemanticSourceHandoff() {}

    /**
     * Find a unique PROPERTY role whose binding {@code propertyName} matches across the profile.
     * Zero or multiple matches → empty (no guess). Diagnostic helper only — provenance attach MUST
     * use {@link #resolvePropertyBindingUnderAssetType} with a proven taxonomy key.
     */
    public static SemanticResolveResult resolveUniquePropertyBinding(SemanticProfileSnapshot profile,
            String propertyName) {
        if (profile == null || !profile.isLoaded() || propertyName == null || propertyName.isBlank()) {
            return SemanticResolveResult.unavailable("semantic profile or propertyName unavailable");
        }
        String needle = propertyName.trim();
        List<Hit> hits = new ArrayList<>();
        for (var e : profile.rolesByAssetType().entrySet()) {
            String assetTypeKey = e.getKey();
            for (SemanticPropertyRole role : e.getValue()) {
                if (role.binding().kind() == SemanticBindingKind.PROPERTY
                        && needle.equals(role.binding().propertyName())) {
                    hits.add(new Hit(assetTypeKey, role));
                }
            }
        }
        if (hits.isEmpty()) {
            return SemanticResolveResult.notFound("no PROPERTY role bound to propertyName=" + needle);
        }
        if (hits.size() > 1) {
            return SemanticResolveResult.ambiguous(
                    "multiple PROPERTY roles bound to propertyName=" + needle + " across asset types");
        }
        Hit hit = hits.get(0);
        return SemanticResolveResult.resolved(hit.role, hit.assetTypeKey, profile.profileId(), profile.version(),
                profile.digest());
    }

    /**
     * Resolve a PROPERTY role by exact binding {@code propertyName} within one proven
     * {@code assetTypeKey} only (SP6 / A5). Does not search other asset types.
     */
    public static SemanticResolveResult resolvePropertyBindingUnderAssetType(SemanticProfileSnapshot profile,
            String assetTypeKey, String propertyName) {
        if (profile == null || !profile.isLoaded()) {
            return SemanticResolveResult.unavailable("semantic profile snapshot is not available");
        }
        if (assetTypeKey == null || assetTypeKey.isBlank()) {
            return SemanticResolveResult.notFound("assetTypeKey is required");
        }
        if (propertyName == null || propertyName.isBlank()) {
            return SemanticResolveResult.notFound("propertyName is required");
        }
        String key = assetTypeKey.trim();
        String needle = propertyName.trim();
        List<SemanticPropertyRole> roles = profile.rolesForAssetType(key);
        if (roles.isEmpty()) {
            return SemanticResolveResult.notFound("no property roles for assetTypeKey=" + key);
        }
        List<Hit> hits = new ArrayList<>();
        for (SemanticPropertyRole role : roles) {
            if (role.binding().kind() == SemanticBindingKind.PROPERTY
                    && needle.equals(role.binding().propertyName())) {
                hits.add(new Hit(key, role));
            }
        }
        if (hits.isEmpty()) {
            return SemanticResolveResult.notFound(
                    "no PROPERTY role bound to propertyName=" + needle + " under assetTypeKey=" + key);
        }
        if (hits.size() > 1) {
            return SemanticResolveResult.ambiguous("multiple PROPERTY roles bound to propertyName=" + needle
                    + " under assetTypeKey=" + key);
        }
        Hit hit = hits.get(0);
        return SemanticResolveResult.resolved(hit.role, hit.assetTypeKey, profile.profileId(), profile.version(),
                profile.digest());
    }

    /**
     * Attach SP5 provenance to {@code base} only when the live Thing's taxonomy
     * {@code assetTypeKey} is uniquely proven and a PROPERTY role under that key binds
     * {@code propertyName}. Null Thing, unproven type, or failed preflight → {@code base}
     * unchanged (no guessed provenance).
     */
    public static SourceDescriptor attachPropertyProvenance(SourceDescriptor base, Thing thing,
            String propertyName) {
        if (base == null) {
            return null;
        }
        if (thing == null) {
            return base;
        }
        AgentThing agent = AgentToolContext.getAgentThing();
        if (agent == null) {
            return base;
        }
        PromptContextCacheSnapshot snap = agent.getPromptContextSnapshot();
        if (snap == null) {
            return base;
        }
        SemanticProfileSnapshot profile = snap.getSemanticProfile();
        ApplicationSemanticTaxonomySnapshot taxonomy = snap.getApplicationSemanticTaxonomy();
        Optional<String> provenKey = TaxonomyAssetTypeProof.proveExactAssetTypeKey(thing, taxonomy);
        if (provenKey.isEmpty()) {
            return base;
        }
        SemanticResolveResult resolved =
                resolvePropertyBindingUnderAssetType(profile, provenKey.get(), propertyName);
        if (resolved.status() != SemanticResolveStatus.RESOLVED) {
            return base;
        }
        SemanticBindingPreflight.Result pf = SemanticBindingPreflight.check(thing, resolved.role().binding());
        if (!pf.ok()) {
            return base;
        }
        return SourceDescriptorSupport.withSemanticProvenance(base, resolved);
    }

    private static final class Hit {
        final String assetTypeKey;
        final SemanticPropertyRole role;

        Hit(String assetTypeKey, SemanticPropertyRole role) {
            this.assetTypeKey = assetTypeKey;
            this.role = role;
        }
    }
}
