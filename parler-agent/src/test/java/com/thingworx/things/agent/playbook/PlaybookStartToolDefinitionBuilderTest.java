package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.LlmJsonSchemaCompat;
import com.thingworx.things.agent.llm.ToolDefinition;

class PlaybookStartToolDefinitionBuilderTest {

    private static PlaybookCatalogEntry entry(String id, String whenToUse) {
        return new PlaybookCatalogEntry(id, "Title " + id, "", whenToUse, "/playbooks/" + id + "/playbook.json",
                new JSONObject(), new JSONObject());
    }

    private static PlaybookRegistrySnapshot registry(PlaybookCatalogEntry... entries) {
        Map<String, PlaybookCatalogEntry> catalog = new LinkedHashMap<>();
        for (PlaybookCatalogEntry e : entries) {
            catalog.put(e.id(), e);
        }
        return PlaybookRegistrySnapshot.loaded(Instant.now(), catalog, Map.of(), List.of());
    }

    @Test
    void unloaded_returnsNull() {
        assertNull(PlaybookStartToolDefinitionBuilder.build(null));
        assertNull(PlaybookStartToolDefinitionBuilder.build(PlaybookRegistrySnapshot.empty(Instant.now())));
    }

    @Test
    void listsAllIds_inPlaybookIdDescription_andOmitsDemoParamKeys() {
        PlaybookRegistrySnapshot reg = registry(
                entry("alpha", "When alpha"),
                entry("beta", "When beta"));
        ToolDefinition td = PlaybookStartToolDefinitionBuilder.build(reg);
        assertNotNull(td);
        assertEquals("start_playbook", td.getName());
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) td.getParametersSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> playbookId = (Map<String, Object>) props.get("playbook_id");
        String idDesc = (String) playbookId.get("description");
        assertTrue(idDesc.contains("alpha"));
        assertTrue(idDesc.contains("beta"));
        assertFalse(props.containsKey("assetType"));
        assertFalse(props.containsKey("regions"));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) props.get("params");
        assertEquals("object", params.get("type"));
        assertEquals(Boolean.TRUE, params.get("additionalProperties"));
        assertFalse(params.containsKey("properties"));
    }

    @Test
    void truncationNeverDropsIds_fromPlaybookIdEnumeration() {
        String longWhen = "w".repeat(500);
        Map<String, PlaybookCatalogEntry> catalog = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            String id = String.format("pb_%02d", i);
            catalog.put(id, entry(id, longWhen));
        }
        PlaybookRegistrySnapshot reg = PlaybookRegistrySnapshot.loaded(Instant.now(), catalog, Map.of(), List.of());
        ToolDefinition td = PlaybookStartToolDefinitionBuilder.build(reg);
        assertNotNull(td);
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) td.getParametersSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> playbookId = (Map<String, Object>) props.get("playbook_id");
        String idDesc = (String) playbookId.get("description");
        for (int i = 0; i < 20; i++) {
            assertTrue(idDesc.contains(String.format("pb_%02d", i)), idDesc);
        }
        assertTrue(td.getDescription().contains("see per-turn Agent playbooks catalog")
                || td.getDescription().length() <= PlaybookStartToolDefinitionBuilder.MAX_TOP_LEVEL_DESCRIPTION_CHARS);
    }

    @Test
    void parametersSchema_passesLlmJsonSchemaCompat() {
        ToolDefinition td = PlaybookStartToolDefinitionBuilder.build(registry(entry("only", "When")));
        assertNotNull(td);
        LlmJsonSchemaCompat.assertArraysDeclareItems("start_playbook", td.getParametersSchema());
    }
}
