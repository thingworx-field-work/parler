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
import com.thingworx.types.primitives.StringPrimitive;
import org.junit.jupiter.api.Test;

/** Query-spec §3.3 / §3.7 — {@code ISEMPTY} / {@code NOTEMPTY} string-family vs {@code TYPE_MISMATCH}. */
class ParlerIsemptyFamilyMatrixTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static DataShapeDefinition oneCol(String name, BaseTypes bt) {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName(name);
        fd.setBaseType(bt);
        shape.addFieldDefinition(fd);
        return shape;
    }

    @Test
    void isempty_true_on_empty_string_string_column() throws Exception {
        DataShapeDefinition shape = oneCol("s", BaseTypes.STRING);
        JsonNode f = MAPPER.readTree("{\"type\":\"ISEMPTY\",\"fieldName\":\"s\"}");
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("s", new StringPrimitive(""));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
        ValueCollection row2 = new ValueCollection();
        row2.put("s", new StringPrimitive("x"));
        assertFalse(CachedTabularDecisionPredicate.evaluateIfilter(fl, row2));
    }

    @Test
    void isempty_on_text_column() throws Exception {
        DataShapeDefinition shape = oneCol("t", BaseTypes.TEXT);
        JsonNode f = MAPPER.readTree("{\"type\":\"ISEMPTY\",\"fieldName\":\"t\"}");
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("t", new StringPrimitive(""));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
    }

    @Test
    void isempty_on_html_column() throws Exception {
        DataShapeDefinition shape = oneCol("h", BaseTypes.HTML);
        JsonNode f = MAPPER.readTree("{\"type\":\"ISEMPTY\",\"fieldName\":\"h\"}");
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("h", new StringPrimitive(""));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
    }

    @Test
    void isempty_on_hyperlink_column() throws Exception {
        DataShapeDefinition shape = oneCol("l", BaseTypes.HYPERLINK);
        JsonNode f = MAPPER.readTree("{\"type\":\"ISEMPTY\",\"fieldName\":\"l\"}");
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("l", new StringPrimitive(""));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
    }

    @Test
    void notempty_requires_non_empty_string() throws Exception {
        DataShapeDefinition shape = oneCol("s", BaseTypes.STRING);
        JsonNode f = MAPPER.readTree("{\"type\":\"NOTEMPTY\",\"fieldName\":\"s\"}");
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("s", new StringPrimitive("a"));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
        ValueCollection empty = new ValueCollection();
        empty.put("s", new StringPrimitive(""));
        assertFalse(CachedTabularDecisionPredicate.evaluateIfilter(fl, empty));
    }

    @Test
    void isempty_on_number_type_mismatch() throws Exception {
        DataShapeDefinition shape = oneCol("n", BaseTypes.NUMBER);
        JsonNode f = MAPPER.readTree("{\"type\":\"ISEMPTY\",\"fieldName\":\"n\"}");
        assertThrows(CachedTabularDecisionToolException.class, () -> ParlerQueryFilterParser.parse(f, shape));
    }
}
