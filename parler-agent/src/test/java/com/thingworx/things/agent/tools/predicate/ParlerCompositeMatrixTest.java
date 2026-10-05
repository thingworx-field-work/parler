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
 * Query-spec §5 composite filter matrix (AND/OR/NOT, arity, depth, leaf caps).
 */
class ParlerCompositeMatrixTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static DataShapeDefinition shapeMx() {
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
    void or_native_lt_and_extension_contains() throws Exception {
        DataShapeDefinition shape = shapeMx();
        JsonNode f = MAPPER.readTree("{\"type\":\"OR\",\"filters\":["
                + "{\"type\":\"LT\",\"fieldName\":\"u\",\"value\":0},"
                + "{\"type\":\"CONTAINS\",\"fieldName\":\"m\",\"value\":\"Z\"}]}");
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("m", new StringPrimitive("A"));
        row.put("u", new NumberPrimitive(-1.0));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
        ValueCollection row2 = new ValueCollection();
        row2.put("m", new StringPrimitive("A"));
        row2.put("u", new NumberPrimitive(5.0));
        assertFalse(CachedTabularDecisionPredicate.evaluateIfilter(fl, row2));
    }

    @Test
    void not_native_gt() throws Exception {
        DataShapeDefinition shape = shapeMx();
        JsonNode f = MAPPER.readTree("{\"type\":\"NOT\",\"filters\":[{\"type\":\"GT\",\"fieldName\":\"u\",\"value\":100}]}");
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("m", new StringPrimitive("A"));
        row.put("u", new NumberPrimitive(50.0));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
    }

    @Test
    void not_extension_contains() throws Exception {
        DataShapeDefinition shape = shapeMx();
        JsonNode f = MAPPER.readTree("{\"type\":\"NOT\",\"filters\":[{\"type\":\"CONTAINS\",\"fieldName\":\"m\",\"value\":\"X\"}]}");
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("m", new StringPrimitive("abc"));
        row.put("u", new NumberPrimitive(1.0));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
    }

    @Test
    void not_and_native_extension() throws Exception {
        DataShapeDefinition shape = shapeMx();
        JsonNode f = MAPPER.readTree("{\"type\":\"NOT\",\"filters\":[{\"type\":\"AND\",\"filters\":["
                + "{\"type\":\"EQ\",\"fieldName\":\"m\",\"value\":\"A\"},"
                + "{\"type\":\"LT\",\"fieldName\":\"u\",\"value\":99}]}]}");
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("m", new StringPrimitive("A"));
        row.put("u", new NumberPrimitive(10.0));
        assertFalse(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
        ValueCollection row2 = new ValueCollection();
        row2.put("m", new StringPrimitive("B"));
        row2.put("u", new NumberPrimitive(10.0));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, row2));
    }

    @Test
    void not_rejects_two_children() throws Exception {
        DataShapeDefinition shape = shapeMx();
        JsonNode f = MAPPER.readTree("{\"type\":\"NOT\",\"filters\":["
                + "{\"type\":\"EQ\",\"fieldName\":\"m\",\"value\":\"A\"},"
                + "{\"type\":\"EQ\",\"fieldName\":\"m\",\"value\":\"B\"}]}");
        assertThrows(CachedTabularDecisionToolException.class, () -> ParlerQueryFilterParser.parse(f, shape));
    }

    @Test
    void not_rejects_zero_children() throws Exception {
        DataShapeDefinition shape = shapeMx();
        JsonNode f = MAPPER.readTree("{\"type\":\"NOT\",\"filters\":[]}");
        assertThrows(CachedTabularDecisionToolException.class, () -> ParlerQueryFilterParser.parse(f, shape));
    }

    @Test
    void composite_depth_five_rejected() throws Exception {
        DataShapeDefinition shape = shapeMx();
        String inner = "{\"type\":\"EQ\",\"fieldName\":\"m\",\"value\":\"A\"}";
        String json = "{\"type\":\"NOT\",\"filters\":[{\"type\":\"NOT\",\"filters\":[{\"type\":\"NOT\",\"filters\":["
                + "{\"type\":\"NOT\",\"filters\":[" + inner + "]}]}]}]}";
        JsonNode f = MAPPER.readTree(json);
        assertThrows(CachedTabularDecisionToolException.class, () -> ParlerQueryFilterParser.parse(f, shape));
    }

    @Test
    void composite_depth_four_allowed() throws Exception {
        DataShapeDefinition shape = shapeMx();
        String inner = "{\"type\":\"EQ\",\"fieldName\":\"m\",\"value\":\"A\"}";
        String json = "{\"type\":\"NOT\",\"filters\":[{\"type\":\"NOT\",\"filters\":[{\"type\":\"NOT\",\"filters\":["
                + inner + "]}]}]}";
        JsonNode f = MAPPER.readTree(json);
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("m", new StringPrimitive("B"));
        row.put("u", new NumberPrimitive(1.0));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
    }

    @Test
    void leaf_count_thirty_three_rejected() throws Exception {
        DataShapeDefinition shape = shapeMx();
        StringBuilder sb = new StringBuilder("{\"type\":\"OR\",\"filters\":[");
        for (int i = 0; i < 33; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"type\":\"EQ\",\"fieldName\":\"m\",\"value\":\"").append(i).append("\"}");
        }
        sb.append("]}");
        JsonNode f = MAPPER.readTree(sb.toString());
        assertThrows(CachedTabularDecisionToolException.class, () -> ParlerQueryFilterParser.parse(f, shape));
    }

    @Test
    void leaf_count_thirty_two_allowed() throws Exception {
        DataShapeDefinition shape = shapeMx();
        StringBuilder sb = new StringBuilder("{\"type\":\"OR\",\"filters\":[");
        for (int i = 0; i < 32; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"type\":\"EQ\",\"fieldName\":\"m\",\"value\":\"").append(i).append("\"}");
        }
        sb.append("]}");
        JsonNode f = MAPPER.readTree(sb.toString());
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("m", new StringPrimitive("0"));
        row.put("u", new NumberPrimitive(0.0));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
    }
}
