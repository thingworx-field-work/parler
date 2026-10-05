package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.cache.TypedColumn;
import com.thingworx.things.agent.source.ColumnRoles;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptorSupport;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * The one writer for numeric-history conversation caches (CM-0 / CM-1): builds the two-column table,
 * declares the column roles for the columns it names, validates them with the shared
 * {@link ColumnRoles} rule, stamps them on the descriptor of the same write, stores, and returns a
 * {@link StoredSeriesCache} that producers copy into their result JSON. Both
 * {@code build_history_overlay_chart} and numeric {@code query_property_history} go through here.
 *
 * <p>{@link #storeWithRoles} is the general entry: any producer may store any table and declare its
 * own roles; nothing here depends on the caller's tool name.
 */
public final class NumericHistoryCacheWriter {

    public static final String TIME_COLUMN = "timestamp";
    public static final String VALUE_COLUMN = "value";

    private NumericHistoryCacheWriter() {}

    /** Fresh two-column table ({@code timestamp} STRING ISO-8601, {@code value} NUMBER). */
    public static InfoTable newTable() {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition ts = new FieldDefinition();
        ts.setName(TIME_COLUMN);
        ts.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(ts);
        FieldDefinition value = new FieldDefinition();
        value.setName(VALUE_COLUMN);
        value.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(value);
        return new InfoTable(shape);
    }

    /** Append one sample; a {@code null} value leaves the value cell unset. */
    public static void addRow(InfoTable table, String isoTimestamp, Double value) {
        ValueCollection row = new ValueCollection();
        row.put(TIME_COLUMN, new StringPrimitive(isoTimestamp == null ? "" : isoTimestamp));
        if (value != null) {
            row.put(VALUE_COLUMN, new NumberPrimitive(value));
        }
        table.addRow(row);
    }

    /** Store a numeric-history table with the writer's own roles and no subject identity. */
    public static StoredSeriesCache store(InfoTable table, String sourceRouteId,
            UnaryOperator<SourceDescriptor> decorate) throws Exception {
        return store(table, sourceRouteId, null, null, decorate);
    }

    /**
     * Store a numeric-history table with the writer's own roles and the subject identity the writer
     * already knows (CM-4). Either name blank → no identity declared; the store still succeeds.
     */
    public static StoredSeriesCache store(InfoTable table, String sourceRouteId, String subjectThingName,
            String subjectPropertyName, UnaryOperator<SourceDescriptor> decorate) throws Exception {
        return storeWithRoles(table, sourceRouteId, TIME_COLUMN, VALUE_COLUMN, subjectThingName,
                subjectPropertyName, decorate);
    }

    /**
     * General entry: store {@code table} and declare {@code timeColumn} / {@code valueColumn} as its
     * roles. Roles that fail {@link ColumnRoles#validate} against the written columns are omitted
     * (the store still succeeds). {@code decorate} runs after the roles are stamped and must be a
     * same-artifact rebuild (roles survive it).
     */
    public static StoredSeriesCache storeWithRoles(InfoTable table, String sourceRouteId, String timeColumn,
            String valueColumn, UnaryOperator<SourceDescriptor> decorate) throws Exception {
        return storeWithRoles(table, sourceRouteId, timeColumn, valueColumn, null, null, decorate);
    }

    /**
     * General entry with an optional subject identity (CM-4): {@code subjectThingName} /
     * {@code subjectPropertyName} are what the writer knows at store time; they are never derived from
     * a later request. Roles and identity are stamped on the same descriptor before {@code decorate}.
     */
    public static StoredSeriesCache storeWithRoles(InfoTable table, String sourceRouteId, String timeColumn,
            String valueColumn, String subjectThingName, String subjectPropertyName,
            UnaryOperator<SourceDescriptor> decorate) throws Exception {
        if (table == null) {
            throw new IllegalArgumentException("table required");
        }
        List<TypedColumn> columns = describeColumns(table.getDataShape());
        List<String> names = new ArrayList<>(columns.size());
        for (TypedColumn c : columns) {
            names.add(c.name());
        }
        SourceDescriptor descriptor = SourceDescriptorSupport.forPrimaryStore(table, sourceRouteId);
        descriptor = SourceDescriptorSupport.withColumnRoles(descriptor, timeColumn, valueColumn, names);
        descriptor = SourceDescriptorSupport.withSubjectIdentity(descriptor, subjectThingName, subjectPropertyName);
        if (decorate != null) {
            SourceDescriptor decorated = decorate.apply(descriptor);
            if (decorated != null) {
                descriptor = decorated;
            }
        }
        String cacheId = InvokeServiceExecutor.storeInfotableInConversationCache(table, descriptor);
        if (cacheId == null || cacheId.isBlank()) {
            return null;
        }
        return new StoredSeriesCache(cacheId, columns, descriptor.timeColumn(), descriptor.valueColumn());
    }

    /** Visible (non-PASSWORD) columns of a shape, in declaration order. */
    public static List<TypedColumn> describeColumns(DataShapeDefinition shape) {
        List<TypedColumn> out = new ArrayList<>();
        if (shape == null || shape.getFields() == null) {
            return out;
        }
        for (FieldDefinition f : shape.getFields().getOrderedFieldsByOrdinal()) {
            if (f == null || f.getName() == null || f.getName().isBlank() || f.getBaseType() == BaseTypes.PASSWORD) {
                continue;
            }
            out.add(new TypedColumn(f.getName(), f.getBaseType() == null ? BaseTypes.STRING : f.getBaseType()));
        }
        return out;
    }

    /** Columns of the writer's canonical shape (used when nothing was stored, e.g. empty history). */
    public static List<TypedColumn> canonicalColumns() {
        return describeColumns(newTable().getDataShape());
    }
}
