package com.thingworx.things.agent.taxonomy;

import java.util.Objects;

/**
 * Optional v2 {@code queryParent} on a type row: narrows QIT to a {@link com.thingworx.things.ThingTemplate} before
 * membership checks (R2 {@code shape_as_type} + {@code ThingShape}).
 */
public final class TaxonomyQueryParent {

    private final String entityType;
    private final String entityName;
    private final String role;

    public TaxonomyQueryParent(String entityType, String entityName, String role) {
        this.entityType = entityType != null ? entityType : "";
        this.entityName = entityName != null ? entityName : "";
        this.role = role != null ? role : "";
    }

    public String entityType() {
        return entityType;
    }

    public String entityName() {
        return entityName;
    }

    public String role() {
        return role;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TaxonomyQueryParent)) {
            return false;
        }
        TaxonomyQueryParent that = (TaxonomyQueryParent) o;
        return Objects.equals(entityType, that.entityType) && Objects.equals(entityName, that.entityName)
                && Objects.equals(role, that.role);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entityType, entityName, role);
    }
}
