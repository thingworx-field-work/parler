package com.thingworx.things.agent.join;

import java.util.Objects;

/** One equality key pair: left column name ↔ right column name. */
public final class JoinKeySpec {

    private final String leftColumn;
    private final String rightColumn;

    public JoinKeySpec(String leftColumn, String rightColumn) {
        this.leftColumn = requireName(leftColumn, "leftColumn");
        this.rightColumn = requireName(rightColumn, "rightColumn");
    }

    public String leftColumn() {
        return leftColumn;
    }

    public String rightColumn() {
        return rightColumn;
    }

    private static String requireName(String name, String label) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(label + " required");
        }
        return name.trim();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JoinKeySpec)) {
            return false;
        }
        JoinKeySpec that = (JoinKeySpec) o;
        return leftColumn.equals(that.leftColumn) && rightColumn.equals(that.rightColumn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(leftColumn, rightColumn);
    }
}
