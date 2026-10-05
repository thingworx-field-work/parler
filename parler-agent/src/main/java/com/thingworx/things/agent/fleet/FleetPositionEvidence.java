package com.thingworx.things.agent.fleet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.cache.TypedCell;
import com.thingworx.things.agent.cache.TypedColumn;
import com.thingworx.things.agent.cache.TypedRow;
import com.thingworx.types.BaseTypes;

/**
 * Compact top-N / focus position rows for derived-artifact publish (fleet-rca §6.5 / FRC-2).
 * Does not include unauthorized identities.
 */
public final class FleetPositionEvidence {

    public static final List<TypedColumn> COLUMNS = List.of(
            new TypedColumn("semanticAssetId", BaseTypes.STRING),
            new TypedColumn("metricValue", BaseTypes.NUMBER),
            new TypedColumn("competitionRank", BaseTypes.INTEGER),
            new TypedColumn("statisticalPercentile", BaseTypes.NUMBER),
            new TypedColumn("robustZ", BaseTypes.NUMBER),
            new TypedColumn("focusOutsideTopN", BaseTypes.BOOLEAN));

    private final MemberPosition position;

    private FleetPositionEvidence(MemberPosition position) {
        this.position = Objects.requireNonNull(position, "position");
    }

    public static FleetPositionEvidence of(MemberPosition position) {
        return new FleetPositionEvidence(position);
    }

    public MemberPosition position() {
        return position;
    }

    public TypedRow toTypedRow(long sourceOrdinal) {
        List<TypedCell> cells = new ArrayList<>(COLUMNS.size());
        cells.add(TypedCell.ofString(position.semanticAssetId()));
        cells.add(TypedCell.ofNumber(position.metricValue()));
        cells.add(TypedCell.ofNumber(position.competitionRank()));
        cells.add(TypedCell.ofNumber(position.statisticalPercentile()));
        if (position.robustZ() != null) {
            cells.add(TypedCell.ofNumber(position.robustZ()));
        } else {
            cells.add(TypedCell.ofNull());
        }
        cells.add(TypedCell.ofBoolean(position.focusOutsideTopN()));
        return new TypedRow(sourceOrdinal, cells);
    }

    public static List<TypedRow> toTypedRows(List<MemberPosition> positions) {
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }
        List<TypedRow> rows = new ArrayList<>(positions.size());
        for (int i = 0; i < positions.size(); i++) {
            rows.add(of(positions.get(i)).toTypedRow(i));
        }
        return List.copyOf(rows);
    }
}
