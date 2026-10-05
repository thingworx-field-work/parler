package com.thingworx.things.agent.cache;

import java.time.Instant;

/**
 * Lightweight projected cell. Does not wrap ThingWorx primitives or nested InfoTables.
 */
public final class TypedCell {

    public enum Kind {
        NULL,
        STRING,
        NUMBER,
        BOOLEAN,
        DATETIME
    }

    private final Kind kind;
    private final String stringValue;
    private final double numberValue;
    private final boolean booleanValue;
    private final Instant datetimeValue;

    private TypedCell(Kind kind, String stringValue, double numberValue, boolean booleanValue,
            Instant datetimeValue) {
        this.kind = kind;
        this.stringValue = stringValue;
        this.numberValue = numberValue;
        this.booleanValue = booleanValue;
        this.datetimeValue = datetimeValue;
    }

    public static TypedCell ofNull() {
        return new TypedCell(Kind.NULL, null, 0d, false, null);
    }

    public static TypedCell ofString(String v) {
        return new TypedCell(Kind.STRING, v, 0d, false, null);
    }

    public static TypedCell ofNumber(double v) {
        return new TypedCell(Kind.NUMBER, null, v, false, null);
    }

    public static TypedCell ofBoolean(boolean v) {
        return new TypedCell(Kind.BOOLEAN, null, 0d, v, null);
    }

    public static TypedCell ofDatetime(Instant v) {
        return new TypedCell(Kind.DATETIME, null, 0d, false, v);
    }

    public Kind kind() {
        return kind;
    }

    public boolean isNull() {
        return kind == Kind.NULL;
    }

    public String stringValue() {
        return stringValue;
    }

    public double numberValue() {
        return numberValue;
    }

    public boolean booleanValue() {
        return booleanValue;
    }

    public Instant datetimeValue() {
        return datetimeValue;
    }
}
