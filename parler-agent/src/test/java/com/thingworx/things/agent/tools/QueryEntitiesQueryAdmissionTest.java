package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

class QueryEntitiesQueryAdmissionTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode j(String json) throws Exception {
        return M.readTree(json);
    }

    @Test
    void nullQueryPasses() throws Exception {
        assertNull(QueryEntitiesQueryAdmission.validateOrErrorJson(null));
        assertNull(QueryEntitiesQueryAdmission.validateOrErrorJson(M.nullNode()));
    }

    @Test
    void queryWithoutFiltersPasses() throws Exception {
        assertNull(QueryEntitiesQueryAdmission.validateOrErrorJson(j("{\"sorts\":[]}")));
    }

    @Test
    void validEqLeafPasses() throws Exception {
        String q = "{\"filters\":{\"type\":\"EQ\",\"fieldName\":\"name\",\"value\":\"x\"}}";
        assertNull(QueryEntitiesQueryAdmission.validateOrErrorJson(j(q)));
    }

    @Test
    void compositeNotRejectedWithRecoveryHint() throws Exception {
        String q = "{\"filters\":{\"type\":\"NOT\",\"filters\":[{\"type\":\"EQ\",\"fieldName\":\"n\",\"value\":\"1\"}]}}";
        String err = QueryEntitiesQueryAdmission.validateOrErrorJson(j(q));
        assertNotNull(err);
        JsonNode o = M.readTree(err);
        assertEquals("UNSUPPORTED_PREDICATE_FOR_QUERY_ENTITIES", o.path("code").asText());
        assertEquals("analyze_entity_set", o.path("recoveryHint").path("tool").asText());
        assertEquals("difference", o.path("recoveryHint").path("operation").asText());
    }

    @Test
    void notWrongArityIsMalformed() throws Exception {
        String q = "{\"filters\":{\"type\":\"NOT\",\"filters\":[]}}";
        String err = QueryEntitiesQueryAdmission.validateOrErrorJson(j(q));
        assertNotNull(err);
        assertEquals("INVALID_PREDICATE", M.readTree(err).path("code").asText());
    }

    @Test
    void containsRejected() throws Exception {
        String q = "{\"filters\":{\"type\":\"CONTAINS\",\"fieldName\":\"x\",\"value\":\"y\"}}";
        String err = QueryEntitiesQueryAdmission.validateOrErrorJson(j(q));
        assertEquals("UNSUPPORTED_OPERATOR", M.readTree(err).path("code").asText());
    }

    @Test
    void missingFieldNameRejected() throws Exception {
        String q = "{\"filters\":{\"type\":\"EQ\",\"value\":\"x\"}}";
        String err = QueryEntitiesQueryAdmission.validateOrErrorJson(j(q));
        assertEquals("INVALID_PREDICATE", M.readTree(err).path("code").asText());
    }

    @Test
    void filtersNotObjectRejected() throws Exception {
        String q = "{\"filters\":[]}";
        String err = QueryEntitiesQueryAdmission.validateOrErrorJson(j(q));
        assertEquals("INVALID_PREDICATE", M.readTree(err).path("code").asText());
        assertTrue(M.readTree(err).path("path").asText().contains("filters"));
    }

    @Test
    void inRequiresValues() throws Exception {
        String q = "{\"filters\":{\"type\":\"IN\",\"fieldName\":\"n\",\"values\":[]}}";
        String err = QueryEntitiesQueryAdmission.validateOrErrorJson(j(q));
        assertEquals("INVALID_PREDICATE", M.readTree(err).path("code").asText());
    }

    @Test
    void textualQueryObjectEqPasses() throws Exception {
        JsonNode text = TextNode.valueOf("{\"filters\":{\"type\":\"EQ\",\"fieldName\":\"name\",\"value\":\"x\"}}");
        assertNull(QueryEntitiesQueryAdmission.validateOrErrorJson(text));
    }

    @Test
    void textualQueryCompositeNotRejected() throws Exception {
        JsonNode text = TextNode.valueOf(
                "{\"filters\":{\"type\":\"NOT\",\"filters\":[{\"type\":\"EQ\",\"fieldName\":\"n\",\"value\":\"1\"}]}}");
        String err = QueryEntitiesQueryAdmission.validateOrErrorJson(text);
        assertEquals("UNSUPPORTED_PREDICATE_FOR_QUERY_ENTITIES", M.readTree(err).path("code").asText());
    }

    @Test
    void eqWithoutValueRejected() throws Exception {
        String q = "{\"filters\":{\"type\":\"EQ\",\"fieldName\":\"name\"}}";
        String err = QueryEntitiesQueryAdmission.validateOrErrorJson(j(q));
        assertEquals("INVALID_PREDICATE", M.readTree(err).path("code").asText());
        assertTrue(M.readTree(err).path("path").asText().contains("value"));
    }

    @Test
    void taggedWithoutTagsRejected() throws Exception {
        String q = "{\"filters\":{\"type\":\"TAGGED\",\"fieldName\":\"tags\"}}";
        String err = QueryEntitiesQueryAdmission.validateOrErrorJson(j(q));
        assertEquals("INVALID_PREDICATE", M.readTree(err).path("code").asText());
    }

    @Test
    void matchesNotInV1Allowlist() throws Exception {
        String q = "{\"filters\":{\"type\":\"MATCHES\",\"fieldName\":\"name\",\"expression\":\".*\"}}";
        String err = QueryEntitiesQueryAdmission.validateOrErrorJson(j(q));
        assertEquals("UNSUPPORTED_OPERATOR", M.readTree(err).path("code").asText());
    }

    @Test
    void missingValueLeafPassesWithoutValue() throws Exception {
        String q = "{\"filters\":{\"type\":\"MISSINGVALUE\",\"fieldName\":\"name\"}}";
        assertNull(QueryEntitiesQueryAdmission.validateOrErrorJson(j(q)));
    }

    @Test
    void tooManyLeavesUsesTooManyPredicates() throws Exception {
        StringBuilder sb = new StringBuilder("{\"filters\":{\"type\":\"OR\",\"filters\":[");
        for (int i = 0; i < 40; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"type\":\"EQ\",\"fieldName\":\"f").append(i).append("\",\"value\":1}");
        }
        sb.append("]}}");
        String err = QueryEntitiesQueryAdmission.validateOrErrorJson(j(sb.toString()));
        assertEquals("TOO_MANY_PREDICATES", M.readTree(err).path("code").asText());
    }
}
