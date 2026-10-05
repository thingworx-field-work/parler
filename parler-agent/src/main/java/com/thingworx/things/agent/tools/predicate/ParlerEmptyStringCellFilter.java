package com.thingworx.things.agent.tools.predicate;

import org.json.JSONException;
import org.json.JSONObject;

import com.thingworx.common.SharedConstants;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.constants.CommonPropertyNames;
import com.thingworx.types.data.filters.IFilter;
import com.thingworx.types.primitives.IPrimitiveType;

/**
 * {@code ISEMPTY} / {@code NOTEMPTY} on non-null cells that are the empty string {@code ""} (see {@code docs/agent/query-spec.md} §3.3).
 * Null cells are treated as not empty-string matches for {@code ISEMPTY}.
 */
public final class ParlerEmptyStringCellFilter implements IFilter {

    private final String fieldName;
    /** {@code true} = ISEMPTY semantics; {@code false} = NOTEMPTY. */
    private final boolean inclusive;

    public ParlerEmptyStringCellFilter(String fieldName, boolean inclusive) {
        this.fieldName = fieldName;
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
        if (value == null) {
            return inclusive ? false : true;
        }
        String s;
        try {
            Object inner = value.getValue();
            s = inner == null ? null : String.valueOf(inner);
        } catch (Exception e) {
            s = String.valueOf(value);
        }
        if (s == null) {
            return inclusive ? false : true;
        }
        boolean empty = s.isEmpty();
        return inclusive ? empty : !empty;
    }

    @Override
    public boolean evaluateFilter(ValueCollection row) {
        return evaluateValue(row.getPrimitive(fieldName));
    }

    @Override
    public JSONObject toJSON() throws JSONException {
        String type = inclusive ? "ISEMPTY" : "NOTEMPTY";
        return new JSONObject().put(CommonPropertyNames.PROP_TYPE, type).put(CommonPropertyNames.PROP_FIELDNAME, fieldName);
    }
}
