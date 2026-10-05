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

/**
 * Query-spec §3.9 — {@code *NAME} suffix family behaves as string-like for {@code ISEMPTY} (category 3 matrix).
 */
class ParlerSuffixNameIsemptyMatrixTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void isempty_on_datashapename_column() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("shapeName");
        fd.setBaseType(BaseTypes.DATASHAPENAME);
        shape.addFieldDefinition(fd);
        JsonNode f = MAPPER.readTree("{\"type\":\"ISEMPTY\",\"fieldName\":\"shapeName\"}");
        IFilter fl = ParlerQueryFilterParser.parse(f, shape);
        fl.resolveFields(shape);
        ValueCollection row = new ValueCollection();
        row.put("shapeName", new StringPrimitive(""));
        assertTrue(CachedTabularDecisionPredicate.evaluateIfilter(fl, row));
    }
}
