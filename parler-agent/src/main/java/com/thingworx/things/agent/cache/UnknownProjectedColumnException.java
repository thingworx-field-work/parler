package com.thingworx.things.agent.cache;

import java.util.List;

import com.thingworx.things.agent.source.SourceDescriptor;

/**
 * Projection failure that keeps the facts a consumer needs to explain the miss without reopening the
 * artifact (CM-2): the rejected name, its position in the requested projection, the full visible
 * schema (PASSWORD columns already excluded) and the runtime descriptor when one exists. Same
 * message and {@link IllegalArgumentException} identity as before, so existing catch sites are unchanged.
 */
public final class UnknownProjectedColumnException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final transient String columnName;
    private final transient int projectionIndex;
    private final transient List<TypedColumn> visibleSchema;
    private final transient SourceDescriptor descriptor;

    public UnknownProjectedColumnException(String columnName, int projectionIndex, List<TypedColumn> visibleSchema,
            SourceDescriptor descriptor) {
        super("unknown projected column: " + columnName);
        this.columnName = columnName;
        this.projectionIndex = projectionIndex;
        this.visibleSchema = visibleSchema == null ? List.of() : List.copyOf(visibleSchema);
        this.descriptor = descriptor;
    }

    public String columnName() {
        return columnName;
    }

    /** Index of the rejected name in the caller's {@code projectedColumns} list. */
    public int projectionIndex() {
        return projectionIndex;
    }

    public List<TypedColumn> visibleSchema() {
        return visibleSchema;
    }

    /** Runtime descriptor of the cache, or {@code null} (for example after a JVM restart). */
    public SourceDescriptor descriptor() {
        return descriptor;
    }
}
