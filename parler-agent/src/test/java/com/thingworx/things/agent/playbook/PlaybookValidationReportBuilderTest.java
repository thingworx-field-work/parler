package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ToolDefinition;

class PlaybookValidationReportBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void validDocument_emitsValidStatus() throws Exception {
        PlaybookDocument doc = loadFixture("cross_region_health");
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, minimalToolDefs());
        assertTrue(r.valid(), () -> String.join("; ", r.errors()));
        PlaybookValidationReport report =
                PlaybookValidationReportBuilder.build(doc, r, doc.playbookId(), "0.1.190");
        assertEquals(PlaybookValidationReport.Status.VALID, report.status());
        JsonNode json = MAPPER.readTree(report.toJson());
        assertEquals("valid", json.get("status").asText());
        assertTrue(json.get("errors").isEmpty());
    }

    @Test
    void unsupportedDeriveOp_mapsStructuredCode() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"id\":\"t\",\"title\":\"T\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"bad\",\"kind\":\"derive\",\"op\":\"window\",\"dependsOn\":[],\"args\":{}},"
                + "{\"id\":\"fin\",\"kind\":\"llm_summary\",\"dependsOn\":[\"bad\"],\"evidenceRefs\":[\"bad\"]}"
                + "],\"finalNode\":\"fin\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, minimalToolDefs());
        assertFalse(r.valid());
        PlaybookValidationReport report = PlaybookValidationReportBuilder.build(doc, r, null, "0.1.190");
        JsonNode json = MAPPER.readTree(report.toJson());
        assertEquals("invalid", json.get("status").asText());
        JsonNode err = json.get("errors").get(0);
        assertEquals("UNSUPPORTED_DERIVE_OP", err.get("code").asText());
        assertEquals("bad", err.get("nodeId").asText());
        assertTrue(err.get("path").asText().contains("nodes["));
    }

    @Test
    void scalarGroupByKeys_mapsRecoveryHint() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"id\":\"t\",\"title\":\"T\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"g\",\"kind\":\"derive\",\"op\":\"group_by\",\"dependsOn\":[],"
                + "\"args\":{\"rows\":{\"$ref\":\"x.rows\"},\"keys\":\"status\",\"maxGroups\":10}},"
                + "{\"id\":\"fin\",\"kind\":\"llm_summary\",\"dependsOn\":[\"g\"],\"evidenceRefs\":[\"g\"]}"
                + "],\"finalNode\":\"fin\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, minimalToolDefs());
        assertFalse(r.valid());
        PlaybookValidationReport report = PlaybookValidationReportBuilder.build(doc, r, null, "0.1.190");
        JsonNode err = MAPPER.readTree(report.toJson()).get("errors").get(0);
        assertEquals("INVALID_GROUP_BY_KEYS", err.get("code").asText());
        assertTrue(err.has("recoveryHint"));
        assertEquals("fix_group_by_keys", err.get("recoveryHint").get("action").asText());
        assertFalse(err.get("recoveryHint").has("recoveryHint"));
        assertTrue(err.get("path").asText().endsWith(".args.keys"));
    }

    @Test
    void dottedGroupByKeyElement_mapsInvalidGroupByKeys() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"id\":\"t\",\"title\":\"T\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"g\",\"kind\":\"derive\",\"op\":\"group_by\",\"dependsOn\":[],"
                + "\"args\":{\"rows\":{\"$ref\":\"x.rows\"},\"keys\":[\"asset.status\"],\"maxGroups\":10}},"
                + "{\"id\":\"fin\",\"kind\":\"llm_summary\",\"dependsOn\":[\"g\"],\"evidenceRefs\":[\"g\"]}"
                + "],\"finalNode\":\"fin\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, minimalToolDefs());
        PlaybookValidationReport report = PlaybookValidationReportBuilder.build(doc, r, null, "0.1.190");
        JsonNode err = MAPPER.readTree(report.toJson()).get("errors").get(0);
        assertEquals("INVALID_GROUP_BY_KEYS", err.get("code").asText());
        assertEquals("g", err.get("nodeId").asText());
        assertTrue(err.get("path").asText().contains(".args.keys[0]"));
    }

    @Test
    void unknownTool_usesToolCallNodeId() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"id\":\"t\",\"title\":\"T\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"tc\",\"kind\":\"tool_call\",\"tool\":\"query_foo\",\"dependsOn\":[],\"args\":{}},"
                + "{\"id\":\"fin\",\"kind\":\"llm_summary\",\"dependsOn\":[\"tc\"],\"evidenceRefs\":[\"tc\"]}"
                + "],\"finalNode\":\"fin\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, minimalToolDefs());
        PlaybookValidationReport report = PlaybookValidationReportBuilder.build(doc, r, null, "0.1.190");
        JsonNode err = MAPPER.readTree(report.toJson()).get("errors").get(0);
        assertEquals("UNKNOWN_TOOL", err.get("code").asText());
        assertEquals("tc", err.get("nodeId").asText());
    }

    @Test
    void unsupportedNodeKind_mapsCodeAndNodeId() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"id\":\"t\",\"title\":\"T\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"n1\",\"kind\":\"foo\",\"dependsOn\":[],\"args\":{}},"
                + "{\"id\":\"fin\",\"kind\":\"llm_summary\",\"dependsOn\":[\"n1\"],\"evidenceRefs\":[\"n1\"]}"
                + "],\"finalNode\":\"fin\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, minimalToolDefs());
        PlaybookValidationReport report = PlaybookValidationReportBuilder.build(doc, r, null, "0.1.190");
        JsonNode err = MAPPER.readTree(report.toJson()).get("errors").get(0);
        assertEquals("UNSUPPORTED_NODE_KIND", err.get("code").asText());
        assertEquals("n1", err.get("nodeId").asText());
    }

    @Test
    void missingFinalNode_mapsInvalidFinalNode() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"id\":\"t\",\"title\":\"T\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"fin\",\"kind\":\"llm_summary\",\"dependsOn\":[],\"evidenceRefs\":[]}"
                + "]}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, minimalToolDefs());
        PlaybookValidationReport report = PlaybookValidationReportBuilder.build(doc, r, null, "0.1.190");
        JsonNode err = MAPPER.readTree(report.toJson()).get("errors").get(0);
        assertEquals("INVALID_FINAL_NODE", err.get("code").asText());
        assertEquals("finalNode", err.get("path").asText());
    }

    @Test
    void invalidJsonReport_hasStableCode() throws Exception {
        PlaybookValidationReport report =
                PlaybookValidationReport.invalidJson("Expected ':' at 4", "pkg_a", "0.1.190");
        JsonNode json = MAPPER.readTree(report.toJson());
        assertEquals("INVALID_JSON", json.get("errors").get(0).get("code").asText());
        assertEquals("pkg_a", json.get("playbookId").asText());
    }

    private static PlaybookDocument loadFixture(String id) throws Exception {
        return PlaybookDocument.parse(loadFixtureText(id));
    }

    private static String loadFixtureText(String id) throws Exception {
        String path = "/playbook-packaging-fixture/" + id + "/playbook.json";
        try (InputStream in = PlaybookValidationReportBuilderTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<ToolDefinition> minimalToolDefs() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        return List.of(
                new ToolDefinition("resolve_thing", "x", schema, true),
                new ToolDefinition("query_entities_by_taxonomy", "x", schema, true),
                new ToolDefinition("query_alert_summary", "x", schema, true),
                new ToolDefinition("get_property_values", "x", schema, true),
                new ToolDefinition("query_property_history", "x", schema, true));
    }
}
