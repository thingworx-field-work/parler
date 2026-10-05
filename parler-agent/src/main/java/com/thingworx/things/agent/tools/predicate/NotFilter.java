package com.thingworx.things.agent.tools.predicate;

import org.json.JSONException;
import org.json.JSONObject;

import com.thingworx.common.SharedConstants;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.constants.CommonPropertyNames;
import com.thingworx.types.data.filters.FilterFactory;
import com.thingworx.types.data.filters.IFilter;
import com.thingworx.types.primitives.IPrimitiveType;

/**
 * Negates a child {@link IFilter} — Parler extension closing the ThingWorx {@link com.thingworx.types.data.filters.FilterFactory}
 * composite {@code NOT} dispatch gap (see {@code docs/agent/query-spec.md} §3.2.1).
 */
public final class NotFilter implements IFilter {

    private final IFilter child;

    public NotFilter(IFilter child) {
        this.child = child;
    }

    @Override
    public String getFieldName() {
        return child != null ? child.getFieldName() : null;
    }

    @Override
    public void setFieldName(String value) {
        if (child != null) {
            child.setFieldName(value);
        }
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
    @SuppressWarnings("rawtypes")
    public boolean evaluateValue(IPrimitiveType value) {
        return !child.evaluateValue(value);
    }

    @Override
    public boolean evaluateFilter(ValueCollection row) {
        return !child.evaluateFilter(row);
    }

    @Override
    public void resolveFields(DataShapeDefinition fields) {
        if (child != null) {
            child.resolveFields(fields);
        }
    }

    @Override
    public JSONObject toJSON() throws JSONException {
        org.json.JSONArray arr = new org.json.JSONArray();
        arr.put(child.toJSON());
        return new JSONObject().put(CommonPropertyNames.PROP_TYPE, FilterFactory.FILTER_COMPOSITE_NOT)
                .put(CommonPropertyNames.PROP_FILTERS, arr);
    }
}
