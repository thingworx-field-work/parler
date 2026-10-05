package com.thingworx.things.agent.cache;

import java.util.Objects;

import com.thingworx.types.BaseTypes;

/** One projected column in a {@link TypedTabularStream} schema. */
public final class TypedColumn {

    private final String name;
    private final BaseTypes baseType;

    public TypedColumn(String name, BaseTypes baseType) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("column name required");
        }
        this.name = name.trim();
        this.baseType = Objects.requireNonNull(baseType, "baseType");
    }

    public String name() {
        return name;
    }

    public BaseTypes baseType() {
        return baseType;
    }
}
