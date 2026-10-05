package com.thingworx.things.agent.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class InfoTableMatrixCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void encode_largeInfotable_appliesMatrixV1AndMeetsThreshold() throws Exception {
        StringBuilder longVal = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            longVal.append('z');
        }
        String pad = longVal.toString();
        StringBuilder rows = new StringBuilder();
        rows.append("\"rows\":[");
        for (int r = 0; r < 40; r++) {
            if (r > 0) {
                rows.append(',');
            }
            rows.append("{\"thingName\":\"T").append(r).append("\",\"region\":\"US-East\",\"payload\":\"")
                    .append(pad).append(r % 7).append("\"}");
        }
        rows.append(']');
        String body = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"rowCount\":40,\"columns\":["
                + "{\"name\":\"thingName\",\"baseType\":\"THINGNAME\"},"
                + "{\"name\":\"region\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"payload\",\"baseType\":\"STRING\"}],"
                + rows + "}";
        String out = InfoTableMatrixCodec.encodeIfEligible(body, null);
        assertFalse(out.equals(body));
        JsonNode root = MAPPER.readTree(out);
        assertEquals(InfoTableMatrixCodec.FORMAT_MATRIX_V1, root.path("$format").asText());
        assertEquals("US-East", root.path("constants").path("region").asText());
        assertTrue(root.path("rows").isArray());
        assertEquals(40, root.path("rows").size());
        assertTrue(root.path("columns").isArray());
        assertEquals(2, root.path("columns").size());
    }

    @Test
    void encode_smallTable_unchangedWhenBelowSavingsThreshold() {
        String body = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"columns\":[{\"name\":\"a\",\"baseType\":\"STRING\"}],"
                + "\"rows\":[{\"a\":\"1\"},{\"a\":\"2\"}]}";
        assertEquals(body, InfoTableMatrixCodec.encodeIfEligible(body, null));
    }

    @Test
    void encode_passwordColumn_skipped() {
        String body = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\","
                + "\"columns\":[{\"name\":\"secret\",\"baseType\":\"PASSWORD\"}],\"rows\":[{\"secret\":\"x\"}]}";
        assertEquals(body, InfoTableMatrixCodec.encodeIfEligible(body, null));
    }

    @Test
    void encode_alreadyMatrix_unchanged() throws Exception {
        String body = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"$format\":\"parler.infotable.matrix.v1\","
                + "\"columns\":[{\"name\":\"a\",\"baseType\":\"STRING\"}],\"rows\":[[\"1\"]]}";
        assertEquals(body, InfoTableMatrixCodec.encodeIfEligible(body, null));
    }

    @Test
    void encode_entityTaxonomy_rootEntityList_appliesMatrixV1() throws Exception {
        StringBuilder longVal = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            longVal.append('q');
        }
        String pad = longVal.toString();
        StringBuilder rel = new StringBuilder();
        rel.append("\"rootEntityList\":[");
        for (int r = 0; r < 40; r++) {
            if (r > 0) {
                rel.append(',');
            }
            rel.append("{\"name\":\"Thing").append(r).append("\",\"region\":\"US\",\"notes\":\"")
                    .append(pad).append(r % 3).append("\"}");
        }
        rel.append(']');
        String body = "{\"status\":\"success\",\"resultKind\":\"ENTITY_TAXONOMY_QUERY_INLINE\",\"columns\":["
                + "{\"name\":\"name\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"region\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"notes\",\"baseType\":\"STRING\"}],"
                + rel + "}";
        String out = InfoTableMatrixCodec.encodeIfEligible(body, null);
        assertFalse(out.equals(body));
        JsonNode root = MAPPER.readTree(out);
        assertEquals(InfoTableMatrixCodec.FORMAT_MATRIX_V1, root.path("$format").asText());
        assertEquals("US", root.path("constants").path("region").asText());
        assertTrue(root.path("rootEntityList").isArray());
        assertEquals(40, root.path("rootEntityList").size());
    }

    @Test
    void encode_entityTaxonomy_sampleRootEntityList_preservesKey() throws Exception {
        StringBuilder longVal = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            longVal.append('t');
        }
        String pad = longVal.toString();
        StringBuilder sample = new StringBuilder();
        sample.append("\"sampleRootEntityList\":[");
        for (int r = 0; r < 40; r++) {
            if (r > 0) {
                sample.append(',');
            }
            sample.append("{\"name\":\"Thing").append(r).append("\",\"region\":\"EU\",\"notes\":\"")
                    .append(pad).append(r % 3).append("\"}");
        }
        sample.append(']');
        String body = "{\"status\":\"success\",\"resultKind\":\"ENTITY_TAXONOMY_QUERY_LARGE\",\"cacheId\":\"cx\",\"columns\":["
                + "{\"name\":\"name\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"region\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"notes\",\"baseType\":\"STRING\"}],"
                + sample + "}";
        String out = InfoTableMatrixCodec.encodeIfEligible(body, null);
        assertFalse(out.equals(body));
        JsonNode root = MAPPER.readTree(out);
        assertEquals(InfoTableMatrixCodec.FORMAT_MATRIX_V1, root.path("$format").asText());
        assertEquals("EU", root.path("constants").path("region").asText());
        assertTrue(root.path("sampleRootEntityList").isArray());
        assertEquals(40, root.path("sampleRootEntityList").size());
    }

    @Test
    void encode_fetchCachedResult_withoutResultKind_appliesMatrixV1() throws Exception {
        // Same row/column bulk as encode_largeInfotable, but omit resultKind (fetch_cached_result envelope).
        StringBuilder longVal = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            longVal.append('z');
        }
        String pad = longVal.toString();
        StringBuilder rows = new StringBuilder();
        rows.append("\"rows\":[");
        for (int r = 0; r < 40; r++) {
            if (r > 0) {
                rows.append(',');
            }
            rows.append("{\"thingName\":\"T").append(r).append("\",\"region\":\"US-East\",\"payload\":\"")
                    .append(pad).append(r % 7).append("\"}");
        }
        rows.append(']');
        String body = "{\"status\":\"success\",\"cacheId\":\"c1\",\"offset\":0,\"returnedRows\":40,\"totalRows\":100,"
                + "\"hasMore\":false,\"columns\":["
                + "{\"name\":\"thingName\",\"baseType\":\"THINGNAME\"},"
                + "{\"name\":\"region\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"payload\",\"baseType\":\"STRING\"}],"
                + rows + "}";
        String out = InfoTableMatrixCodec.encodeIfEligible(body, null);
        assertFalse(out.equals(body));
        JsonNode root = MAPPER.readTree(out);
        assertEquals(InfoTableMatrixCodec.FORMAT_MATRIX_V1, root.path("$format").asText());
        assertEquals("US-East", root.path("constants").path("region").asText());
        assertTrue(root.path("rows").isArray());
        assertEquals(40, root.path("rows").size());
    }

    @Test
    void encode_entityList_inline_productionColumns_meetsThreshold() throws Exception {
        StringBuilder longVal = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            longVal.append('v');
        }
        String pad = longVal.toString();
        StringBuilder rows = new StringBuilder();
        rows.append("\"rows\":[");
        for (int r = 0; r < 40; r++) {
            if (r > 0) {
                rows.append(',');
            }
            rows.append("{\"name\":\"E").append(r).append("\",\"description\":\"")
                    .append(pad).append(r % 6).append("\",\"entityType\":\"ThingTemplate\",")
                    .append("\"tags\":\"x\"}");
        }
        rows.append(']');
        String body = "{\"status\":\"success\",\"resultKind\":\"ENTITY_LIST_INLINE\",\"columns\":["
                + "{\"name\":\"name\",\"baseType\":\"THINGNAME\"},"
                + "{\"name\":\"description\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"entityType\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"tags\",\"baseType\":\"TAGS\"}],"
                + rows + "}";
        String out = InfoTableMatrixCodec.encodeIfEligible(body, null);
        assertFalse(out.equals(body));
        JsonNode root = MAPPER.readTree(out);
        assertEquals(InfoTableMatrixCodec.FORMAT_MATRIX_V1, root.path("$format").asText());
        assertEquals("ThingTemplate", root.path("constants").path("entityType").asText());
    }

    @Test
    void encode_markdownBody_passesThroughUnchanged() {
        String body = "# Skill\n\nSome **markdown** tool result.";
        assertEquals(body, InfoTableMatrixCodec.encodeIfEligible(body, null));
    }

    @Test
    void encode_nullOrEmpty_passesThrough() {
        assertEquals(null, InfoTableMatrixCodec.encodeIfEligible(null, null));
        assertEquals("", InfoTableMatrixCodec.encodeIfEligible("", null));
    }
}
