package com.thingworx.things.agent.taxonomy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One v3 {@code identity-types.json} array element: unique-Thing resolution rule scoped by
 * {@code baseThingTemplate}.
 */
public final class ThingIdentityRuleV3 {

    private final String baseThingTemplate;
    private final List<ThingIdentityPropertyMatchV3> identityProperties;
    private final List<String> criticalProperties;

    public ThingIdentityRuleV3(String baseThingTemplate, List<ThingIdentityPropertyMatchV3> identityProperties,
            List<String> criticalProperties) {
        this.baseThingTemplate = baseThingTemplate != null ? baseThingTemplate.trim() : "";
        this.identityProperties = identityProperties != null ? List.copyOf(identityProperties) : List.of();
        this.criticalProperties = criticalProperties != null ? List.copyOf(criticalProperties) : List.of();
    }

    public String baseThingTemplate() {
        return baseThingTemplate;
    }

    public List<ThingIdentityPropertyMatchV3> identityProperties() {
        return identityProperties;
    }

    public List<String> criticalProperties() {
        return criticalProperties;
    }

    /**
     * Synthetic {@link AssetTypeEntry} for delegating QIT scan + zippered identity matching to
     * {@link com.thingworx.things.agent.tools.TaxonomyIdentifierResolver} (entityKey prefix {@code v3zip:}).
     *
     * @param mergedCriticalProperties merged critical property names (asset-type row + rule); when {@code null} or
     *            empty, uses this rule's {@link #criticalProperties()} only
     * @param assetFilter optional {@code resolve_thing} {@code assetTypeKey} row; when its parent is a
     *            {@code ThingShape}, the synthetic row uses {@code shape_as_type} + {@code queryParent} on this rule's
     *            {@code baseThingTemplate} so QIT narrows to the template then filters by the shape
     */
    public AssetTypeEntry toSyntheticAssetTypeEntry(int ruleIndex, List<String> mergedCriticalProperties,
            AssetTypeEntry assetFilter) {
        List<String> propNames = new ArrayList<>();
        List<String> matchRules = new ArrayList<>();
        for (ThingIdentityPropertyMatchV3 p : identityProperties) {
            propNames.add(p.name());
            matchRules.add(p.match());
        }
        String entityKey = "v3zip:" + ruleIndex;
        List<String> crit = mergedCriticalProperties != null && !mergedCriticalProperties.isEmpty()
                ? mergedCriticalProperties
                : criticalProperties;
        if (assetFilter != null && "ThingShape".equals(assetFilter.parentEntityType())) {
            String shapeName = assetFilter.parentEntityName();
            if (shapeName != null && !shapeName.isEmpty()) {
                TaxonomyQueryParent qp = new TaxonomyQueryParent("ThingTemplate", baseThingTemplate, "");
                return new AssetTypeEntry(entityKey, List.of(), "shape_as_type", "thing", List.of(), "ThingShape",
                        shapeName, propNames, matchRules, crit, qp);
            }
        }
        return new AssetTypeEntry(entityKey, List.of(), "template_as_type", "thing", List.of(), "ThingTemplate",
                baseThingTemplate, propNames, matchRules, crit, null);
    }

    public AssetTypeEntry toSyntheticAssetTypeEntry(int ruleIndex, List<String> mergedCriticalProperties) {
        return toSyntheticAssetTypeEntry(ruleIndex, mergedCriticalProperties, null);
    }

    public AssetTypeEntry toSyntheticAssetTypeEntry(int ruleIndex) {
        return toSyntheticAssetTypeEntry(ruleIndex, null, null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ThingIdentityRuleV3)) {
            return false;
        }
        ThingIdentityRuleV3 that = (ThingIdentityRuleV3) o;
        return Objects.equals(baseThingTemplate, that.baseThingTemplate)
                && Objects.equals(identityProperties, that.identityProperties)
                && Objects.equals(criticalProperties, that.criticalProperties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseThingTemplate, identityProperties, criticalProperties);
    }
}
