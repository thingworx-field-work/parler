package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.AnthropicMessagesApi;
import com.thingworx.things.agent.llm.ChatCompletionsApi;
import com.thingworx.things.agent.llm.LlmJsonSchemaCompat;
import com.thingworx.things.agent.llm.ToolDefinition;

/**
 * Provider wire-shape acceptance for registry-driven {@code start_playbook}.
 */
class PlaybookStartToolDefinitionProviderWireTest {

    private static PlaybookRegistrySnapshot sampleRegistry() {
        PlaybookCatalogEntry alpha = new PlaybookCatalogEntry(
                "alpha", "Alpha", "", "When alpha", "/playbooks/alpha/playbook.json",
                new JSONObject(), new JSONObject());
        PlaybookCatalogEntry beta = new PlaybookCatalogEntry(
                "beta", "Beta", "", "When beta", "/playbooks/beta/playbook.json",
                new JSONObject(), new JSONObject());
        return PlaybookRegistrySnapshot.loaded(
                Instant.now(), Map.of("alpha", alpha, "beta", beta), Map.of(), List.of());
    }

    @Test
    void chatCompletionsWire_carriesGenericParamsAndAllIds() {
        ToolDefinition td = PlaybookStartToolDefinitionBuilder.build(sampleRegistry());
        assertNotNull(td);
        LlmJsonSchemaCompat.assertArraysDeclareItems("start_playbook", td.getParametersSchema());

        @SuppressWarnings("unchecked")
        Map<String, Object> wire = ChatCompletionsApi.toolWireMapForBudget(td);
        assertEquals("function", wire.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> fn = (Map<String, Object>) wire.get("function");
        assertEquals("start_playbook", fn.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) fn.get("parameters");
        assertWireSchema(schema, "alpha", "beta");
    }

    @Test
    void anthropicWire_carriesGenericParamsAndAllIds() {
        ToolDefinition td = PlaybookStartToolDefinitionBuilder.build(sampleRegistry());
        assertNotNull(td);
        LlmJsonSchemaCompat.assertArraysDeclareItems("start_playbook", td.getParametersSchema());

        @SuppressWarnings("unchecked")
        Map<String, Object> wire = AnthropicMessagesApi.toolWireMapForBudget(td);
        assertEquals("start_playbook", wire.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) wire.get("input_schema");
        assertWireSchema(schema, "alpha", "beta");
        assertFalse(wire.containsKey("strict"));
    }

    @SuppressWarnings("unchecked")
    private static void assertWireSchema(Map<String, Object> schema, String... expectedIds) {
        assertEquals("object", schema.get("type"));
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertNotNull(props);
        Map<String, Object> playbookId = (Map<String, Object>) props.get("playbook_id");
        String idDesc = (String) playbookId.get("description");
        for (String id : expectedIds) {
            assertTrue(idDesc.contains(id), idDesc);
        }
        Map<String, Object> params = (Map<String, Object>) props.get("params");
        assertEquals("object", params.get("type"));
        assertEquals(Boolean.TRUE, params.get("additionalProperties"));
        assertFalse(params.containsKey("properties"));
        assertFalse(props.containsKey("assetType"));
        assertFalse(props.containsKey("regions"));
        Object required = schema.get("required");
        assertTrue(required instanceof String[] || required instanceof List);
    }
}
