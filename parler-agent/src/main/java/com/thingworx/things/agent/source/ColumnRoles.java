package com.thingworx.things.agent.source;

import java.util.Collection;
import java.util.Objects;

/**
 * Validated writer-declared column roles (CM-0). One rule, used by cache writers before stamping a
 * descriptor and by consumers after reading the visible schema: both roles name a visible
 * (non-PASSWORD) column and differ from each other. Anything else means "no trusted roles".
 */
public final class ColumnRoles {

    private final String timeColumn;
    private final String valueColumn;

    private ColumnRoles(String timeColumn, String valueColumn) {
        this.timeColumn = timeColumn;
        this.valueColumn = valueColumn;
    }

    public String timeColumn() {
        return timeColumn;
    }

    public String valueColumn() {
        return valueColumn;
    }

    /**
     * @return the validated pair, or {@code null} when either role is blank, names a column absent
     *         from {@code visibleColumnNames} (exact match), or both roles name the same column
     */
    public static ColumnRoles validate(String timeColumn, String valueColumn, Collection<String> visibleColumnNames) {
        String t = trimToNull(timeColumn);
        String v = trimToNull(valueColumn);
        if (t == null || v == null || t.equals(v) || visibleColumnNames == null) {
            return null;
        }
        if (!visibleColumnNames.contains(t) || !visibleColumnNames.contains(v)) {
            return null;
        }
        return new ColumnRoles(t, v);
    }

    /** Validate the roles a descriptor carries against the schema actually read from the cache. */
    public static ColumnRoles fromDescriptor(SourceDescriptor descriptor, Collection<String> visibleColumnNames) {
        if (descriptor == null) {
            return null;
        }
        return validate(descriptor.timeColumn(), descriptor.valueColumn(), visibleColumnNames);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ColumnRoles)) {
            return false;
        }
        ColumnRoles other = (ColumnRoles) o;
        return timeColumn.equals(other.timeColumn) && valueColumn.equals(other.valueColumn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timeColumn, valueColumn);
    }
}
