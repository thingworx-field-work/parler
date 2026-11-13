package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.ToolResultSettings.JsonMode;

/** P35/P38 settings: {@code toolResultJsonMode} normalization (R22). */
class ToolResultSettingsTest {

    @Test
    void json_mode_values() {
        assertEquals(JsonMode.STRING, ToolResultSettings.fromRaw(null).jsonMode());
        assertEquals(JsonMode.STRING, ToolResultSettings.fromRaw("").jsonMode());
        assertEquals(JsonMode.STRING, ToolResultSettings.fromRaw("string").jsonMode());
        assertEquals(JsonMode.NODE, ToolResultSettings.fromRaw(" Node ").jsonMode());
        assertTrue(ToolResultSettings.fromRaw("node").warnings().isEmpty());
    }

    @Test
    void an_invalid_json_mode_resets_to_string_with_a_clamp_warning() {
        ToolResultSettings s = ToolResultSettings.fromRaw("tree");
        assertEquals(JsonMode.STRING, s.jsonMode());
        assertEquals(1, s.warnings().size());
        assertEquals(ToolResultSettings.CONFIG_VALUE_CLAMPED, s.warnings().get(0).code());
    }

    @Test
    void the_setting_is_declared_on_the_agent_settings_surface() {
        assertEquals("toolResultJsonMode", AgentBaseThing.Config.ToolResultJsonMode);
    }
}
