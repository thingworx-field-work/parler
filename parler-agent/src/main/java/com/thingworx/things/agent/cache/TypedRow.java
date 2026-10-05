package com.thingworx.things.agent.cache;

import java.util.List;
import java.util.Objects;

/** One projected source row with a stable source ordinal (0-based in source order). */
public final class TypedRow {

    private final long sourceOrdinal;
    private final List<TypedCell> cells;

    public TypedRow(long sourceOrdinal, List<TypedCell> cells) {
        if (sourceOrdinal < 0L) {
            throw new IllegalArgumentException("sourceOrdinal must be non-negative");
        }
        this.sourceOrdinal = sourceOrdinal;
        this.cells = List.copyOf(Objects.requireNonNull(cells, "cells"));
    }

    public long sourceOrdinal() {
        return sourceOrdinal;
    }

    public List<TypedCell> cells() {
        return cells;
    }
}
