package com.thingworx.things.agent;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable taxonomy row for {@link PromptContextCacheSnapshot} and {@link com.thingworx.things.agent.tools.ModelKeyResolver}
 * synonym matching. Synonyms are normalized with {@link com.thingworx.things.agent.tools.ModelKeyResolutionNormalize#normalizePhase0}
 * at snapshot construction time.
 */
public final class TaxonomyRow {

    private final String assetType;
    private final String entityType;
    private final String entityName;
    private final List<String> synonymsNormalized;
    private final String criticalProperties;

    public TaxonomyRow(String assetType, String entityType, String entityName,
            List<String> synonymsNormalized, String criticalProperties) {
        this.assetType = assetType;
        this.entityType = entityType;
        this.entityName = entityName;
        this.synonymsNormalized = synonymsNormalized != null
                ? Collections.unmodifiableList(synonymsNormalized)
                : Collections.emptyList();
        this.criticalProperties = criticalProperties;
    }

    public String getAssetType() {
        return assetType;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityName() {
        return entityName;
    }

    public List<String> getSynonymsNormalized() {
        return synonymsNormalized;
    }

    public String getCriticalProperties() {
        return criticalProperties;
    }

    /**
     * Phase −1 match: {@code normalizedUser} is {@link com.thingworx.things.agent.tools.ModelKeyResolutionNormalize#normalizePhase0}
     * of the user key.
     */
    public boolean synonymsMatchNormalizedUser(String normalizedUser) {
        if (normalizedUser == null || normalizedUser.isEmpty()) {
            return false;
        }
        for (String frag : synonymsNormalized) {
            if (normalizedUser.equals(frag)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TaxonomyRow)) {
            return false;
        }
        TaxonomyRow that = (TaxonomyRow) o;
        return Objects.equals(assetType, that.assetType) && Objects.equals(entityType, that.entityType)
                && Objects.equals(entityName, that.entityName)
                && Objects.equals(synonymsNormalized, that.synonymsNormalized)
                && Objects.equals(criticalProperties, that.criticalProperties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(assetType, entityType, entityName, synonymsNormalized, criticalProperties);
    }
}
