package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolDefinition;

/**
 * E2: shared reserved-name authority — dynamic names always reserved; case-sensitive
 * equality; registry + executor-only aliases included.
 */
class ReservedBuiltinToolNamesTest {

    @Test
    void alwaysReservedDynamicNamesIncludesStartPlaybook() {
        assertTrue(ReservedBuiltinToolNames.alwaysReservedDynamicNames()
                .contains(ReservedBuiltinToolNames.START_PLAYBOOK));
        assertEquals(Set.of("start_playbook"), ReservedBuiltinToolNames.alwaysReservedDynamicNames());
    }

    @Test
    void fromRegistryIncludesStartPlaybookEvenWhenNotRegistered() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg);
        Set<String> reserved = ReservedBuiltinToolNames.fromRegistry(reg);
        assertFalse(reg.getAllDefinitions().stream().anyMatch(d -> "start_playbook".equals(d.getName())));
        assertFalse(reg.getExecutorOnlyAliases().contains("start_playbook"));
        assertTrue(reserved.contains("start_playbook"));
    }

    @Test
    void fromRegistryUnionsDefinitionsAndExecutorOnlyAliases() {
        ToolRegistry reg = new ToolRegistry();
        reg.register(new ToolDefinition("canonical_tool", "d", Map.of("type", "object")), c -> "{}");
        reg.registerExecutorAlias("alias_a", "canonical_tool");
        reg.registerExecutorOnly("replay_only_x", c -> "{}");
        Set<String> reserved = ReservedBuiltinToolNames.fromRegistry(reg);
        assertTrue(reserved.contains("canonical_tool"));
        assertTrue(reserved.contains("alias_a"));
        assertTrue(reserved.contains("replay_only_x"));
        assertTrue(reserved.contains("start_playbook"));
    }

    @Test
    void comparisonIsExactCaseSensitive() {
        ToolRegistry reg = new ToolRegistry();
        reg.register(new ToolDefinition("Foo", "d", Map.of("type", "object")), c -> "{}");
        Set<String> reserved = ReservedBuiltinToolNames.fromRegistry(reg);
        assertTrue(reserved.contains("Foo"));
        assertFalse(reserved.contains("foo"));
        assertFalse(reserved.contains("FOO"));
    }

    @Test
    void emptyRegistryStillReservesDynamicNames() {
        Set<String> reserved = ReservedBuiltinToolNames.fromRegistry(new ToolRegistry());
        assertEquals(Set.of("start_playbook"), reserved);
    }

    /**
     * Permanent E2 parity gate: every always-reserved dynamic name must appear in the
     * shared authority for a fully registered built-in registry. Adding a dynamic name without
     * registering it in {@link ReservedBuiltinToolNames#alwaysReservedDynamicNames()} fails here
     * once the expected set is updated — the production authority remains the single source.
     */
    @Test
    void permanentParity_dynamicNamesSubsetOfFullAuthority() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg);
        Set<String> reserved = ReservedBuiltinToolNames.fromRegistry(reg);
        Set<String> dynamic = ReservedBuiltinToolNames.alwaysReservedDynamicNames();
        assertFalse(dynamic.isEmpty());
        assertTrue(reserved.containsAll(dynamic),
                "fromRegistry must include every alwaysReservedDynamicNames entry");
        // Case-sensitive: dynamic names are not reserved under alternate casing.
        for (String name : dynamic) {
            if (!name.equals(name.toUpperCase(java.util.Locale.ROOT))) {
                assertFalse(reserved.contains(name.toUpperCase(java.util.Locale.ROOT)));
            }
        }
    }
}
