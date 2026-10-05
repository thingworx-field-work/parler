package com.thingworx.things.agent.investigation;

/**
 * Typed asset relation bound to predecessor semantic ids (fleet-rca D8).
 */
public final class CatalogRelationEntry {

    private final String relationTypeId;
    private final String fromAssetSemanticId;
    private final String toAssetSemanticId;
    private final int declaredDistance;

    public CatalogRelationEntry(
            String relationTypeId, String fromAssetSemanticId, String toAssetSemanticId, int declaredDistance) {
        this.relationTypeId = requireNonBlank(relationTypeId, "relationTypeId");
        this.fromAssetSemanticId = requireNonBlank(fromAssetSemanticId, "fromAssetSemanticId");
        this.toAssetSemanticId = requireNonBlank(toAssetSemanticId, "toAssetSemanticId");
        if (declaredDistance < 1) {
            throw new IllegalArgumentException("declaredDistance must be >= 1");
        }
        this.declaredDistance = declaredDistance;
    }

    public String relationTypeId() {
        return relationTypeId;
    }

    public String fromAssetSemanticId() {
        return fromAssetSemanticId;
    }

    public String toAssetSemanticId() {
        return toAssetSemanticId;
    }

    public int declaredDistance() {
        return declaredDistance;
    }

    private static String requireNonBlank(String s, String name) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return s.trim();
    }
}
