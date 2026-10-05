package com.thingworx.things.agent.taxonomy;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import com.thingworx.things.Thing;

/**
 * Proves a live Thing's exact taxonomy {@code assetTypeKey} by parent membership (SP6 / A5).
 * Zero or multiple matching keys → empty (no guess).
 */
public final class TaxonomyAssetTypeProof {

    private TaxonomyAssetTypeProof() {}

    /**
     * Returns the unique {@code assetTypeKey} whose taxonomy parent (and optional
     * {@code queryParent}) the Thing implements. Absent when the Thing or taxonomy is unavailable,
     * or when zero / multiple distinct keys match.
     */
    public static Optional<String> proveExactAssetTypeKey(Thing thing,
            ApplicationSemanticTaxonomySnapshot taxonomy) {
        if (thing == null || taxonomy == null || !taxonomy.isLoaded()) {
            return Optional.empty();
        }
        return proveExactAssetTypeKey(taxonomy, entry -> thingMatchesEntry(thing, entry));
    }

    /**
     * Membership-predicate form for offline tests (no live {@link Thing} required).
     */
    public static Optional<String> proveExactAssetTypeKey(ApplicationSemanticTaxonomySnapshot taxonomy,
            Predicate<AssetTypeEntry> matches) {
        if (taxonomy == null || !taxonomy.isLoaded() || matches == null) {
            return Optional.empty();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (AssetTypeEntry entry : taxonomy.assetTypes()) {
            if (entry == null || entry.key() == null || entry.key().isBlank()) {
                continue;
            }
            if (matches.test(entry)) {
                keys.add(entry.key());
            }
        }
        if (keys.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(keys.iterator().next());
    }

    static boolean thingMatchesEntry(Thing thing, AssetTypeEntry entry) {
        if (thing == null || entry == null) {
            return false;
        }
        if (!TaxonomyParentMembership.thingImplementsParent(thing, entry.parentEntityType(),
                entry.parentEntityName())) {
            return false;
        }
        TaxonomyQueryParent qp = entry.queryParent();
        if (qp != null
                && !TaxonomyParentMembership.thingImplementsParent(thing, qp.entityType(), qp.entityName())) {
            return false;
        }
        return true;
    }
}
