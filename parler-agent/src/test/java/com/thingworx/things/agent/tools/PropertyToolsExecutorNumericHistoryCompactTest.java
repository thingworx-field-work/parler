package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

class PropertyToolsExecutorNumericHistoryCompactTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void compactNumericHistory_rawSeriesUsesSampleRowsAndCacheIdNoPoints() throws Exception {
        ObjectNode full = fullNumericHistory(32);
        ObjectNode compact = PropertyToolsExecutor.compactNumericHistoryResult(
                full, points(32), List.of(), storedCache("cache-1"), false, true);

        assertEquals("success", compact.path("status").asText());
        assertEquals(PropertyToolsExecutor.NUMERIC_HISTORY_COMPACT_FORMAT, compact.path("$format").asText());
        assertEquals("NUMERIC_HISTORY_INLINE", compact.path("resultKind").asText());
        assertEquals(32, compact.path("totalRows").asInt());
        assertEquals(20, compact.path("returnedRows").asInt());
        assertTrue(compact.path("sampleOnly").asBoolean());
        assertTrue(compact.path("rowsOmitted").asBoolean());
        assertEquals(2, compact.path("columns").size());
        assertEquals(20, compact.path("sampleRows").size());
        assertEquals("cache-1", compact.path("cacheId").asText());
        assertTrue(compact.path("chartEmitted").asBoolean());
        assertFalse(compact.has("points"), compact.toString());
        // CM-1: columns and roles come from the same store as the cacheId.
        assertEquals("timestamp", compact.path("columns").get(0).path("name").asText());
        assertEquals("value", compact.path("columns").get(1).path("name").asText());
        assertEquals("timestamp", compact.path("timeColumn").asText());
        assertEquals("value", compact.path("valueColumn").asText());
    }

    private static StoredSeriesCache storedCache(String cacheId) {
        return new StoredSeriesCache(cacheId, NumericHistoryCacheWriter.canonicalColumns(), "timestamp", "value");
    }

    @Test
    void compactNumericHistory_aggregatesCarryExactStatsAndNoRows() throws Exception {
        ObjectNode full = fullNumericHistory(232);
        ObjectNode aggregates = full.putObject("aggregates");
        aggregates.put("MEAN", 5.25);
        aggregates.put("COUNT", 232.0);

        ObjectNode compact = PropertyToolsExecutor.compactNumericHistoryResult(
                full, points(232), List.of(NumericSeriesAggregateAction.MEAN, NumericSeriesAggregateAction.COUNT),
                storedCache("cache-2"), false, false);

        assertEquals(PropertyToolsExecutor.NUMERIC_HISTORY_COMPACT_FORMAT, compact.path("$format").asText());
        assertEquals("NUMERIC_HISTORY_AGGREGATES", compact.path("resultKind").asText());
        assertEquals(232, compact.path("totalRows").asInt());
        assertEquals(0, compact.path("returnedRows").asInt());
        assertTrue(compact.path("sampleOnly").asBoolean());
        assertTrue(compact.path("rowsOmitted").asBoolean());
        assertEquals(0, compact.path("sampleRows").size());
        assertEquals(5.25, compact.path("aggregates").path("MEAN").asDouble(), 0.000001);
        assertEquals(232.0, compact.path("aggregates").path("COUNT").asDouble(), 0.000001);
        assertEquals("cache-2", compact.path("cacheId").asText());
        assertFalse(compact.path("chartEmitted").asBoolean());
        assertFalse(compact.has("points"), compact.toString());
    }

    @Test
    void compactNumericHistory_rawSeriesCanReportNoChartRegistration() throws Exception {
        ObjectNode compact = PropertyToolsExecutor.compactNumericHistoryResult(
                fullNumericHistory(12), points(12), List.of(), storedCache("cache-3"), false, false);

        assertEquals("NUMERIC_HISTORY_INLINE", compact.path("resultKind").asText());
        assertEquals(12, compact.path("totalRows").asInt());
        assertEquals(12, compact.path("returnedRows").asInt());
        assertFalse(compact.path("sampleOnly").asBoolean());
        assertFalse(compact.path("rowsOmitted").asBoolean());
        assertFalse(compact.path("chartEmitted").asBoolean());
        assertFalse(compact.has("points"), compact.toString());
    }

    private static ObjectNode fullNumericHistory(int count) {
        ObjectNode full = JSON.createObjectNode();
        full.put("status", "success");
        full.put("thingName", "Thing1");
        full.put("propertyName", "speed");
        full.set("points", pointsArray(count));
        full.putObject("requested_time_range").put("start", "2026-06-04T00:00:00Z")
                .put("end", "2026-06-05T00:00:00Z");
        return full;
    }

    private static List<ObjectNode> points(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(PropertyToolsExecutorNumericHistoryCompactTest::point)
                .toList();
    }

    private static ArrayNode pointsArray(int count) {
        ArrayNode arr = JSON.createArrayNode();
        for (int i = 0; i < count; i++) {
            arr.add(point(i));
        }
        return arr;
    }

    private static ObjectNode point(int i) {
        ObjectNode p = JSON.createObjectNode();
        p.put("timestamp", String.format("2026-06-04T00:%02d:00Z", i));
        p.put("value", i + 0.5d);
        return p;
    }
}
