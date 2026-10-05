package com.thingworx.things.agent.tools.predicate;

import java.util.Locale;

import org.json.JSONException;
import org.json.JSONObject;

import com.thingworx.common.SharedConstants;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.constants.CommonPropertyNames;
import com.thingworx.types.data.filters.IFilter;
import com.thingworx.types.primitives.IPrimitiveType;

/**
 * Literal substring / prefix / suffix match (no wildcards) — Parler extensions per {@code docs/agent/query-spec.md} §3.3.
 */
public final class ParlerLiteralSubstringFilter implements IFilter {

    public enum Mode {
        CONTAINS, STARTS_WITH, ENDS_WITH
    }

    private final String fieldName;
    private final String needle;
    private final boolean caseSensitive;
    private final boolean inclusive;
    private final Mode mode;

    public ParlerLiteralSubstringFilter(String fieldName, Mode mode, String needle, boolean caseSensitive,
            boolean inclusive) {
        this.fieldName = fieldName;
        this.mode = mode;
        this.needle = needle == null ? "" : needle;
        this.caseSensitive = caseSensitive;
        this.inclusive = inclusive;
    }

    @Override
    public String getFieldName() {
        return fieldName;
    }

    @Override
    public void setFieldName(String value) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public void setFilterExpression(String expression) {
    }

    @Override
    @Deprecated
    public String getFilterExpression() {
        return SharedConstants.EMPTY_STRING;
    }

    @Override
    public void resolveFields(DataShapeDefinition metadata) {
    }

    @Override
    @SuppressWarnings("rawtypes")
    public boolean evaluateValue(IPrimitiveType value) {
        String hay = cellString(value);
        boolean hit = matches(hay);
        return inclusive ? hit : !hit;
    }

    @Override
    public boolean evaluateFilter(ValueCollection row) {
        return evaluateValue(row.getPrimitive(fieldName));
    }

    @Override
    public JSONObject toJSON() throws JSONException {
        String type;
        switch (mode) {
            case CONTAINS:
                type = inclusive ? "CONTAINS" : "NOTCONTAINS";
                break;
            case STARTS_WITH:
                type = inclusive ? "STARTSWITH" : "NOTSTARTSWITH";
                break;
            case ENDS_WITH:
                type = inclusive ? "ENDSWITH" : "NOTENDSWITH";
                break;
            default:
                type = "CONTAINS";
        }
        JSONObject o = new JSONObject();
        o.put(CommonPropertyNames.PROP_TYPE, type);
        o.put(CommonPropertyNames.PROP_FIELDNAME, fieldName);
        o.put(CommonPropertyNames.PROP_VALUE, needle);
        o.put(CommonPropertyNames.PROP_ISCASESENSITIVE, caseSensitive);
        return o;
    }

    private boolean matches(String hay) {
        if (hay == null) {
            hay = "";
        }
        String h = hay;
        String n = needle;
        if (!caseSensitive) {
            h = h.toLowerCase(Locale.ROOT);
            n = n.toLowerCase(Locale.ROOT);
        }
        switch (mode) {
            case CONTAINS:
                return h.contains(n);
            case STARTS_WITH:
                return h.startsWith(n);
            case ENDS_WITH:
                return h.endsWith(n);
            default:
                return false;
        }
    }

    private static String cellString(IPrimitiveType value) {
        if (value == null) {
            return null;
        }
        try {
            Object inner = value.getValue();
            return inner == null ? null : String.valueOf(inner);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
