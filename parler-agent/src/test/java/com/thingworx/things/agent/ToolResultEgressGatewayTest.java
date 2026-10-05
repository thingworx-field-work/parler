package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

class ToolResultEgressGatewayTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void publicTabularPolicyMatchesGatewayArraySampling() {
        assertEquals(20, ToolResultEgressGateway.llmArraySampleLimit());
        assertFalse(ToolResultEgressGateway.isLargeTabularResult(20));
        assertTrue(ToolResultEgressGateway.isLargeTabularResult(21));
    }

    @Test
    void tabularEvidencePlanAppliesInlineAndLargeShapes() {
        ToolResultEgressGateway.TabularEvidencePlan inline = ToolResultEgressGateway.planTabularEvidence(
                2, "ENTITY_QUERY_INLINE", "ENTITY_QUERY_LARGE", "rows", "sampleRows");
        ObjectNode inlineOut = JSON.createObjectNode();
        ArrayNode inlineRows = JSON.createArrayNode();
        inlineRows.addObject().put("name", "A");
        inlineRows.addObject().put("name", "B");
        ToolResultEgressGateway.applyTabularEvidence(inlineOut, inline, inlineRows, "cid-inline", "hint");
        assertEquals("ENTITY_QUERY_INLINE", inlineOut.path("resultKind").asText());
        assertTrue(inlineOut.has("rows"));
        assertFalse(inlineOut.has("sampleRows"));
        assertFalse(inlineOut.has("cacheId"));

        ToolResultEgressGateway.TabularEvidencePlan large = ToolResultEgressGateway.planTabularEvidence(
                21, "ENTITY_QUERY_INLINE", "ENTITY_QUERY_LARGE", "rows", "sampleRows");
        ObjectNode largeOut = JSON.createObjectNode();
        ToolResultEgressGateway.applyTabularEvidence(largeOut, large, JSON.createArrayNode(), "cid-large", "use cache");
        assertEquals("ENTITY_QUERY_LARGE", largeOut.path("resultKind").asText());
        assertTrue(largeOut.has("sampleRows"));
        assertEquals("cid-large", largeOut.path("cacheId").asText());
        assertEquals(20, large.rowsToEmit());
    }

    @Test
    void compactForLlmAppend_preservesAggregatesAndSamplesOversizedPoints() throws Exception {
        String raw = numericAggregateResult(232);

        ToolResultEgressGateway.EgressResult out =
                ToolResultEgressGateway.compactForLlmAppend("query_property_history", "call-1", raw, null);

        assertTrue(out.isCompacted());
        JsonNode root = JSON.readTree(out.getLlmContent());
        assertEquals("success", root.path("status").asText());
        assertEquals("NUMERIC_HISTORY", root.path("resultKind").asText());
        assertEquals(5.323706896551724, root.path("aggregates").path("MEAN").asDouble(), 0.000001);
        assertEquals(232.0, root.path("aggregates").path("COUNT").asDouble(), 0.000001);
        assertEquals(20, root.path("points").size());
        assertTrue(root.path("sampleOnly").asBoolean());
        assertTrue(root.path("rowsOmitted").asBoolean());
        assertEquals(232, root.path("_egress").path("reducedFields").path("points").path("originalCount").asInt());
        assertTrue(out.getLlmContent().length() < raw.length());
        assertFalse(out.getLlmContent().contains("marker-021"));
    }

    @Test
    void compactForLlmAppend_leavesSmallToolResultUnchanged() {
        String raw = "{\"status\":\"success\",\"rows\":[{\"x\":1}]}";

        ToolResultEgressGateway.EgressResult out =
                ToolResultEgressGateway.compactForLlmAppend("small_tool", "call-2", raw, null);

        assertFalse(out.isCompacted());
        assertEquals(raw, out.getLlmContent());
    }

    @Test
    void compactForLlmAppend_samplesBeneficialOversizedArrayEvenWhenBelowCharCap() throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("status", "success");
        ArrayNode rows = root.putArray("rows");
        for (int i = 0; i < 80; i++) {
            rows.addObject()
                    .put("i", i)
                    .put("label", String.format("row-%03d", i))
                    .put("description", "row-count egress below soft cap");
        }
        String raw = JSON.writeValueAsString(root);

        ToolResultEgressGateway.EgressResult out =
                ToolResultEgressGateway.compactForLlmAppend("rows_tool", "call-3", raw, null);

        assertTrue(raw.length() < ToolResultEgressGateway.LLM_EVIDENCE_CHARS_SOFT_CAP);
        assertTrue(out.isCompacted());
        JsonNode compact = JSON.readTree(out.getLlmContent());
        assertEquals(20, compact.path("rows").size());
        assertEquals(80, compact.path("_egress").path("reducedFields").path("rows").path("originalCount").asInt());
    }

    @Test
    void compactForLlmAppend_leavesMediumPlainTextUnchanged() {
        String raw = "plain [ diagnostic ] text";

        ToolResultEgressGateway.EgressResult out =
                ToolResultEgressGateway.compactForLlmAppend("text_tool", "call-4", raw, null);

        assertFalse(out.isCompacted());
        assertEquals(raw, out.getLlmContent());
    }

    @Test
    void compactForLlmAppend_lastResortKeepsPriorityFieldsUnderCap() throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("status", "success");
        root.put("resultKind", "WIDE_OBJECT");
        root.putObject("aggregates").put("COUNT", 500);
        for (int i = 0; i < 500; i++) {
            root.put(String.format("field_%03d", i), "small-but-too-many-values-" + i);
        }
        String raw = JSON.writeValueAsString(root);

        ToolResultEgressGateway.EgressResult out =
                ToolResultEgressGateway.compactForLlmAppend("wide_tool", "call-5", raw, null);

        assertTrue(out.isCompacted());
        assertTrue(out.getLlmContent().length() < ToolResultEgressGateway.LLM_EVIDENCE_CHARS_SOFT_CAP,
                out.getLlmContent().length() + " chars");
        JsonNode compact = JSON.readTree(out.getLlmContent());
        assertEquals("success", compact.path("status").asText());
        assertEquals("WIDE_OBJECT", compact.path("resultKind").asText());
        assertEquals(500, compact.path("aggregates").path("COUNT").asInt());
        assertTrue(compact.path("_egress").path("reducedFields").size() > 0);
    }

    @Test
    void compactForLlmAppend_preservesColumnsSchemaArray() throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("status", "success");
        root.put("resultKind", "INFOTABLE_LARGE");
        ArrayNode columns = root.putArray("columns");
        for (int i = 0; i < 30; i++) {
            columns.addObject().put("name", "col_" + i).put("baseType", "STRING");
        }
        ArrayNode rows = root.putArray("rows");
        for (int i = 0; i < 80; i++) {
            ObjectNode row = rows.addObject();
            row.put("col_0", "row_" + i);
            row.put("description", "large row payload to make row sampling beneficial");
        }
        String raw = JSON.writeValueAsString(root);

        ToolResultEgressGateway.EgressResult out =
                ToolResultEgressGateway.compactForLlmAppend("wide_table", "call-columns", raw, null);

        assertTrue(out.isCompacted());
        JsonNode compact = JSON.readTree(out.getLlmContent());
        assertEquals(30, compact.path("columns").size());
        assertEquals(20, compact.path("rows").size());
        assertFalse(compact.path("_egress").path("reducedFields").has("columns"),
                compact.path("_egress").toString());
        assertEquals(80, compact.path("_egress").path("reducedFields").path("rows").path("originalCount").asInt());
    }

    @Test
    void compactForLlmAppend_samplesUnknownDataArrayButPreservesSchemaArrays() throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("status", "success");
        ArrayNode fieldDefinitions = root.putArray("fieldDefinitions");
        for (int i = 0; i < 30; i++) {
            fieldDefinitions.addObject().put("name", "schema_" + i);
        }
        ArrayNode items = root.putArray("items");
        for (int i = 0; i < 120; i++) {
            items.addObject()
                    .put("id", "item_" + i)
                    .put("description", "unknown data array item that should be sampled, not omitted");
        }
        String raw = JSON.writeValueAsString(root);

        ToolResultEgressGateway.EgressResult out =
                ToolResultEgressGateway.compactForLlmAppend("unknown_array_tool", "call-items", raw, null);

        assertTrue(out.isCompacted());
        JsonNode compact = JSON.readTree(out.getLlmContent());
        assertEquals(20, compact.path("items").size());
        assertEquals(30, compact.path("fieldDefinitions").size());
        assertEquals(120, compact.path("_egress").path("reducedFields").path("items").path("originalCount").asInt());
        assertFalse(compact.path("_egress").path("reducedFields").has("fieldDefinitions"));
    }

    @Test
    void compactForLlmAppend_compactsScalarJsonAboveSoftCap() throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("status", "success");
        root.put("resultKind", "LARGE_SCALAR_JSON");
        root.putObject("aggregates").put("COUNT", 1);
        root.put("details", "x".repeat(ToolResultEgressGateway.LLM_EVIDENCE_CHARS_SOFT_CAP + 50));
        String raw = JSON.writeValueAsString(root);

        ToolResultEgressGateway.EgressResult out =
                ToolResultEgressGateway.compactForLlmAppend("scalar_tool", "call-soft-cap", raw, null);

        assertTrue(out.isCompacted());
        assertTrue(out.getLlmContent().length() < raw.length());
        JsonNode compact = JSON.readTree(out.getLlmContent());
        assertEquals(1, compact.path("aggregates").path("COUNT").asInt());
        assertTrue(compact.path("_egress").path("reducedFields").path("truncatedTextFields").asBoolean());
    }

    static String numericAggregateResult(int pointCount) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("status", "success");
        root.put("resultKind", "NUMERIC_HISTORY");
        root.put("thingName", "SE.CellFab.Model.Workunit.MUC-JetDryer-01");
        root.put("propertyName", "dryingSpeed");
        ObjectNode aggregates = root.putObject("aggregates");
        aggregates.put("MEAN", 5.323706896551724);
        aggregates.put("MIN", 0.07);
        aggregates.put("MAX", 9.97);
        aggregates.put("COUNT", 232.0);
        ArrayNode points = root.putArray("points");
        for (int i = 0; i < pointCount; i++) {
            ObjectNode p = points.addObject();
            p.put("timestamp", String.format("2026-06-04T00:%02d:00Z", i));
            p.put("value", (i % 100) / 10.0d);
            p.put("quality", "GOOD");
            p.put("marker", String.format("marker-%03d", i));
            p.put("source", "query_property_history_live_fixture");
        }
        return JSON.writeValueAsString(root);
    }
}
