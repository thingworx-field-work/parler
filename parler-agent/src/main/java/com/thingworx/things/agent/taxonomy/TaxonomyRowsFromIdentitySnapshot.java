package com.thingworx.things.agent.taxonomy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import com.thingworx.things.agent.TaxonomyRow;
import com.thingworx.things.agent.tools.ModelKeyResolutionNormalize;

/**
 * Projects {@link ApplicationSemanticTaxonomySnapshot} rows into {@link TaxonomyRow} for key resolution and
 * Playbook injection (same {@link TaxonomyRow} shape as the built-in taxonomy resolver projection).
 */
public final class TaxonomyRowsFromIdentitySnapshot {

    private TaxonomyRowsFromIdentitySnapshot() {}

    public static List<TaxonomyRow> build(ApplicationSemanticTaxonomySnapshot sem) {
        if (sem == null || !sem.isLoaded()) {
            return List.of();
        }
        List<TaxonomyRow> out = new ArrayList<>();
        for (AssetTypeEntry e : sem.assetTypes()) {
            LinkedHashSet<String> synNorm = new LinkedHashSet<>();
            // Type key + type-level aliases only. Entity-level aliases (entities[].aliases[]) are for
            // resolver entity-hint matching — not Phase −1 synonym equality on TaxonomyRow.
            addNormalizedSynonym(e.key(), synNorm);
            for (String a : e.aliases()) {
                addNormalizedSynonym(a, synNorm);
            }
            String crit = String.join(";", e.criticalProperties());
            out.add(new TaxonomyRow(e.key(), e.parentEntityType(), e.parentEntityName(), new ArrayList<>(synNorm), crit));
        }
        return out;
    }

    private static void addNormalizedSynonym(String raw, LinkedHashSet<String> synNorm) {
        if (raw == null) {
            return;
        }
        String n = ModelKeyResolutionNormalize.normalizePhase0(raw.trim());
        if (!n.isEmpty()) {
            synNorm.add(n);
        }
    }
}
