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

/** Query-spec §4 Parler substring extension family (beyond CONTAINS covered in {@link ParlerExtensionFiltersTest}). */
class ParlerExtensionSubstringMatrixTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static DataShapeDefinition shapeS() {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition s = new FieldDefinition();
        s.setName("s");
        s.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(s);
        return shape;
    }

    @Test
    void notcontains_excludes_substring() throws Exception {
        DataShapeDefinition shape = shapeS();
        JsonNode f = MAPPER.readTree("{\"type\":\"NOTCONTAINS\",\"fieldName\":\"s\",\"value\":\"mid\"}");
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection ok = new ValueCollection();
        ok.put("s", new StringPrimitive("prefix"));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, ok));
        ValueCollection bad = new ValueCollection();
        bad.put("s", new StringPrimitive("prefixmiddlesuffix"));
        assertFalse(CachedTabularDecisionPredicate.evaluateIfilter(fl, bad));
    }

    @Test
    void startswith_literal_star_not_wildcard() throws Exception {
        DataShapeDefinition shape = shapeS();
        JsonNode f = MAPPER.readTree("{\"type\":\"STARTSWITH\",\"fieldName\":\"s\",\"value\":\"*pre\"}");
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("s", new StringPrimitive("*prefix"));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
        ValueCollection row2 = new ValueCollection();
        row2.put("s", new StringPrimitive("prefix"));
        assertFalse(CachedTabularDecisionPredicate.evaluateIfilter(fl, row2));
    }

    @Test
    void notstartswith_negates() throws Exception {
        DataShapeDefinition shape = shapeS();
        JsonNode f = MAPPER.readTree("{\"type\":\"NOTSTARTSWITH\",\"fieldName\":\"s\",\"value\":\"ab\"}");
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("s", new StringPrimitive("ba"));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
    }

    @Test
    void endswith_question_mark_literal() throws Exception {
        DataShapeDefinition shape = shapeS();
        JsonNode f = MAPPER.readTree("{\"type\":\"ENDSWITH\",\"fieldName\":\"s\",\"value\":\"x?\"}");
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("s", new StringPrimitive("yx?"));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
    }

    @Test
    void notendswith_negates() throws Exception {
        DataShapeDefinition shape = shapeS();
        JsonNode f = MAPPER.readTree("{\"type\":\"NOTENDSWITH\",\"fieldName\":\"s\",\"value\":\".txt\"}");
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("s", new StringPrimitive("file.doc"));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
    }

    @Test
    void contains_default_case_insensitive() throws Exception {
        DataShapeDefinition shape = shapeS();
        JsonNode f = MAPPER.readTree("{\"type\":\"CONTAINS\",\"fieldName\":\"s\",\"value\":\"abc\"}");
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("s", new StringPrimitive("xxAbCy"));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
    }

    @Test
    void contains_explicit_case_sensitive_no_match_when_case_differs() throws Exception {
        DataShapeDefinition shape = shapeS();
        JsonNode f = MAPPER.readTree("{\"type\":\"CONTAINS\",\"fieldName\":\"s\",\"value\":\"abc\",\"isCaseSensitive\":true}");
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("s", new StringPrimitive("xxAbCy"));
        assertFalse(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
    }
}
