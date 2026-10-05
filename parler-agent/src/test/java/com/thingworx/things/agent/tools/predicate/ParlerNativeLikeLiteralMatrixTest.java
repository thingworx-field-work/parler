package com.thingworx.things.agent.tools.predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.tools.CachedTabularDecisionPredicate;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.data.filters.IFilter;
import com.thingworx.types.primitives.StringPrimitive;
import org.junit.jupiter.api.Test;

/** Query-spec §3.3 — TWX {@code LIKE} wildcard semantics ({@code *} / {@code ?}); underscore literal. */
class ParlerNativeLikeLiteralMatrixTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void like_star_matches_extra_chars() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("s");
        fd.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fd);
        JsonNode f = MAPPER.readTree("{\"type\":\"LIKE\",\"fieldName\":\"s\",\"value\":\"pre*post\"}");
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("s", new StringPrimitive("preMIDDLEpost"));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
    }

    @Test
    void like_question_mark_single_char() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("s");
        fd.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fd);
        JsonNode f = MAPPER.readTree("{\"type\":\"LIKE\",\"fieldName\":\"s\",\"value\":\"a?c\"}");
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("s", new StringPrimitive("abc"));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
        ValueCollection row2 = new ValueCollection();
        row2.put("s", new StringPrimitive("abbc"));
        assertFalse(CachedTabularDecisionPredicate.evaluateIfilter(fl, row2));
    }

    @Test
    void like_underscore_is_literal_not_sql_single_char_wildcard() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("s");
        fd.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fd);
        JsonNode f = MAPPER.readTree("{\"type\":\"LIKE\",\"fieldName\":\"s\",\"value\":\"a_c\"}");
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("s", new StringPrimitive("a_c"));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
        ValueCollection row2 = new ValueCollection();
        row2.put("s", new StringPrimitive("abc"));
        assertFalse(CachedTabularDecisionPredicate.evaluateIfilter(fl, row2));
    }
}
