package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.configrepo.ExtendedToolDefinition;
import com.thingworx.things.agent.configrepo.ExtendedToolRegistrySnapshot;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.tools.BuiltInTools;
import com.thingworx.things.agent.tools.ToolRegistry;

class PlaybookValidatorTest {

    @Test
    void playbookJsonRoot_rejectsProviderField() {
        PlaybookValidator.Result r = PlaybookValidator.validatePlaybookJsonRoot(new JSONObject().put("provider", "x"));
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("provider"), String.join("; ", r.errors()));
    }

    /** Minimal defs matching dev_data health playbooks (built-in tool names only). */
    private static List<ToolDefinition> playbookToolDefsForHealthPlaybooks() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        return List.of(
                new ToolDefinition("resolve_thing", "x", schema, true),
                new ToolDefinition("query_entities_by_taxonomy", "x", schema, true),
                new ToolDefinition("query_alert_summary", "x", schema, true),
                new ToolDefinition("get_property_values", "x", schema, true),
                new ToolDefinition("query_property_history", "x", schema, true));
    }

    @Test
    void crossRegionHealthDocument_passesV1aGuards() throws Exception {
        String raw = readPackagingFixture("cross_region_health");
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result result = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertTrue(result.valid(), String.join("; ", result.errors()));
    }

    @Test
    void v1aDeriveOps_allowlistsSummarizeCurrentValuesByRegion() throws Exception {
        Field f = PlaybookValidator.class.getDeclaredField("V1A_DERIVE_OPS");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> ops = (Set<String>) f.get(null);
        assertTrue(ops.contains("summarize_current_values_by_region"));
    }

    @Test
    void playbookToolAllowlist_matchesMergedPlaybookSafeBuiltIns() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        List<ToolDefinition> merged = PlaybookToolDefinitionsMerge.merge(reg, ExtendedToolRegistrySnapshot.missing());
        Set<String> safe = merged.stream()
                .filter(ToolDefinition::isPlaybookSafe)
                .map(ToolDefinition::getName)
                .collect(Collectors.toSet());
        assertEquals(safe, PlaybookToolAllowlist.TOOL_NAMES);
        assertTrue(PlaybookToolAllowlist.isAllowed("invoke_service"));
        assertTrue(PlaybookToolAllowlist.isAllowed("query_numeric_property_history"));
        assertTrue(PlaybookToolAllowlist.isAllowed("query_value_stream_property_history"));
        assertFalse(PlaybookToolAllowlist.isAllowed("set_property_value"));
        assertFalse(PlaybookToolAllowlist.isAllowed("start_playbook"));
    }

    @Test
    void executorAliasQueryNumericPropertyHistory_passesValidationWithMergedDefs() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        List<ToolDefinition> tools = PlaybookToolDefinitionsMerge.merge(reg, ExtendedToolRegistrySnapshot.missing());
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"n1\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_numeric_property_history\","
                + "\"args\":{}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"n1\"],\"evidenceRefs\":[\"n1\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw), tools);
        assertTrue(r.valid(), String.join("; ", r.errors()));
    }

    @Test
    void executorAliasQueryValueStreamPropertyHistory_passesValidationWithMergedDefs() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        List<ToolDefinition> tools = PlaybookToolDefinitionsMerge.merge(reg, ExtendedToolRegistrySnapshot.missing());
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"n1\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_value_stream_property_history\","
                + "\"args\":{}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"n1\"],\"evidenceRefs\":[\"n1\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw), tools);
        assertTrue(r.valid(), String.join("; ", r.errors()));
    }

    @Test
    void toolCall_startPlaybook_rejectedAsUnknownTool_withMergedBuiltIns() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        List<ToolDefinition> tools = PlaybookToolDefinitionsMerge.merge(reg, ExtendedToolRegistrySnapshot.missing());
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"n1\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"start_playbook\","
                + "\"args\":{}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"n1\"],\"evidenceRefs\":[\"n1\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw), tools);
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("unknown tool"), String.join("; ", r.errors()));
    }

    @Test
    void devDataDirectoryPackages_passValidation() throws Exception {
        for (String id : List.of("cross_region_health", "cross_asset_pair_health")) {
            String raw = Files.readString(repoRoot().resolve("dev_data/playbooks")
                    .resolve(id).resolve("playbook.json"), StandardCharsets.UTF_8);
            PlaybookDocument doc = PlaybookDocument.parse(raw);
            assertEquals(id, doc.playbookId());
            PlaybookValidator.Result result =
                    PlaybookValidator.validateDocument(doc, id, playbookToolDefsForHealthPlaybooks());
            assertTrue(result.valid(), String.join("; ", result.errors()));
        }
    }

    @Test
    void crossAssetPairHealthDocument_passesValidation() throws Exception {
        String raw = readPackagingFixture("cross_asset_pair_health");
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result result = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertTrue(result.valid(), String.join("; ", result.errors()));
    }

    @Test
    void normalizeResolvedThing_invalidSourceNode_rejected() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        List<ToolDefinition> tools = PlaybookToolDefinitionsMerge.merge(reg, ExtendedToolRegistrySnapshot.missing());
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"bad\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"get_property_values\","
                + "\"args\":{\"thingName\":\"T\",\"propertyNames\":[\"Temperature\"]}},"
                + "{\"id\":\"n\",\"kind\":\"derive\",\"dependsOn\":[\"bad\"],\"op\":\"normalize_resolved_thing\","
                + "\"args\":{\"sourceNodeId\":\"bad\"}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"n\"],\"evidenceRefs\":[\"n\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw), tools);
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("resolve_thing"),
                String.join("; ", r.errors()));
    }

    @Test
    void conditionNode_unsupportedOp_rejected() throws Exception {
        String raw = readPackagingFixture("cross_region_health");
        JSONObject root = new JSONObject(raw);
        root.getJSONArray("nodes").put(new JSONObject()
                .put("id", "bad_cond")
                .put("kind", "condition")
                .put("dependsOn", new org.json.JSONArray().put("taxonomy_row"))
                .put("if", new JSONObject().put("op", "matches"))
                .put("then", "taxonomy_row")
                .put("else", "taxonomy_row"));
        PlaybookValidator.Result result =
                PlaybookValidator.validateDocument(PlaybookDocument.parse(root.toString()),
                        playbookToolDefsForHealthPlaybooks());
        assertFalse(result.valid());
    }

    @Test
    void conditionNode_missingThenElse_rejected() throws Exception {
        String raw = readPackagingFixture("cross_region_health");
        JSONObject root = new JSONObject(raw);
        root.getJSONArray("nodes").put(new JSONObject()
                .put("id", "branch_gate")
                .put("kind", "condition")
                .put("dependsOn", new org.json.JSONArray().put("taxonomy_row"))
                .put("if", new JSONObject().put("op", "is_empty").put("value", new JSONObject())));
        PlaybookValidator.Result result =
                PlaybookValidator.validateDocument(PlaybookDocument.parse(root.toString()),
                        playbookToolDefsForHealthPlaybooks());
        assertFalse(result.valid());
    }

    @Test
    void orphanNode_notReferenced_rejected() throws Exception {
        String raw = readPackagingFixture("cross_region_health");
        JSONObject root = new JSONObject(raw);
        root.getJSONArray("nodes").put(new JSONObject()
                .put("id", "orphan_probe")
                .put("kind", "derive")
                .put("dependsOn", new org.json.JSONArray().put("taxonomy_row"))
                .put("op", "extract_field")
                .put("args", new JSONObject()
                        .put("rows", new org.json.JSONArray().put("x"))
                        .put("fieldName", "name")));
        PlaybookValidator.Result result =
                PlaybookValidator.validateDocument(PlaybookDocument.parse(root.toString()),
                        playbookToolDefsForHealthPlaybooks());
        assertFalse(result.valid());
        assertTrue(String.join("; ", result.errors()).contains("orphan node"),
                String.join("; ", result.errors()));
    }

    @Test
    void matchIdentifierInRows_dollarTableInRows_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"prior\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"resolve_thing\",\"args\":{}},"
                + "{\"id\":\"m\",\"kind\":\"derive\",\"dependsOn\":[\"prior\"],\"op\":\"match_identifier_in_rows\","
                + "\"args\":{\"rows\":{\"$table\":\"prior.result\"},\"identifier\":{\"$input\":\"x\"}}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"m\"],\"evidenceRefs\":[\"m\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("$table"), String.join("; ", r.errors()));
    }

    @Test
    void deriveWithDollarTableInArgs_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"prior\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"d\",\"kind\":\"derive\",\"dependsOn\":[\"prior\"],\"op\":\"extract_field\","
                + "\"args\":{\"rows\":{\"$table\":\"prior.result\"},\"fieldName\":\"x\"}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"d\"],\"evidenceRefs\":[\"d\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("$table"), String.join("; ", r.errors()));
    }

    @Test
    void toolCall_dollarTableOutsideArgs_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"prior\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"bad\",\"kind\":\"tool_call\",\"dependsOn\":[\"prior\"],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{},\"evidence\":{\"x\":{\"$table\":\"prior.result\"}}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"bad\"],\"evidenceRefs\":[\"bad\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("$table"), String.join("; ", r.errors()));
    }

    @Test
    void toolCall_dollarTableOnlyInArgs_passes() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"first\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"second\",\"kind\":\"tool_call\",\"dependsOn\":[\"first\"],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{\"rows\":{\"$table\":\"first.result\"}}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"second\"],\"evidenceRefs\":[\"second\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertTrue(r.valid(), String.join("; ", r.errors()));
    }

    @Test
    void toolCall_dollarTableUnknownSourceNode_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"first\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"second\",\"kind\":\"tool_call\",\"dependsOn\":[\"first\"],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{\"rows\":{\"$table\":\"nonexistent.result\"}}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"second\"],\"evidenceRefs\":[\"second\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("unknown node"),
                String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_project_minimalPlaybook_passes() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"pr\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"project\","
                + "\"args\":{\"rows\":[{\"x\":1}],\"fields\":[{\"from\":\"x\",\"as\":\"y\"}]}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"pr\"],\"evidenceRefs\":[\"pr\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertTrue(r.valid(), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_flatten_fan_out_rows_duplicateInjectAs_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"fo\",\"kind\":\"fan_out\",\"dependsOn\":[],\"items\":[],\"maxItems\":1,\"maxConcurrency\":1,"
                + "\"node\":{\"kind\":\"tool_call\",\"tool\":\"query_entities_by_taxonomy\",\"args\":{}}},"
                + "{\"id\":\"fl\",\"kind\":\"derive\",\"dependsOn\":[\"fo\"],\"op\":\"flatten_fan_out_rows\","
                + "\"args\":{\"fanOutNodeId\":\"fo\",\"injectFromItem\":["
                + "{\"from\":\"name\",\"as\":\"x\"},{\"from\":\"slot\",\"as\":\"x\"}] }},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"fl\"],\"evidenceRefs\":[\"fl\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("duplicate as"), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_flatten_fan_out_rows_nonFanOutNode_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"fl\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"flatten_fan_out_rows\","
                + "\"args\":{\"fanOutNodeId\":\"src\"}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"fl\"],\"evidenceRefs\":[\"fl\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("fan_out"), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_project_duplicateAs_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"pr\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"project\","
                + "\"args\":{\"rows\":[],\"fields\":[{\"from\":\"a\",\"as\":\"y\"},{\"from\":\"b\",\"as\":\"y\"}]}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"pr\"],\"evidenceRefs\":[\"pr\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("duplicate as"), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_project_malformedFromPath_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"pr\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"project\","
                + "\"args\":{\"rows\":[],\"fields\":[{\"from\":\"a..b\",\"as\":\"y\"}]}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"pr\"],\"evidenceRefs\":[\"pr\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("invalid dotted path"), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_filter_minimalPlaybook_passes() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"fl\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"filter\","
                + "\"args\":{\"rows\":[{\"n\":1}],\"where\":{\"op\":\"is_present\",\"field\":\"n\"}}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"fl\"],\"evidenceRefs\":[\"fl\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertTrue(r.valid(), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_filter_missingWhere_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"fl\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"filter\","
                + "\"args\":{\"rows\":[]}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"fl\"],\"evidenceRefs\":[\"fl\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("where"), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_filter_emptyAndArray_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"fl\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"filter\","
                + "\"args\":{\"rows\":[],\"where\":{\"and\":[]}}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"fl\"],\"evidenceRefs\":[\"fl\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("non-empty"), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_filter_multipleStructuralBranches_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"fl\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"filter\","
                + "\"args\":{\"rows\":[],\"where\":{\"op\":\"is_present\",\"field\":\"n\",\"or\":[]}}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"fl\"],\"evidenceRefs\":[\"fl\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("exactly one structural"), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_filter_notNonObject_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"fl\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"filter\","
                + "\"args\":{\"rows\":[],\"where\":{\"not\":\"bad\"}}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"fl\"],\"evidenceRefs\":[\"fl\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("JSONObject"), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_sort_minimalPlaybook_passes() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"so\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"sort\","
                + "\"args\":{\"rows\":[],\"orderBy\":[{\"field\":\"k\",\"direction\":\"asc\"}]}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"so\"],\"evidenceRefs\":[\"so\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertTrue(r.valid(), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_top_n_nAboveCap_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"tn\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"top_n\","
                + "\"args\":{\"rows\":[],\"n\":999999}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"tn\"],\"evidenceRefs\":[\"tn\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("exceeds cap"), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_top_n_nAboveIntRange_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"tn\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"top_n\","
                + "\"args\":{\"rows\":[],\"n\":2147483648}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"tn\"],\"evidenceRefs\":[\"tn\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("exceeds cap"), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_group_by_minimalPlaybook_passes() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"gb\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"group_by\","
                + "\"args\":{\"rows\":[],\"keys\":[\"k\"],\"maxGroups\":10,"
                + "\"measures\":[{\"name\":\"n\",\"op\":\"count\"}]}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"gb\"],\"evidenceRefs\":[\"gb\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertTrue(r.valid(), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_group_by_foldOverflow_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"gb\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"group_by\","
                + "\"args\":{\"rows\":[],\"keys\":[\"k\"],\"maxGroups\":10,\"foldOverflowToOther\":true}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"gb\"],\"evidenceRefs\":[\"gb\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("foldOverflow"), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_group_by_measuresNotArray_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"gb\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"group_by\","
                + "\"args\":{\"rows\":[],\"keys\":[\"k\"],\"maxGroups\":10,\"measures\":\"bad\"}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"gb\"],\"evidenceRefs\":[\"gb\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("measures must be a JSON array"),
                String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_group_by_dottedKey_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"gb\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"group_by\","
                + "\"args\":{\"rows\":[],\"keys\":[\"sensor.id\"],\"maxGroups\":10}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"gb\"],\"evidenceRefs\":[\"gb\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("single identifier"), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_group_by_keyNonJsonString_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"gb\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"group_by\","
                + "\"args\":{\"rows\":[],\"keys\":[true],\"maxGroups\":10}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"gb\"],\"evidenceRefs\":[\"gb\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("JSON string"), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_group_by_measureNameNonJsonString_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"gb\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"group_by\","
                + "\"args\":{\"rows\":[],\"keys\":[\"k\"],\"maxGroups\":10,"
                + "\"measures\":[{\"name\":true,\"op\":\"count\"}]}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"gb\"],\"evidenceRefs\":[\"gb\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("name must be a JSON string"),
                String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_aggregate_minimalPlaybook_passes() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"ag\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"aggregate\","
                + "\"args\":{\"rows\":[],\"measures\":[{\"name\":\"n\",\"op\":\"count\"}]}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"ag\"],\"evidenceRefs\":[\"ag\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertTrue(r.valid(), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_aggregate_emptyMeasures_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"ag\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"aggregate\","
                + "\"args\":{\"rows\":[],\"measures\":[]}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"ag\"],\"evidenceRefs\":[\"ag\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("non-empty"), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_join_by_key_minimalPlaybook_passes() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"jk\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"join_by_key\","
                + "\"args\":{\"left\":[],\"right\":[],\"leftKey\":\"k\",\"rightKey\":\"k\",\"joinType\":\"inner\","
                + "\"rightPrefix\":\"r_\",\"maxRows\":50}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"jk\"],\"evidenceRefs\":[\"jk\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertTrue(r.valid(), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_build_targets_minimalPlaybook_passes() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"bt\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"build_targets\","
                + "\"args\":{\"sources\":{\"r\":[]},\"template\":{\"k\":{\"$path\":\"r.x\"}},\"maxTargets\":10}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"bt\"],\"evidenceRefs\":[\"bt\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertTrue(r.valid(), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_dollarPathOutsideBuildTargetsTemplate_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"tc\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{\"payload\":{\"$path\":\"a.b\"}}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"tc\"],\"evidenceRefs\":[\"tc\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("$path"), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_build_targets_pathUnderSources_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"bt\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"build_targets\","
                + "\"args\":{\"sources\":{\"r\":{\"$path\":\"a.b\"}},\"template\":{\"k\":1},\"maxTargets\":1}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"bt\"],\"evidenceRefs\":[\"bt\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("$path"), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_collect_gaps_minimalPlaybook_passes() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"n1\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"filter\","
                + "\"args\":{\"rows\":[],\"where\":{\"op\":\"is_present\",\"field\":\"x\"}}},"
                + "{\"id\":\"cg\",\"kind\":\"derive\",\"dependsOn\":[\"n1\"],\"op\":\"collect_gaps\","
                + "\"args\":{\"refs\":[\"n1.output.gaps\"],\"maxItems\":8}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"cg\"],\"evidenceRefs\":[\"cg\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertTrue(r.valid(), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_collect_gaps_unknownRefNode_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"cg\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"collect_gaps\","
                + "\"args\":{\"refs\":[\"missing.output.gaps\"],\"maxItems\":4}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"cg\"],\"evidenceRefs\":[\"cg\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("unknown node"), String.join("; ", r.errors()));
    }

    @Test
    void genericDerive_pick_one_invalidOnZero_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"po\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"pick_one\","
                + "\"args\":{\"rows\":[],\"where\":{\"op\":\"is_present\",\"field\":\"x\"},\"onZero\":\"bogus\"}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"po\"],\"evidenceRefs\":[\"po\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("onZero"), String.join("; ", r.errors()));
    }

    @Test
    void toolCall_nestedDollarTableInArgs_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"first\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"second\",\"kind\":\"tool_call\",\"dependsOn\":[\"first\"],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{\"payload\":{\"rows\":{\"$table\":\"first.result\"}}}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"second\"],\"evidenceRefs\":[\"second\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("$table"), String.join("; ", r.errors()));
    }

    @Test
    void toolCall_includeToolOutputRootFieldsWithoutTable_passes() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"n1\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{},\"evidence\":{\"includeToolOutputRootFields\":[\"rowCount\"]}}"
                + ",{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"n1\"],\"evidenceRefs\":[\"n1\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertTrue(r.valid(), String.join("; ", r.errors()));
    }

    @Test
    void toolCall_includeToolOutputRootFields_emptyArray_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"n1\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{},\"evidence\":{\"includeToolOutputRootFields\":[]}}"
                + ",{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"n1\"],\"evidenceRefs\":[\"n1\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("includeToolOutputRootFields must be non-empty"),
                String.join("; ", r.errors()));
    }

    @Test
    void toolCall_includeToolOutputRootFieldsWithTable_passes() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"n1\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{},\"evidence\":{\"includeToolOutputRootFields\":[\"rowCount\"],"
                + "\"table\":{\"maxRows\":3,\"columns\":[\"sourceProperty\"]}}}"
                + ",{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"n1\"],\"evidenceRefs\":[\"n1\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertTrue(r.valid(), String.join("; ", r.errors()));
    }

    @Test
    void toolCall_includeToolOutputPaths_nestedPath_passes() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"n1\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{},\"evidence\":{\"includeToolOutputPaths\":[\"result.stats.utilizationPercent\"],"
                + "\"table\":{\"path\":\"result.rows\",\"maxRows\":3,\"columns\":[\"utilizationState\"]}}}"
                + ",{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"n1\"],\"evidenceRefs\":[\"n1\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertTrue(r.valid(), String.join("; ", r.errors()));
    }

    @Test
    void toolCall_includeToolOutputPaths_invalidDotPath_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"n1\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{},\"evidence\":{\"includeToolOutputPaths\":[\"result.rows[0].x\"]}}"
                + ",{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"n1\"],\"evidenceRefs\":[\"n1\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("includeToolOutputPaths[0] invalid dot path"),
                String.join("; ", r.errors()));
    }

    @Test
    void orchestrationDerive_extract_sourceRefObject_rejected() throws Exception {
        String raw = minimalOrchestrationPlaybook(
                "{\"id\":\"ex\",\"kind\":\"derive\",\"dependsOn\":[\"fo\"],\"op\":\"extract_from_tool_output\","
                        + "\"args\":{\"source\":{\"$ref\":\"fo\"},\"mode\":\"fan_out_children\",\"arrayPath\":\"properties\","
                        + "\"maxRows\":10,\"fields\":[{\"from\":\"value\",\"as\":\"UID\"}]}}");
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw),
                playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("sourceNodeId"), String.join("; ", r.errors()));
    }

    @Test
    void orchestrationDerive_extract_parentFromInSingleMode_rejected() throws Exception {
        String raw = minimalOrchestrationPlaybook(
                "{\"id\":\"ex\",\"kind\":\"derive\",\"dependsOn\":[\"tc\"],\"op\":\"extract_from_tool_output\","
                        + "\"args\":{\"sourceNodeId\":\"tc\",\"mode\":\"single\",\"arrayPath\":\"properties\","
                        + "\"maxRows\":10,\"fields\":[{\"from\":\"$parent.item.name\",\"as\":\"n\"}]}}");
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw),
                playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("from invalid"), String.join("; ", r.errors()));
    }

    @Test
    void orchestrationDerive_normalize_maxRowsLessThanMin_rejected() throws Exception {
        String raw = minimalOrchestrationPlaybook(
                "{\"id\":\"norm\",\"kind\":\"derive\",\"dependsOn\":[\"fo\"],\"op\":\"normalize_resolved_things\","
                        + "\"args\":{\"fanOutNodeId\":\"fo\",\"maxRows\":1,\"minResolvedRows\":2}}");
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw),
                playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("maxRows must be >= minResolvedRows"),
                String.join("; ", r.errors()));
    }

    @Test
    void orchestrationDerive_extract_duplicateAs_rejected() throws Exception {
        String raw = minimalOrchestrationPlaybook(
                "{\"id\":\"ex\",\"kind\":\"derive\",\"dependsOn\":[\"fo\"],\"op\":\"extract_from_tool_output\","
                        + "\"args\":{\"sourceNodeId\":\"fo\",\"mode\":\"fan_out_children\",\"arrayPath\":\"properties\","
                        + "\"maxRows\":10,\"fields\":[{\"from\":\"value\",\"as\":\"UID\"},{\"from\":\"x\",\"as\":\"UID\"}]}}");
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw),
                playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("duplicate as"), String.join("; ", r.errors()));
    }

    @Test
    void orchestrationDerive_minimalPlaybook_passes() throws Exception {
        String raw = minimalOrchestrationPlaybook(
                "{\"id\":\"norm\",\"kind\":\"derive\",\"dependsOn\":[\"fo\"],\"op\":\"normalize_resolved_things\","
                        + "\"args\":{\"fanOutNodeId\":\"fo\",\"maxRows\":25}},"
                        + "{\"id\":\"ex\",\"kind\":\"derive\",\"dependsOn\":[\"uidfo\"],\"op\":\"extract_from_tool_output\","
                        + "\"args\":{\"sourceNodeId\":\"uidfo\",\"mode\":\"fan_out_children\",\"arrayPath\":\"properties\","
                        + "\"maxRows\":25,\"where\":{\"field\":\"name\",\"op\":\"eq\",\"right\":\"UID\"},"
                        + "\"fields\":[{\"from\":\"value\",\"as\":\"UID\"},{\"from\":\"$parent.item.name\",\"as\":\"thingName\"}]}}");
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw),
                playbookToolDefsForHealthPlaybooks());
        assertTrue(r.valid(), String.join("; ", r.errors()));
    }

    @Test
    void orchestrationDerive_buildNestedObject_minimalPlaybook_passes() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"time_window\",\"kind\":\"derive\",\"dependsOn\":[],\"op\":\"project\","
                + "\"args\":{\"rows\":[{\"QuickTimeIntervalUID\":3}],"
                + "\"fields\":[{\"from\":\"QuickTimeIntervalUID\",\"as\":\"QuickTimeIntervalUID\"}]}},"
                + "{\"id\":\"ex\",\"kind\":\"derive\",\"dependsOn\":[],\"op\":\"project\","
                + "\"args\":{\"rows\":[{\"UID\":4},{\"UID\":7}],"
                + "\"fields\":[{\"from\":\"UID\",\"as\":\"UID\"}]}},"
                + "{\"id\":\"filters\",\"kind\":\"derive\",\"dependsOn\":[\"ex\",\"time_window\"],\"op\":\"build_nested_object\","
                + "\"args\":{\"sources\":{\"equipment\":{\"$ref\":\"ex.output.rows\"},"
                + "\"criteria\":[{\"FilterCriteria\":\"PRODUCT\",\"UIDValue\":8}]},"
                + "\"template\":{\"Filters\":{\"$map\":{\"over\":\"equipment\",\"each\":{"
                + "\"EquipmentUID\":{\"$path\":\"UID\"},\"FilterCriterias\":{\"$src\":\"criteria\"}}}},"
                + "\"QuickTimeIntervalUID\":{\"$nodeRef\":\"time_window.output.QuickTimeIntervalUID\"}},"
                + "\"maxParents\":25,\"maxChildren\":100,\"minParents\":1,\"omitNull\":true}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"filters\"],\"evidenceRefs\":[\"filters\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw),
                playbookToolDefsForHealthPlaybooks());
        assertTrue(r.valid(), String.join("; ", r.errors()));
    }

    @Test
    void orchestrationDerive_buildNestedObject_unknownSource_rejected() throws Exception {
        String raw = minimalOrchestrationPlaybook(
                "{\"id\":\"norm\",\"kind\":\"derive\",\"dependsOn\":[\"fo\"],\"op\":\"normalize_resolved_things\","
                        + "\"args\":{\"fanOutNodeId\":\"fo\",\"maxRows\":25}},"
                        + "{\"id\":\"ex\",\"kind\":\"derive\",\"dependsOn\":[\"uidfo\"],\"op\":\"extract_from_tool_output\","
                        + "\"args\":{\"sourceNodeId\":\"uidfo\",\"mode\":\"fan_out_children\",\"arrayPath\":\"properties\","
                        + "\"maxRows\":25,\"fields\":[{\"from\":\"value\",\"as\":\"UID\"}]}},"
                        + "{\"id\":\"filters\",\"kind\":\"derive\",\"dependsOn\":[\"ex\"],\"op\":\"build_nested_object\","
                        + "\"args\":{\"sources\":{\"equipment\":{\"$ref\":\"ex.output.rows\"}},"
                        + "\"template\":{\"Filters\":{\"$map\":{\"over\":\"missing\",\"each\":{"
                        + "\"EquipmentUID\":{\"$path\":\"UID\"}}}}},"
                        + "\"maxParents\":25,\"maxChildren\":100}}");
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw),
                playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("$map.over must name a declared source"),
                String.join("; ", r.errors()));
    }

    @Test
    void orchestrationDerive_jsonStringify_missingMaxBytes_rejected() throws Exception {
        String raw = minimalOrchestrationPlaybook(
                "{\"id\":\"norm\",\"kind\":\"derive\",\"dependsOn\":[\"fo\"],\"op\":\"normalize_resolved_things\","
                        + "\"args\":{\"fanOutNodeId\":\"fo\",\"maxRows\":25}},"
                        + "{\"id\":\"ex\",\"kind\":\"derive\",\"dependsOn\":[\"uidfo\"],\"op\":\"extract_from_tool_output\","
                        + "\"args\":{\"sourceNodeId\":\"uidfo\",\"mode\":\"fan_out_children\",\"arrayPath\":\"properties\","
                        + "\"maxRows\":25,\"fields\":[{\"from\":\"value\",\"as\":\"UID\"}]}},"
                        + "{\"id\":\"js\",\"kind\":\"derive\",\"dependsOn\":[\"ex\"],\"op\":\"json_stringify\","
                        + "\"args\":{\"value\":{\"$ref\":\"ex.output.rows\"}}}");
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw),
                playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("json_stringify derive missing maxBytes"),
                String.join("; ", r.errors()));
    }

    @Test
    void orchestrationDerive_buildNestedObject_unknownNodeRef_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"time_window\",\"kind\":\"derive\",\"dependsOn\":[],\"op\":\"project\","
                + "\"args\":{\"rows\":[{\"QuickTimeIntervalUID\":3}],"
                + "\"fields\":[{\"from\":\"QuickTimeIntervalUID\",\"as\":\"QuickTimeIntervalUID\"}]}},"
                + "{\"id\":\"ex\",\"kind\":\"derive\",\"dependsOn\":[],\"op\":\"project\","
                + "\"args\":{\"rows\":[{\"UID\":4}],\"fields\":[{\"from\":\"UID\",\"as\":\"UID\"}]}},"
                + "{\"id\":\"filters\",\"kind\":\"derive\",\"dependsOn\":[\"ex\",\"time_window\"],\"op\":\"build_nested_object\","
                + "\"args\":{\"sources\":{\"equipment\":{\"$ref\":\"ex.output.rows\"}},"
                + "\"template\":{\"QuickTimeIntervalUID\":{\"$nodeRef\":\"missing.output.QuickTimeIntervalUID\"}},"
                + "\"maxParents\":25,\"maxChildren\":100}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"filters\"],\"evidenceRefs\":[\"filters\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw),
                playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("unknown node \"missing\""),
                String.join("; ", r.errors()));
    }

    @Test
    void orchestrationDerive_buildNestedObject_topLevelLiteral_passes() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"ex\",\"kind\":\"derive\",\"dependsOn\":[],\"op\":\"project\","
                + "\"args\":{\"rows\":[{\"UID\":4}],\"fields\":[{\"from\":\"UID\",\"as\":\"UID\"}]}},"
                + "{\"id\":\"filters\",\"kind\":\"derive\",\"dependsOn\":[\"ex\"],\"op\":\"build_nested_object\","
                + "\"args\":{\"sources\":{\"equipment\":{\"$ref\":\"ex.output.rows\"}},"
                + "\"template\":{\"QuickTimeIntervalUID\":{\"$literal\":3}},"
                + "\"maxParents\":25,\"maxChildren\":100}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"filters\"],\"evidenceRefs\":[\"filters\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw),
                playbookToolDefsForHealthPlaybooks());
        assertTrue(r.valid(), String.join("; ", r.errors()));
    }

    @Test
    void orchestrationDerive_buildNestedObject_minParentsExceedsMaxParents_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"ex\",\"kind\":\"derive\",\"dependsOn\":[],\"op\":\"project\","
                + "\"args\":{\"rows\":[{\"UID\":4}],\"fields\":[{\"from\":\"UID\",\"as\":\"UID\"}]}},"
                + "{\"id\":\"filters\",\"kind\":\"derive\",\"dependsOn\":[\"ex\"],\"op\":\"build_nested_object\","
                + "\"args\":{\"sources\":{\"equipment\":{\"$ref\":\"ex.output.rows\"}},"
                + "\"template\":{\"Filters\":{\"$map\":{\"over\":\"equipment\",\"each\":{"
                + "\"EquipmentUID\":{\"$path\":\"UID\"}}}}},"
                + "\"maxParents\":2,\"maxChildren\":100,\"minParents\":5}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"filters\"],\"evidenceRefs\":[\"filters\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw),
                playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("minParents must be <= maxParents"),
                String.join("; ", r.errors()));
    }

    @Test
    void orchestrationDerive_buildNestedObject_topLevelSrc_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"ex\",\"kind\":\"derive\",\"dependsOn\":[],\"op\":\"project\","
                + "\"args\":{\"rows\":[{\"UID\":4}],\"fields\":[{\"from\":\"UID\",\"as\":\"UID\"}]}},"
                + "{\"id\":\"filters\",\"kind\":\"derive\",\"dependsOn\":[\"ex\"],\"op\":\"build_nested_object\","
                + "\"args\":{\"sources\":{\"equipment\":{\"$ref\":\"ex.output.rows\"},"
                + "\"criteria\":[{\"FilterCriteria\":\"PRODUCT\"}]},"
                + "\"template\":{\"FilterCriterias\":{\"$src\":\"criteria\"}},"
                + "\"maxParents\":25,\"maxChildren\":100}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"filters\"],\"evidenceRefs\":[\"filters\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw),
                playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("$src only allowed inside $map.each"),
                String.join("; ", r.errors()));
    }

    @Test
    void orchestrationDerive_resolveTimeWindow_minimal_passes() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"qi\",\"kind\":\"derive\",\"dependsOn\":[],\"op\":\"project\","
                + "\"args\":{\"rows\":[{\"name\":\"Today\",\"QuickTimeIntervalUID\":3}],"
                + "\"fields\":[{\"from\":\"name\",\"as\":\"name\"},{\"from\":\"QuickTimeIntervalUID\",\"as\":\"QuickTimeIntervalUID\"}]}},"
                + "{\"id\":\"tw\",\"kind\":\"derive\",\"dependsOn\":[\"qi\"],\"op\":\"resolve_time_window_for_playbook\","
                + "\"args\":{\"phrase\":{\"$input\":\"timeWindow\"},"
                + "\"quickIntervalRows\":{\"$ref\":\"qi.output.rows\"},"
                + "\"defaultQuickIntervalName\":\"Today\","
                + "\"timezone\":{\"$var\":\"user_timezone\"}}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"tw\"],\"evidenceRefs\":[\"tw\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw),
                playbookToolDefsForHealthPlaybooks());
        assertTrue(r.valid(), String.join("; ", r.errors()));
    }

    @Test
    void orchestrationDerive_mergeRowSets_passes() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"a\",\"kind\":\"derive\",\"dependsOn\":[],\"op\":\"project\","
                + "\"args\":{\"rows\":[{\"x\":1}],\"fields\":[{\"from\":\"x\",\"as\":\"x\"}]}},"
                + "{\"id\":\"b\",\"kind\":\"derive\",\"dependsOn\":[],\"op\":\"project\","
                + "\"args\":{\"rows\":[{\"y\":2}],\"fields\":[{\"from\":\"y\",\"as\":\"y\"}]}},"
                + "{\"id\":\"merged\",\"kind\":\"derive\",\"dependsOn\":[\"a\",\"b\"],\"op\":\"merge_row_sets\","
                + "\"args\":{\"sources\":[{\"$ref\":\"a.output.rows\"},{\"$ref\":\"b.output.rows\"}],"
                + "\"maxSources\":8,\"maxRows\":100}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"merged\"],\"evidenceRefs\":[\"merged\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw),
                playbookToolDefsForHealthPlaybooks());
        assertTrue(r.valid(), String.join("; ", r.errors()));
    }

    @Test
    void orchestrationDerive_mergeRowSets_sourcesExceedMaxSources_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"a\",\"kind\":\"derive\",\"dependsOn\":[],\"op\":\"project\","
                + "\"args\":{\"rows\":[{\"x\":1}],\"fields\":[{\"from\":\"x\",\"as\":\"x\"}]}},"
                + "{\"id\":\"b\",\"kind\":\"derive\",\"dependsOn\":[],\"op\":\"project\","
                + "\"args\":{\"rows\":[{\"y\":2}],\"fields\":[{\"from\":\"y\",\"as\":\"y\"}]}},"
                + "{\"id\":\"merged\",\"kind\":\"derive\",\"dependsOn\":[\"a\",\"b\"],\"op\":\"merge_row_sets\","
                + "\"args\":{\"sources\":[{\"$ref\":\"a.output.rows\"},{\"$ref\":\"b.output.rows\"}],"
                + "\"maxSources\":1,\"maxRows\":100}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"merged\"],\"evidenceRefs\":[\"merged\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw),
                playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("sources length exceeds maxSources"));
    }

    @Test
    void orchestrationDerive_mergeRowSets_trailingDotRef_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"a\",\"kind\":\"derive\",\"dependsOn\":[],\"op\":\"project\","
                + "\"args\":{\"rows\":[{\"x\":1}],\"fields\":[{\"from\":\"x\",\"as\":\"x\"}]}},"
                + "{\"id\":\"merged\",\"kind\":\"derive\",\"dependsOn\":[\"a\"],\"op\":\"merge_row_sets\","
                + "\"args\":{\"sources\":[{\"$ref\":\"a.\"}],\"maxSources\":8,\"maxRows\":100}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"merged\"],\"evidenceRefs\":[\"merged\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw),
                playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("valid dotted path"));
    }

    @Test
    void orchestrationDerive_mergeRowSets_doubleDotRef_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"a\",\"kind\":\"derive\",\"dependsOn\":[],\"op\":\"project\","
                + "\"args\":{\"rows\":[{\"x\":1}],\"fields\":[{\"from\":\"x\",\"as\":\"x\"}]}},"
                + "{\"id\":\"merged\",\"kind\":\"derive\",\"dependsOn\":[\"a\"],\"op\":\"merge_row_sets\","
                + "\"args\":{\"sources\":[{\"$ref\":\"a..b\"}],\"maxSources\":8,\"maxRows\":100}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"merged\"],\"evidenceRefs\":[\"merged\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw),
                playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("valid dotted path"));
    }

    @Test
    void orchestrationDerive_emptyRowsIfSkipped_passes() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"gate\",\"kind\":\"condition\",\"dependsOn\":[],\"if\":{\"op\":\"is_empty\",\"left\":{"
                + "\"$input\":\"productNames\"}},\"then\":\"lookup\",\"else\":\"empty\"},"
                + "{\"id\":\"lookup\",\"kind\":\"derive\",\"dependsOn\":[\"gate\"],\"op\":\"project\","
                + "\"args\":{\"rows\":[{\"FilterCriteria\":\"PRODUCT\"}],"
                + "\"fields\":[{\"from\":\"FilterCriteria\",\"as\":\"FilterCriteria\"}]}},"
                + "{\"id\":\"empty\",\"kind\":\"derive\",\"dependsOn\":[\"gate\"],\"op\":\"empty_rows_if_skipped\","
                + "\"args\":{\"sourceNodeId\":\"lookup\",\"label\":\"product\"}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"empty\"],\"evidenceRefs\":[\"empty\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw),
                playbookToolDefsForHealthPlaybooks());
        assertTrue(r.valid(), String.join("; ", r.errors()));
    }

    @Test
    void orchestrationDerive_sliceEOps_passes() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"derive\",\"dependsOn\":[],\"op\":\"project\","
                + "\"args\":{\"rows\":[{\"a\":1}],\"fields\":[{\"from\":\"a\",\"as\":\"a\"}]}},"
                + "{\"id\":\"calc\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"add_computed_fields\","
                + "\"args\":{\"rows\":{\"$ref\":\"src.output.rows\"},\"fields\":[{\"as\":\"b\","
                + "\"expr\":{\"op\":\"add\",\"left\":\"a\",\"right\":\"a\"}}]}},"
                + "{\"id\":\"vals\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"collect_values\","
                + "\"args\":{\"rows\":{\"$ref\":\"src.output.rows\"},\"field\":\"a\"}},"
                + "{\"id\":\"join\",\"kind\":\"derive\",\"dependsOn\":[\"vals\"],\"op\":\"join_values\","
                + "\"args\":{\"values\":{\"$ref\":\"vals.output.values\"}}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"calc\",\"join\"],"
                + "\"evidenceRefs\":[\"calc\",\"join\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw),
                playbookToolDefsForHealthPlaybooks());
        assertTrue(r.valid(), String.join("; ", r.errors()));
    }

    @Test
    void orchestrationDerive_addComputedFields_invalidExprPath_rejected() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"src\",\"kind\":\"derive\",\"dependsOn\":[],\"op\":\"project\","
                + "\"args\":{\"rows\":[{\"a\":1}],\"fields\":[{\"from\":\"a\",\"as\":\"a\"}]}},"
                + "{\"id\":\"calc\",\"kind\":\"derive\",\"dependsOn\":[\"src\"],\"op\":\"add_computed_fields\","
                + "\"args\":{\"rows\":{\"$ref\":\"src.output.rows\"},\"fields\":[{\"as\":\"b\","
                + "\"expr\":{\"op\":\"add\",\"left\":\"a..b\",\"right\":\"a\"}}]}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"calc\"],\"evidenceRefs\":[\"calc\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw),
                playbookToolDefsForHealthPlaybooks());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("invalid dotted path"),
                String.join("; ", r.errors()));
    }

    @Test
    void infotableBinding_extendedToolTopLevel_passes() throws Exception {
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"ex\",\"kind\":\"derive\",\"dependsOn\":[],\"op\":\"project\","
                + "\"args\":{\"rows\":[{\"x\":1}],\"fields\":[{\"from\":\"x\",\"as\":\"UID\"}]}},"
                + "{\"id\":\"call\",\"kind\":\"tool_call\",\"dependsOn\":[\"ex\"],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{\"EquipmentUIDs\":{\"$infotable\":{\"rows\":{\"$ref\":\"ex.output.rows\"},"
                + "\"dataShapeName\":\"PTC.SCA.SCO.Utilities.UID\"}}}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"call\"],\"evidenceRefs\":[\"call\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw),
                playbookToolDefsForHealthPlaybooks(), ExtendedToolRegistrySnapshot.missing());
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("repository extended tools"),
                String.join("; ", r.errors()));
    }

    @Test
    void infotableBinding_inlineRowsArray_rejected() throws Exception {
        ExtendedToolRegistrySnapshot ext = alarmEventsExtendedRegistry();
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        List<ToolDefinition> tools = PlaybookToolDefinitionsMerge.merge(reg, ext);
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"call\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"GetAlarmEvents_AI\","
                + "\"args\":{\"EquipmentUIDs\":{\"$infotable\":{\"rows\":[{\"UID\":1}],"
                + "\"dataShapeName\":\"PTC.SCA.SCO.Utilities.UID\"}}}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"call\"],\"evidenceRefs\":[\"call\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw), tools, ext);
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("inline array"), String.join("; ", r.errors()));
    }

    @Test
    void infotableBinding_invokeServiceParameters_deferred() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        List<ToolDefinition> tools = PlaybookToolDefinitionsMerge.merge(reg, ExtendedToolRegistrySnapshot.missing());
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"ex\",\"kind\":\"derive\",\"dependsOn\":[],\"op\":\"project\","
                + "\"args\":{\"rows\":[{\"x\":1}],\"fields\":[{\"from\":\"x\",\"as\":\"UID\"}]}},"
                + "{\"id\":\"call\",\"kind\":\"tool_call\",\"dependsOn\":[\"ex\"],\"tool\":\"invoke_service\","
                + "\"args\":{\"entityName\":\"E\",\"serviceName\":\"GetAlarmEvents_AI\",\"parameters\":{"
                + "\"EquipmentUIDs\":{\"$infotable\":{\"rows\":{\"$ref\":\"ex.output.rows\"},"
                + "\"dataShapeName\":\"PTC.SCA.SCO.Utilities.UID\"}}}}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"call\"],\"evidenceRefs\":[\"call\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw), tools);
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("deferred in v1"), String.join("; ", r.errors()));
    }

    @Test
    void infotableBinding_unknownRowsRef_rejected() throws Exception {
        ExtendedToolRegistrySnapshot ext = alarmEventsExtendedRegistry();
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        List<ToolDefinition> tools = PlaybookToolDefinitionsMerge.merge(reg, ext);
        String raw = "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"call\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"GetAlarmEvents_AI\","
                + "\"args\":{\"EquipmentUIDs\":{\"$infotable\":{\"rows\":{\"$ref\":\"missing.output.rows\"},"
                + "\"dataShapeName\":\"PTC.SCA.SCO.Utilities.UID\"}}}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"call\"],\"evidenceRefs\":[\"call\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(PlaybookDocument.parse(raw), tools, ext);
        assertFalse(r.valid());
        assertTrue(String.join("; ", r.errors()).contains("unknown node"), String.join("; ", r.errors()));
    }

    private static ExtendedToolRegistrySnapshot alarmEventsExtendedRegistry() {
        ToolDefinition td = new ToolDefinition("GetAlarmEvents_AI", "alarm events", Map.of("type", "object"), true);
        return ExtendedToolRegistrySnapshot.ok(List.of(new ExtendedToolDefinition("GetAlarmEvents_AI", "t", "w",
                "AlarmThing", "GetAlarmEvents_AI", false, false, td)));
    }

    private static String minimalOrchestrationPlaybook(String deriveNodesCsv) {
        return "{\"schema\":\"parler-playbook-v1\",\"title\":\"t\",\"budgets\":{},\"nodes\":["
                + "{\"id\":\"fo\",\"kind\":\"fan_out\",\"dependsOn\":[],\"items\":[],\"maxItems\":2,\"maxConcurrency\":1,"
                + "\"node\":{\"kind\":\"tool_call\",\"tool\":\"resolve_thing\",\"args\":{}}},"
                + "{\"id\":\"uidfo\",\"kind\":\"fan_out\",\"dependsOn\":[\"norm\"],\"items\":{\"$ref\":\"norm.output.rows\"},"
                + "\"maxItems\":2,\"maxConcurrency\":1,"
                + "\"node\":{\"kind\":\"tool_call\",\"tool\":\"get_property_values\",\"args\":{}}},"
                + deriveNodesCsv
                + ",{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"ex\"],\"evidenceRefs\":[\"ex\"],\"prompt\":\"p\"}"
                + "],\"finalNode\":\"end\"}";
    }

    private static String readPackagingFixture(String playbookId) throws Exception {
        String resource = "/playbook-packaging-fixture/" + playbookId + "/playbook.json";
        try (InputStream in = PlaybookValidatorTest.class.getResourceAsStream(resource)) {
            assertTrue(in != null, "missing " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path repoRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (cwd.getFileName().toString().equals("parler-agent")) {
            return cwd.getParent();
        }
        return cwd;
    }
}
