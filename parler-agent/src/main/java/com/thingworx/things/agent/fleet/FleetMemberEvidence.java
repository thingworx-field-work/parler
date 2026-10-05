package com.thingworx.things.agent.fleet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.cache.TypedCell;
import com.thingworx.things.agent.cache.TypedColumn;
import com.thingworx.things.agent.cache.TypedRow;
import com.thingworx.types.BaseTypes;

/**
 * Per-member evidence row ready for derived-artifact publish (fleet-rca §7.1 step 5).
 * Permission-limited outcomes are omitted — never written as named rows.
 */
public final class FleetMemberEvidence {

    public static final List<TypedColumn> COLUMNS = List.of(
            new TypedColumn("semanticAssetId", BaseTypes.STRING),
            new TypedColumn("status", BaseTypes.STRING),
            new TypedColumn("metricValue", BaseTypes.NUMBER),
            new TypedColumn("unit", BaseTypes.STRING),
            new TypedColumn("grain", BaseTypes.STRING),
            new TypedColumn("methodId", BaseTypes.STRING),
            new TypedColumn("reasonCode", BaseTypes.STRING));

    private final GatedMemberOutcome outcome;

    private FleetMemberEvidence(GatedMemberOutcome outcome) {
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        if (outcome.status() == CohortMemberStatus.PERMISSION_LIMITED) {
            throw new IllegalArgumentException("permission-limited outcomes are not published as evidence rows");
        }
    }

    public static FleetMemberEvidence of(GatedMemberOutcome outcome) {
        return new FleetMemberEvidence(outcome);
    }

    public GatedMemberOutcome outcome() {
        return outcome;
    }

    public TypedRow toTypedRow(long sourceOrdinal) {
        List<TypedCell> cells = new ArrayList<>(COLUMNS.size());
        cells.add(TypedCell.ofString(outcome.semanticAssetId()));
        cells.add(TypedCell.ofString(outcome.status().name()));
        if (outcome.metricValue() != null) {
            cells.add(TypedCell.ofNumber(outcome.metricValue()));
        } else {
            cells.add(TypedCell.ofNull());
        }
        cells.add(stringOrNull(outcome.unit()));
        cells.add(stringOrNull(outcome.grain()));
        cells.add(stringOrNull(outcome.methodId()));
        cells.add(stringOrNull(outcome.reasonCode()));
        return new TypedRow(sourceOrdinal, cells);
    }

    public static List<TypedRow> toTypedRows(List<FleetMemberEvidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return List.of();
        }
        List<TypedRow> rows = new ArrayList<>(evidences.size());
        for (int i = 0; i < evidences.size(); i++) {
            rows.add(evidences.get(i).toTypedRow(i));
        }
        return List.copyOf(rows);
    }

    private static TypedCell stringOrNull(String v) {
        return v == null ? TypedCell.ofNull() : TypedCell.ofString(v);
    }
}
