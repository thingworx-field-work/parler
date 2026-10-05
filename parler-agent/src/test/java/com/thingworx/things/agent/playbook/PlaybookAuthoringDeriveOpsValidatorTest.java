package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ToolDefinition;

/**
 * Validator and structured-report coverage for Slice E authoring derive ops (§9.4).
 */
class PlaybookAuthoringDeriveOpsValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void matchCandidates_invalidOnZero_mapsStructuredCode() throws Exception {
        PlaybookDocument doc = parse(minimalPlaybook("mc", "match_candidates",
                "{\"needle\":\"x\",\"rows\":{\"$ref\":\"a.rows\"},\"onZero\":\"bogus\"}"));
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, minimalToolDefs());
        assertFalse(r.valid());
        JsonNode err = firstError(doc, r);
        assertEquals("INVALID_MATCH_CANDIDATES_MODE", err.get("code").asText());
        assertEquals("mc", err.get("nodeId").asText());
        assertTrue(err.get("path").asText().endsWith(".args.onZero"));
        assertEquals("fix_match_candidates_mode", err.get("recoveryHint").get("action").asText());
    }

    @Test
    void limitRows_negativeMaxRows_mapsStructuredCode() throws Exception {
        PlaybookDocument doc = parse(minimalPlaybook("lr", "limit_rows",
                "{\"rows\":{\"$ref\":\"a.rows\"},\"maxRows\":-1}"));
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, minimalToolDefs());
        assertFalse(r.valid());
        JsonNode err = firstError(doc, r);
        assertEquals("INVALID_LIMIT_ROWS_MAX", err.get("code").asText());
        assertTrue(err.get("path").asText().endsWith(".args.maxRows"));
        assertEquals("fix_limit_rows_max", err.get("recoveryHint").get("action").asText());
    }

    @Test
    void formatEvidenceLines_zeroMaxLines_mapsStructuredCode() throws Exception {
        PlaybookDocument doc = parse(minimalPlaybook("fe", "format_evidence_lines",
                "{\"rows\":{\"$ref\":\"a.rows\"},\"template\":\"{x}\",\"maxLines\":0}"));
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, minimalToolDefs());
        assertFalse(r.valid());
        JsonNode err = firstError(doc, r);
        assertEquals("INVALID_FORMAT_EVIDENCE_MAX_LINES", err.get("code").asText());
        assertTrue(err.get("path").asText().endsWith(".args.maxLines"));
    }

    @Test
    void dedupe_scalarKeys_mapsRecoveryHint() throws Exception {
        PlaybookDocument doc = parse(minimalPlaybook("dd", "dedupe",
                "{\"rows\":{\"$ref\":\"a.rows\"},\"keys\":\"name\"}"));
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, minimalToolDefs());
        assertFalse(r.valid());
        JsonNode err = firstError(doc, r);
        assertEquals("INVALID_DEDUPE_KEYS", err.get("code").asText());
        assertTrue(err.get("path").asText().endsWith(".args.keys"));
        JsonNode hint = err.get("recoveryHint");
        assertEquals("fix_dedupe_keys", hint.get("action").asText());
        assertFalse(hint.has("recoveryHint"), "hint must not be double-nested");
        assertTrue(hint.has("example"));
    }

    @Test
    void dedupe_dottedKeyElement_mapsInvalidDedupeKeys() throws Exception {
        PlaybookDocument doc = parse(minimalPlaybook("dd", "dedupe",
                "{\"rows\":{\"$ref\":\"a.rows\"},\"keys\":[\"asset.name\"]}"));
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, minimalToolDefs());
        assertFalse(r.valid());
        JsonNode err = firstError(doc, r);
        assertEquals("INVALID_DEDUPE_KEYS", err.get("code").asText());
        assertTrue(err.get("path").asText().contains(".args.keys[0]"));
        assertEquals("fix_dedupe_keys", err.get("recoveryHint").get("action").asText());
    }

    @Test
    void dedupe_duplicateKey_mapsInvalidDedupeKeys() throws Exception {
        PlaybookDocument doc = parse(minimalPlaybook("dd", "dedupe",
                "{\"rows\":{\"$ref\":\"a.rows\"},\"keys\":[\"name\",\"name\"]}"));
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, minimalToolDefs());
        assertFalse(r.valid());
        JsonNode err = firstError(doc, r);
        assertEquals("INVALID_DEDUPE_KEYS", err.get("code").asText());
    }

    @Test
    void groupBy_scalarKeys_mapsFlatRecoveryHint() throws Exception {
        PlaybookDocument doc = parse(minimalPlaybook("gb", "group_by",
                "{\"rows\":{\"$ref\":\"a.rows\"},\"keys\":\"status\",\"maxGroups\":10}"));
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, minimalToolDefs());
        assertFalse(r.valid());
        JsonNode err = firstError(doc, r);
        assertEquals("INVALID_GROUP_BY_KEYS", err.get("code").asText());
        JsonNode hint = err.get("recoveryHint");
        assertEquals("fix_group_by_keys", hint.get("action").asText());
        assertFalse(hint.has("recoveryHint"), "hint must not be double-nested");
    }

    @Test
    void matchCandidates_dottedCandidateField_rejectsAtValidator() throws Exception {
        PlaybookDocument doc = parse(minimalPlaybook("mc", "match_candidates",
                "{\"needle\":\"x\",\"rows\":{\"$ref\":\"a.rows\"},\"candidateField\":\"asset.name\"}"));
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, minimalToolDefs());
        assertFalse(r.valid());
        JsonNode err = firstError(doc, r);
        assertEquals("INVALID_MATCH_CANDIDATES_FIELD", err.get("code").asText());
        assertTrue(err.get("path").asText().endsWith(".args.candidateField"));
    }

    @Test
    void normalizeText_invalidMode_mapsStructuredCode() throws Exception {
        PlaybookDocument doc = parse(minimalPlaybook("nt", "normalize_text",
                "{\"text\":\"x\",\"mode\":\"bogus\"}"));
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, minimalToolDefs());
        assertFalse(r.valid());
        JsonNode err = firstError(doc, r);
        assertEquals("INVALID_NORMALIZE_TEXT_MODE", err.get("code").asText());
    }

    private static JsonNode firstError(PlaybookDocument doc, PlaybookValidator.Result r) throws Exception {
        PlaybookValidationReport report = PlaybookValidationReportBuilder.build(doc, r, null, "0.1.190");
        return MAPPER.readTree(report.toJson()).get("errors").get(0);
    }

    private static PlaybookDocument parse(String raw) throws Exception {
        return PlaybookDocument.parse(raw);
    }

    private static String minimalPlaybook(String nodeId, String op, String argsJson) {
        return "{\"schema\":\"parler-playbook-v1\",\"id\":\"t\",\"title\":\"T\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"" + nodeId + "\",\"kind\":\"derive\",\"op\":\"" + op + "\",\"dependsOn\":[],\"args\":"
                + argsJson + "},"
                + "{\"id\":\"fin\",\"kind\":\"llm_summary\",\"dependsOn\":[\"" + nodeId + "\"],\"evidenceRefs\":[\""
                + nodeId + "\"]}"
                + "],\"finalNode\":\"fin\"}";
    }

    private static List<ToolDefinition> minimalToolDefs() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        ToolDefinition td = new ToolDefinition("query_entities", "q", schema, true);
        return List.of(td);
    }
}
