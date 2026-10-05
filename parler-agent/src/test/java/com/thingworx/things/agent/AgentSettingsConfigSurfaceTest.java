package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.thingworx.metadata.annotations.ThingworxConfigurationTableDefinition;
import com.thingworx.metadata.annotations.ThingworxConfigurationTableDefinitions;
import com.thingworx.metadata.annotations.ThingworxFieldDefinition;

/**
 * Guards that runtime-read AgentSettings keys are actually declared on the {@code @ThingworxFieldDefinition}
 * configuration surface, so an operator can set them in Composer. A field that is read (e.g. in
 * {@code AgentBaseThing.initializeThing}) but missing from the annotated DataShape is unreachable in normal ops —
 * the class of miss this test guards against.
 */
class AgentSettingsConfigSurfaceTest {

    private static ThingworxFieldDefinition[] agentSettingsFields() {
        ThingworxConfigurationTableDefinitions tables =
                AgentBaseThing.class.getAnnotation(ThingworxConfigurationTableDefinitions.class);
        assertNotNull(tables, "AgentBaseThing must carry @ThingworxConfigurationTableDefinitions");
        for (ThingworxConfigurationTableDefinition table : tables.tables()) {
            if ("AgentSettings".equals(table.name())) {
                return table.dataShape().fields();
            }
        }
        fail("AgentSettings configuration table not found");
        return new ThingworxFieldDefinition[0];
    }

    private static ThingworxFieldDefinition field(String name) {
        return Arrays.stream(agentSettingsFields())
                .filter(f -> name.equals(f.name()))
                .findFirst()
                .orElse(null);
    }

    @Test
    void toolAdmissionMode_isDeclaredOnAgentSettingsSurface() {
        ThingworxFieldDefinition f = field("toolAdmissionMode");
        assertNotNull(f, "toolAdmissionMode must be declared on the AgentSettings DataShape so operators can enable narrow");
        assertEquals("STRING", f.baseType());
        assertTrue(Arrays.asList(f.aspects()).contains("defaultValue:off"),
                "toolAdmissionMode must default to off; aspects=" + Arrays.toString(f.aspects()));
    }

    @Test
    void configKeyConstantMatchesDeclaredField() {
        // The constant the runtime reads must match the declared field name (catches drift between the two).
        assertEquals("toolAdmissionMode", AgentBaseThing.Config.ToolAdmissionMode);
        assertNotNull(field(AgentBaseThing.Config.ToolAdmissionMode));
    }

    @Test
    void toolAdmissionMode_descriptionIsNotStaleAboutLazy() {
        // Guards against doc drift: the Composer description must not claim lazy is a
        // not-yet-implemented no-op once M3 ships. It must name all three modes truthfully.
        ThingworxFieldDefinition f = field("toolAdmissionMode");
        assertNotNull(f);
        String d = f.description().toLowerCase(java.util.Locale.ROOT);
        assertTrue(d.contains("off") && d.contains("narrow") && d.contains("lazy"),
                "description must name all three modes; was: " + f.description());
        assertFalse(d.contains("behaves as off"),
                "lazy is implemented; description must not say it behaves as off: " + f.description());
        assertFalse(d.contains("not yet implemented"), "stale 'not yet implemented' in: " + f.description());
        assertFalse(d.contains("reserved"), "stale 'reserved' qualifier on lazy in: " + f.description());
    }
}
