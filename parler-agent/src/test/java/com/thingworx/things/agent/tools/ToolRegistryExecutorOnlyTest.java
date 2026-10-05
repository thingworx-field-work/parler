package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;

import java.util.List;
import java.util.Map;

class ToolRegistryExecutorOnlyTest {

    @Test
    void direct_executor_only_excludes_alias_keys() {
        ToolRegistry reg = new ToolRegistry();
        reg.register(new ToolDefinition("canonical_tool", "d", Map.of("type", "object")), c -> "x");
        reg.registerExecutorAlias("alias_a", "canonical_tool");
        reg.registerExecutorOnly("replay_direct", c -> "{}");
        assertEquals(List.of("replay_direct"), reg.getDirectExecutorOnlyToolNames());
    }

    @Test
    void executor_only_runs_without_tool_definition() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        reg.registerExecutorOnly("replay_only_x", c -> "{\"status\":\"ok\",\"tool\":\"" + c.getFunctionName() + "\"}");
        assertFalse(reg.hasTool("replay_only_x"));
        assertTrue(reg.getExecutorOnlyAliases().contains("replay_only_x"));
        assertEquals(0, reg.getAllDefinitions().size());
        String out = reg.executeTool(new ToolCall("id1", "replay_only_x", "{}"));
        assertTrue(out.contains("replay_only_x"));
    }

    @Test
    void register_after_executor_only_same_name_throws() {
        ToolRegistry reg = new ToolRegistry();
        reg.registerExecutorOnly("dup", c -> "x");
        assertThrows(IllegalStateException.class,
                () -> reg.register(new ToolDefinition("dup", "d", Map.of("type", "object")), c -> "y"));
    }

    @Test
    void register_executor_only_twice_throws() {
        ToolRegistry reg = new ToolRegistry();
        reg.registerExecutorOnly("once", c -> "1");
        assertThrows(IllegalStateException.class, () -> reg.registerExecutorOnly("once", c -> "2"));
    }

    @Test
    void unregister_removes_executor_only() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        reg.registerExecutorOnly("gone", c -> "1");
        reg.unregister("gone");
        assertThrows(IllegalArgumentException.class, () -> reg.executeTool(new ToolCall("id", "gone", "{}")));
    }

    @Test
    void built_in_get_entity_is_executor_only_and_runs() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg);
        assertFalse(reg.hasTool("get_entity"));
        assertTrue(reg.getExecutorOnlyAliases().contains("get_entity"));
        assertEquals(28, reg.getAllDefinitions().size());
        String json = reg.executeTool(new ToolCall("optb", "get_entity", "{}"));
        assertTrue(json.contains("\"status\""), json);
    }
}
