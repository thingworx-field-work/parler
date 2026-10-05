package com.thingworx.things.agent.cache;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.thingworx.types.BaseTypes;

/**
 * Resolved recursive typed schema. INFOTABLE children are named columns; scalars have a name at
 * their nesting level (column or JSON field name).
 */
public final class ArtifactSchemaNode {

    private final String name;
    private final byte[] utf8NameBytes;
    private final BaseTypes baseType;
    private final List<ArtifactSchemaNode> children;
    private final boolean untypedBytes;

    private ArtifactSchemaNode(String name, BaseTypes baseType, List<ArtifactSchemaNode> children,
            boolean untypedBytes) {
        this.name = name == null ? "" : name;
        this.utf8NameBytes = this.name.getBytes(StandardCharsets.UTF_8);
        this.baseType = baseType;
        this.children = children;
        this.untypedBytes = untypedBytes;
    }

    public static ArtifactSchemaNode scalar(BaseTypes baseType) {
        return field("", baseType);
    }

    public static ArtifactSchemaNode field(String name, BaseTypes baseType) {
        Objects.requireNonNull(baseType, "baseType");
        if (baseType == BaseTypes.INFOTABLE) {
            throw new IllegalArgumentException("Use infotable(...) for INFOTABLE nodes");
        }
        return new ArtifactSchemaNode(name, baseType, Collections.emptyList(), false);
    }

    public static ArtifactSchemaNode infotable(String name, List<ArtifactSchemaNode> columns) {
        Objects.requireNonNull(columns, "columns");
        ArtifactSchemaNode[] copy = new ArtifactSchemaNode[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            copy[i] = Objects.requireNonNull(columns.get(i), "column");
        }
        return new ArtifactSchemaNode(name, BaseTypes.INFOTABLE,
                Collections.unmodifiableList(java.util.Arrays.asList(copy)), false);
    }

    public static ArtifactSchemaNode composite(BaseTypes baseType, List<ArtifactSchemaNode> children) {
        if (baseType != BaseTypes.INFOTABLE) {
            throw new IllegalArgumentException("composite requires INFOTABLE");
        }
        return infotable("", children);
    }

    /** Explicit untyped-bytes / untyped-JSON kind: cannot prove PASSWORD absence. */
    public static ArtifactSchemaNode untypedBytes() {
        return new ArtifactSchemaNode("", null, Collections.emptyList(), true);
    }

    public String name() {
        return name;
    }

    /** Precomputed UTF-8 of {@link #name()} for allocation-free wire compare. */
    public byte[] utf8NameBytes() {
        return utf8NameBytes;
    }

    public BaseTypes baseType() {
        return baseType;
    }

    public List<ArtifactSchemaNode> children() {
        return children;
    }

    public boolean isUntypedBytes() {
        return untypedBytes;
    }
}
