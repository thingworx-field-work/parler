package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Offline tests for {@link QueryJsonPrimitiveMapper#validateQueryObject} and {@link QueryJsonPrimitiveMapper#parseQueryObject} — no {@code JSONPrimitive}. */
class QueryJsonPayloadValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parseStructuredQueryDoesNotNeedJsonPrimitive() throws Exception {
        var node = MAPPER.readTree("{\"filters\":{}}");
        JSONObject jo = QueryJsonPrimitiveMapper.parseQueryObject("query", node, MAPPER);
        assertTrue(jo.has("filters"));
    }

    @Test
    void noPaginationNoOp() {
        assertDoesNotThrow(() -> QueryJsonPrimitiveMapper.validateQueryObject(new JSONObject("{\"filters\":{}}")));
    }

    @Test
    void paginationAbsentNoOp() {
        assertDoesNotThrow(() -> QueryJsonPrimitiveMapper.validateQueryObject(new JSONObject("{}"), "query"));
    }

    @Test
    void paginationPositiveOk() {
        assertDoesNotThrow(() -> QueryJsonPrimitiveMapper.validateQueryObject(
                new JSONObject("{\"pagination\":{\"pageSize\":10,\"pageNumber\":1}}")));
    }

    /** Locks lenience for JSON {@code 10.0}-style values (integral doubles); model should still prefer integer literals. */
    @Test
    void pageSizeIntegralDoubleAccepted() {
        JSONObject q = new JSONObject("{\"pagination\":{\"pageSize\":10.0,\"pageNumber\":1}}");
        assertDoesNotThrow(() -> QueryJsonPrimitiveMapper.validateQueryObject(q, "query"));
    }

    @Test
    void pageSizeZeroRejected() {
        JSONObject q = new JSONObject("{\"pagination\":{\"pageSize\":0,\"pageNumber\":1}}");
        assertThrows(IllegalArgumentException.class, () -> QueryJsonPrimitiveMapper.validateQueryObject(q, "query"));
    }

    @Test
    void pageNumberZeroRejected() {
        JSONObject q = new JSONObject("{\"pagination\":{\"pageSize\":10,\"pageNumber\":0}}");
        assertThrows(IllegalArgumentException.class, () -> QueryJsonPrimitiveMapper.validateQueryObject(q, "query"));
    }

    @Test
    void negativePageSizeRejected() {
        JSONObject q = new JSONObject("{\"pagination\":{\"pageSize\":-1,\"pageNumber\":1}}");
        assertThrows(IllegalArgumentException.class, () -> QueryJsonPrimitiveMapper.validateQueryObject(q, "query"));
    }

    @Test
    void paginationStringRejected() {
        JSONObject q = new JSONObject("{\"pagination\":\"5\"}");
        assertThrows(IllegalArgumentException.class, () -> QueryJsonPrimitiveMapper.validateQueryObject(q, "query"));
    }

    @Test
    void paginationArrayRejected() {
        JSONObject q = new JSONObject("{\"pagination\":[10,1]}");
        assertThrows(IllegalArgumentException.class, () -> QueryJsonPrimitiveMapper.validateQueryObject(q, "query"));
    }

    @Test
    void paginationNumberRejected() {
        JSONObject q = new JSONObject("{\"pagination\":5}");
        assertThrows(IllegalArgumentException.class, () -> QueryJsonPrimitiveMapper.validateQueryObject(q, "query"));
    }

    @Test
    void pageSizeDecimalRejected() {
        JSONObject q = new JSONObject("{\"pagination\":{\"pageSize\":1.5,\"pageNumber\":1}}");
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> QueryJsonPrimitiveMapper.validateQueryObject(q, "query"));
        assertTrue(ex.getMessage().contains("pageSize") || ex.getMessage().contains("fractional"));
    }

    @Test
    void pageSizeDecimalStringRejected() {
        JSONObject q = new JSONObject("{\"pagination\":{\"pageSize\":\"1.5\",\"pageNumber\":1}}");
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> QueryJsonPrimitiveMapper.validateQueryObject(q, "query"));
        assertTrue(ex.getMessage().contains("pageSize") || ex.getMessage().contains("decimal") || ex.getMessage().contains("reject"));
    }

    @Test
    void pageSizeBooleanRejected() {
        JSONObject q = new JSONObject("{\"pagination\":{\"pageSize\":true,\"pageNumber\":1}}");
        assertThrows(IllegalArgumentException.class, () -> QueryJsonPrimitiveMapper.validateQueryObject(q, "query"));
    }
}
