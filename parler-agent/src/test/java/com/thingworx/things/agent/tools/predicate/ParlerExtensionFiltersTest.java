package com.thingworx.things.agent.tools.predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.tools.CachedTabularDecisionPredicate;
import com.thingworx.things.agent.tools.CachedTabularDecisionToolException;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.data.filters.IFilter;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;
import org.junit.jupiter.api.Test;

/**
 * Parler extension filter JSON (CONTAINS, AND, etc.) parsed to ThingWorx {@link IFilter}.
 */
class ParlerExtensionFiltersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static DataShapeDefinition shapeMm() {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition m = new FieldDefinition();
        m.setName("m");
        m.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(m);
        FieldDefinition u = new FieldDefinition();
        u.setName("u");
        u.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(u);
        return shape;
    }

    @Test
    void contains_value_percent_is_literal_not_wildcard() throws Exception {
        DataShapeDefinition shape = shapeMm();
        JsonNode f = MAPPER.readTree("{\"type\":\"CONTAINS\",\"fieldName\":\"m\",\"value\":\"%B\"}");
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("m", new StringPrimitive("%B"));
        row.put("u", new NumberPrimitive(1.0));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
        ValueCollection row2 = new ValueCollection();
        row2.put("m", new StringPrimitive("AB"));
        row2.put("u", new NumberPrimitive(1.0));
        assertFalse(CachedTabularDecisionPredicate.evaluateIfilter(fl, row2));
    }

    @Test
    void and_eq_string_and_contains() throws Exception {
        DataShapeDefinition shape = shapeMm();
        JsonNode f = MAPPER.readTree("{\"type\":\"AND\",\"filters\":["
                + "{\"type\":\"EQ\",\"fieldName\":\"m\",\"value\":\"A\"},"
                + "{\"type\":\"CONTAINS\",\"fieldName\":\"m\",\"value\":\"A\"}]}");
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("m", new StringPrimitive("A"));
        row.put("u", new NumberPrimitive(10.0));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
        ValueCollection row2 = new ValueCollection();
        row2.put("m", new StringPrimitive("B"));
        row2.put("u", new NumberPrimitive(10.0));
        assertFalse(CachedTabularDecisionPredicate.evaluateIfilter(fl, row2));
    }

    @Test
    void not_wraps_eq() throws Exception {
        DataShapeDefinition shape = shapeMm();
        JsonNode f = MAPPER.readTree("{\"type\":\"NOT\",\"filters\":[{\"type\":\"EQ\",\"fieldName\":\"m\",\"value\":\"A\"}]}");
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("m", new StringPrimitive("B"));
        row.put("u", new NumberPrimitive(1.0));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
        ValueCollection row2 = new ValueCollection();
        row2.put("m", new StringPrimitive("A"));
        row2.put("u", new NumberPrimitive(1.0));
        assertFalse(CachedTabularDecisionPredicate.evaluateIfilter(fl, row2));
    }

    @Test
    void not_rejects_missing_filters_array() throws Exception {
        DataShapeDefinition shape = shapeMm();
        JsonNode f = MAPPER.readTree("{\"type\":\"NOT\"}");
        CachedTabularDecisionToolException ex =
                assertThrows(CachedTabularDecisionToolException.class, () -> ParlerQueryFilterParser.parse(f, shape));
        assertTrue(ex.getMessage().contains("filters"));
    }
}
