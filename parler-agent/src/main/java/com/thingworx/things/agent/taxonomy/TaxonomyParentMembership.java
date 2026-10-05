package com.thingworx.things.agent.taxonomy;

import com.thingworx.things.Thing;

/**
 * Validates that a Thing implements the configured taxonomy parent (ThingTemplate or ThingShape).
 */
public final class TaxonomyParentMembership {

    private TaxonomyParentMembership() {}

    public static boolean thingImplementsParent(Thing thing, String entityType, String entityName) {
        if (thing == null || entityName == null || entityName.isEmpty()) {
            return false;
        }
        if ("ThingTemplate".equals(entityType)) {
            try {
                return thing.implementsTemplate(entityName);
            } catch (Exception e) {
                return false;
            }
        }
        if ("ThingShape".equals(entityType)) {
            try {
                return thing.implementsShape(entityName);
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }
}
