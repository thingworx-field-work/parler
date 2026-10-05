package com.thingworx.things.agent.investigation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates the U6 candidate catalog. Missing/invalid data fails the path — never falls back to
 * model-name guessing (fleet-rca D8).
 */
public final class CandidateCatalogValidator {

    private CandidateCatalogValidator() {}

    public static void validateOrThrow(CandidateCatalog catalog) {
        List<String> errors = validate(catalog);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("invalid CandidateCatalog: " + String.join("; ", errors));
        }
    }

    public static List<String> validate(CandidateCatalog catalog) {
        List<String> errors = new ArrayList<>();
        if (catalog == null) {
            errors.add("catalog is null");
            return errors;
        }
        if (catalog.relations().isEmpty()
                && catalog.signals().isEmpty()
                && catalog.serviceBindings().isEmpty()) {
            errors.add("catalog must contain at least one relation, signal, or service binding");
        }
        Set<String> relationKeys = new HashSet<>();
        for (CatalogRelationEntry r : catalog.relations()) {
            if (r.declaredDistance() > catalog.maxRelationDepth()) {
                errors.add("relation " + r.relationTypeId() + " distance "
                        + r.declaredDistance() + " exceeds maxRelationDepth "
                        + catalog.maxRelationDepth());
            }
            String key = r.relationTypeId() + "|" + r.fromAssetSemanticId() + "|" + r.toAssetSemanticId();
            if (!relationKeys.add(key)) {
                errors.add("duplicate relation " + key);
            }
        }
        if (catalog.relations().size() > catalog.maxRelationNodes()) {
            errors.add("relation count " + catalog.relations().size()
                    + " exceeds maxRelationNodes " + catalog.maxRelationNodes());
        }
        Set<String> signalKeys = new HashSet<>();
        for (CatalogSignalEntry s : catalog.signals()) {
            String key = s.assetTypeKey() + "|" + s.signalSemanticId();
            if (!signalKeys.add(key)) {
                errors.add("duplicate signal " + key);
            }
        }
        Set<String> bindingKeys = new HashSet<>();
        for (CatalogServiceBinding b : catalog.serviceBindings()) {
            String key = b.kind() + "|" + b.thingName() + "|" + b.serviceName();
            if (!bindingKeys.add(key)) {
                errors.add("duplicate service binding " + key);
            }
        }
        return errors;
    }
}
