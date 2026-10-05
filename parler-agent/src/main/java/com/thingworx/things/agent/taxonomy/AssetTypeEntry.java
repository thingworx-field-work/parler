package com.thingworx.things.agent.taxonomy;

import java.util.List;
import java.util.Objects;

/**
 * One validated identity-type row flattened from {@code /taxonomies/identity-types.json} {@code entities[].types[]}
 * (v2).
 */
public final class AssetTypeEntry {

    private final String entityKey;
    private final List<String> entityAliases;
    private final String representation;
    private final String key;
    private final List<String> aliases;
    private final String parentEntityType;
    private final String parentEntityName;
    private final List<String> identityProperties;
    private final List<String> matchRules;
    private final List<String> criticalProperties;
    private final TaxonomyQueryParent queryParent;

    public AssetTypeEntry(String entityKey, List<String> entityAliases, String representation, String key,
            List<String> aliases, String parentEntityType, String parentEntityName, List<String> identityProperties,
            List<String> matchRules, List<String> criticalProperties, TaxonomyQueryParent queryParent) {
        if (entityKey == null || entityKey.isBlank()) {
            throw new IllegalArgumentException("entityKey is required for v2 taxonomy rows");
        }
        this.entityKey = entityKey.trim();
        this.entityAliases = entityAliases != null ? List.copyOf(entityAliases) : List.of();
        this.representation = representation != null ? representation.trim() : "";
        this.key = key != null ? key : "";
        this.aliases = aliases != null ? List.copyOf(aliases) : List.of();
        this.parentEntityType = parentEntityType != null ? parentEntityType : "";
        this.parentEntityName = parentEntityName != null ? parentEntityName : "";
        this.identityProperties =
                identityProperties != null ? List.copyOf(identityProperties) : List.of();
        this.matchRules = matchRules != null ? List.copyOf(matchRules) : List.of();
        this.criticalProperties = criticalProperties != null ? List.copyOf(criticalProperties) : List.of();
        this.queryParent = queryParent;
    }

    public String entityKey() {
        return entityKey;
    }

    /** Aliases from the parent {@code entities[].aliases[]} (same list for every type under that entity). */
    public List<String> entityAliases() {
        return entityAliases;
    }

    /** {@code template_as_type} or {@code shape_as_type} for rows loaded by the parser. */
    public String representation() {
        return representation;
    }

    public String key() {
        return key;
    }

    public List<String> aliases() {
        return aliases;
    }

    public String parentEntityType() {
        return parentEntityType;
    }

    public String parentEntityName() {
        return parentEntityName;
    }

    public List<String> identityProperties() {
        return identityProperties;
    }

    public List<String> matchRules() {
        return matchRules;
    }

    public List<String> criticalProperties() {
        return criticalProperties;
    }

    /** When non-null, QIT uses this ThingTemplate first; {@code shape_as_type} rows still require shape membership. */
    public TaxonomyQueryParent queryParent() {
        return queryParent;
    }

    public boolean hasNameExactRule() {
        if (entityKey != null && entityKey.startsWith("v3zip:")) {
            if (identityProperties.size() != 1 || !"name".equals(identityProperties.get(0))) {
                return false;
            }
            if (matchRules.isEmpty()) {
                return false;
            }
            String r0 = matchRules.get(0);
            return "exact".equals(r0) || "equals".equals(r0);
        }
        return identityProperties.contains("name") && matchRules.contains("exact");
    }

    /** Dedup key for resolver matching (unique per flattened row). */
    String matchDedupKey() {
        return entityKey + "\u0001" + key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AssetTypeEntry)) {
            return false;
        }
        AssetTypeEntry that = (AssetTypeEntry) o;
        return Objects.equals(entityKey, that.entityKey) && Objects.equals(entityAliases, that.entityAliases)
                && Objects.equals(representation, that.representation) && Objects.equals(key, that.key)
                && Objects.equals(aliases, that.aliases) && Objects.equals(parentEntityType, that.parentEntityType)
                && Objects.equals(parentEntityName, that.parentEntityName)
                && Objects.equals(identityProperties, that.identityProperties)
                && Objects.equals(matchRules, that.matchRules)
                && Objects.equals(criticalProperties, that.criticalProperties)
                && Objects.equals(queryParent, that.queryParent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entityKey, entityAliases, representation, key, aliases, parentEntityType, parentEntityName,
                identityProperties, matchRules, criticalProperties, queryParent);
    }
}
