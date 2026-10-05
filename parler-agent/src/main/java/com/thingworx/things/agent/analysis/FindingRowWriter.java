package com.thingworx.things.agent.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.cache.TypedCell;
import com.thingworx.things.agent.cache.TypedColumn;
import com.thingworx.things.agent.cache.TypedRow;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;

/**
 * Streams finding rows to an InfoTable / derived artifact via {@link DerivedTabularPublisher}
 * (DIK-1). Compact schema only — no source-column duplication.
 */
public final class FindingRowWriter {

    public static final List<TypedColumn> SCHEMA = List.of(
            new TypedColumn("sourceOrdinal", BaseTypes.LONG),
            new TypedColumn("timestamp", BaseTypes.DATETIME),
            new TypedColumn("score", BaseTypes.NUMBER),
            new TypedColumn("effect", BaseTypes.NUMBER),
            new TypedColumn("limit", BaseTypes.NUMBER),
            new TypedColumn("segmentOrPair", BaseTypes.STRING),
            new TypedColumn("methodId", BaseTypes.STRING),
            new TypedColumn("outcomeCode", BaseTypes.STRING));

    private FindingRowWriter() {}

    public static InfoTable toInfoTable(List<FindingRow> findings) throws Exception {
        return DerivedTabularPublisher.toInfoTable(SCHEMA, toTypedRows(findings));
    }

    public static String publish(List<FindingRow> findings, SourceDescriptor descriptor) throws Exception {
        return DerivedTabularPublisher.publish(SCHEMA, toTypedRows(findings), descriptor);
    }

    public static List<TypedRow> toTypedRows(List<FindingRow> findings) {
        Objects.requireNonNull(findings, "findings");
        List<TypedRow> rows = new ArrayList<>(findings.size());
        for (FindingRow f : findings) {
            List<TypedCell> cells = new ArrayList<>(SCHEMA.size());
            cells.add(TypedCell.ofNumber(f.sourceOrdinal()));
            cells.add(f.timestamp() == null ? TypedCell.ofNull() : TypedCell.ofDatetime(f.timestamp()));
            cells.add(nullableNumber(f.score()));
            cells.add(nullableNumber(f.effect()));
            cells.add(nullableNumber(f.limit()));
            cells.add(f.segmentOrPair() == null ? TypedCell.ofNull() : TypedCell.ofString(f.segmentOrPair()));
            cells.add(TypedCell.ofString(f.methodId()));
            cells.add(f.outcomeCode() == null ? TypedCell.ofNull() : TypedCell.ofString(f.outcomeCode()));
            rows.add(new TypedRow(f.sourceOrdinal(), cells));
        }
        return rows;
    }

    private static TypedCell nullableNumber(Double v) {
        if (v == null || !Double.isFinite(v)) {
            return TypedCell.ofNull();
        }
        return TypedCell.ofNumber(v);
    }
}
