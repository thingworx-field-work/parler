package com.thingworx.things.agent.semantics;

import java.util.List;
import java.util.Objects;

/** One App-authored property role under an asset type. */
public final class SemanticPropertyRole {

    private final String roleId;
    private final List<String> aliases;
    private final SemanticRoleBinding binding;
    private final String unit;
    private final String dimension;
    private final String grain;
    private final String expectedCadence;

    public SemanticPropertyRole(String roleId, List<String> aliases, SemanticRoleBinding binding, String unit,
            String dimension, String grain, String expectedCadence) {
        this.roleId = Objects.requireNonNull(roleId, "roleId");
        this.aliases = aliases != null ? List.copyOf(aliases) : List.of();
        this.binding = Objects.requireNonNull(binding, "binding");
        this.unit = Objects.requireNonNull(unit, "unit");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.grain = Objects.requireNonNull(grain, "grain");
        this.expectedCadence = expectedCadence;
    }

    public String roleId() {
        return roleId;
    }

    public List<String> aliases() {
        return aliases;
    }

    public SemanticRoleBinding binding() {
        return binding;
    }

    public String unit() {
        return unit;
    }

    public String dimension() {
        return dimension;
    }

    public String grain() {
        return grain;
    }

    public String expectedCadence() {
        return expectedCadence;
    }

    /** Stable ref form for {@code SourceDescriptor.propertyRoleRef} (SP5). */
    public String propertyRoleRef(String assetTypeKey) {
        return assetTypeKey + ".propertyRole." + roleId;
    }
}
