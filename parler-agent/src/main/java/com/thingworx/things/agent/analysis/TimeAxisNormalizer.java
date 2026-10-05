package com.thingworx.things.agent.analysis;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

import com.thingworx.things.agent.cache.TypedCell;
import com.thingworx.things.agent.cache.TypedColumn;
import com.thingworx.things.agent.cache.TypedRow;
import com.thingworx.types.BaseTypes;

/**
 * TQJ-1 time-axis helpers: resolve a timestamp column to a UTC {@link Instant}. Calendar bucket
 * boundaries (profile IANA zone) are applied by later G3 operators; this class only normalizes
 * instants.
 */
public final class TimeAxisNormalizer {

    private TimeAxisNormalizer() {}

    public static Instant toUtcInstant(TypedRow row, int columnIndex, TypedColumn column) {
        Objects.requireNonNull(row, "row");
        if (columnIndex < 0 || columnIndex >= row.cells().size()) {
            return null;
        }
        TypedCell cell = row.cells().get(columnIndex);
        return toUtcInstant(cell, column == null ? null : column.baseType());
    }

    public static Instant toUtcInstant(TypedCell cell, BaseTypes baseType) {
        if (cell == null || cell.isNull()) {
            return null;
        }
        if (cell.kind() == TypedCell.Kind.DATETIME) {
            return cell.datetimeValue();
        }
        if (baseType == BaseTypes.DATETIME || cell.kind() == TypedCell.Kind.NUMBER) {
            if (cell.kind() == TypedCell.Kind.NUMBER) {
                return Instant.ofEpochMilli((long) cell.numberValue());
            }
        }
        if (cell.kind() == TypedCell.Kind.STRING) {
            try {
                return Instant.parse(cell.stringValue().trim());
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    /** Validate an IANA zone id; never falls back to the server default. */
    public static ZoneId requireZone(String ianaZoneId) {
        if (ianaZoneId == null || ianaZoneId.isBlank()) {
            throw new IllegalArgumentException("TIMEZONE_REQUIRED");
        }
        try {
            return ZoneId.of(ianaZoneId.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("TIMEZONE_REQUIRED", e);
        }
    }
}
