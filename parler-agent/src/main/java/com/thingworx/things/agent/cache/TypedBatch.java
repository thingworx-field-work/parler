package com.thingworx.things.agent.cache;

import java.util.List;
import java.util.Objects;

/** A bounded batch of projected {@link TypedRow}s. */
public final class TypedBatch {

    private final List<TypedRow> rows;

    public TypedBatch(List<TypedRow> rows) {
        this.rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
    }

    public List<TypedRow> rows() {
        return rows;
    }

    public int size() {
        return rows.size();
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }
}
