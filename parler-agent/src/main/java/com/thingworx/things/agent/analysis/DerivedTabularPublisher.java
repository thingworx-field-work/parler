package com.thingworx.things.agent.analysis;

import java.util.List;
import java.util.Objects;

import org.joda.time.DateTime;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.cache.PublicationGuard;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.cache.TypedCell;
import com.thingworx.things.agent.cache.TypedColumn;
import com.thingworx.things.agent.cache.TypedRow;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * Publishes a derived tabular artifact through {@link TabularArtifactHub} only (single cache-owner
 * rule). Converts typed projected rows to an InfoTable at the publish boundary.
 */
public final class DerivedTabularPublisher {

    private DerivedTabularPublisher() {}

    public static String publish(List<TypedColumn> columns, List<TypedRow> rows, SourceDescriptor descriptor)
            throws Exception {
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(rows, "rows");
        InfoTable table = toInfoTable(columns, rows);
        if (descriptor == null) {
            return TabularArtifactHub.store(table);
        }
        return TabularArtifactHub.store(table, descriptor);
    }

    /**
     * Publish an already materialized derived table under the producing operation's guard, so output
     * creation shares that operation's deadline and cancellation (see {@link PublicationGuard}).
     */
    public static String publish(InfoTable table, SourceDescriptor descriptor, PublicationGuard guard)
            throws Exception {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(guard, "guard");
        return TabularArtifactHub.store(table, descriptor, guard);
    }

    public static InfoTable toInfoTable(List<TypedColumn> columns, List<TypedRow> rows) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        for (TypedColumn col : columns) {
            FieldDefinition fd = new FieldDefinition();
            fd.setName(col.name());
            fd.setBaseType(col.baseType() == null ? BaseTypes.STRING : col.baseType());
            shape.addFieldDefinition(fd);
        }
        InfoTable table = new InfoTable(shape);
        for (TypedRow row : rows) {
            ValueCollection vc = new ValueCollection();
            for (int i = 0; i < columns.size(); i++) {
                TypedColumn col = columns.get(i);
                TypedCell cell = i < row.cells().size() ? row.cells().get(i) : TypedCell.ofNull();
                putCell(vc, col, cell);
            }
            table.addRow(vc);
        }
        return table;
    }

    private static void putCell(ValueCollection vc, TypedColumn col, TypedCell cell) {
        if (cell == null || cell.isNull()) {
            return;
        }
        BaseTypes bt = col.baseType() == null ? BaseTypes.STRING : col.baseType();
        if (bt == BaseTypes.NUMBER || bt == BaseTypes.INTEGER || bt == BaseTypes.LONG) {
            if (cell.kind() == TypedCell.Kind.NUMBER) {
                vc.put(col.name(), new NumberPrimitive(cell.numberValue()));
            }
            return;
        }
        if (bt == BaseTypes.BOOLEAN) {
            if (cell.kind() == TypedCell.Kind.BOOLEAN) {
                vc.put(col.name(), new BooleanPrimitive(cell.booleanValue()));
            }
            return;
        }
        if (bt == BaseTypes.DATETIME) {
            if (cell.kind() == TypedCell.Kind.DATETIME && cell.datetimeValue() != null) {
                vc.put(col.name(), new DatetimePrimitive(new DateTime(cell.datetimeValue().toEpochMilli())));
            } else if (cell.kind() == TypedCell.Kind.NUMBER) {
                vc.put(col.name(), new DatetimePrimitive(new DateTime((long) cell.numberValue())));
            }
            return;
        }
        if (cell.kind() == TypedCell.Kind.STRING) {
            vc.put(col.name(), new StringPrimitive(cell.stringValue()));
        } else if (cell.kind() == TypedCell.Kind.NUMBER) {
            vc.put(col.name(), new StringPrimitive(Double.toString(cell.numberValue())));
        } else if (cell.kind() == TypedCell.Kind.BOOLEAN) {
            vc.put(col.name(), new StringPrimitive(Boolean.toString(cell.booleanValue())));
        }
    }
}
