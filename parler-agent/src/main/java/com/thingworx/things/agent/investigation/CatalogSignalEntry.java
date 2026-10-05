package com.thingworx.things.agent.investigation;

/**
 * Candidate signal/KPI entry bound to a predecessor semantic id (fleet-rca D8).
 */
public final class CatalogSignalEntry {

    private final String signalSemanticId;
    private final String assetTypeKey;
    private final String businessStateFilter;

    public CatalogSignalEntry(String signalSemanticId, String assetTypeKey, String businessStateFilter) {
        this.signalSemanticId = requireNonBlank(signalSemanticId, "signalSemanticId");
        this.assetTypeKey = requireNonBlank(assetTypeKey, "assetTypeKey");
        this.businessStateFilter =
                businessStateFilter == null || businessStateFilter.isBlank() ? null : businessStateFilter.trim();
    }

    public String signalSemanticId() {
        return signalSemanticId;
    }

    public String assetTypeKey() {
        return assetTypeKey;
    }

    public String businessStateFilter() {
        return businessStateFilter;
    }

    private static String requireNonBlank(String s, String name) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return s.trim();
    }
}
