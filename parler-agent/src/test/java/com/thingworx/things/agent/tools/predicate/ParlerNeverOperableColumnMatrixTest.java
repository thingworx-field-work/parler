package com.thingworx.things.agent.tools.predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.tools.CachedTabularDecisionToolException;
import com.thingworx.types.BaseTypes;
import org.junit.jupiter.api.Test;

/** Query-spec §3.9 cat 6 — substring filters on never-operable JSON column surface {@code UNSUPPORTED_OPERATOR}. */
class ParlerNeverOperableColumnMatrixTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void contains_on_json_column_unsupported_operator() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fj = new FieldDefinition();
        fj.setName("j");
        fj.setBaseType(BaseTypes.JSON);
        shape.addFieldDefinition(fj);
        JsonNode f = MAPPER.readTree("{\"type\":\"CONTAINS\",\"fieldName\":\"j\",\"value\":\"x\"}");
        CachedTabularDecisionToolException ex =
                assertThrows(CachedTabularDecisionToolException.class, () -> ParlerQueryFilterParser.parse(f, shape));
        assertEquals("UNSUPPORTED_OPERATOR", ex.code);
    }
}
