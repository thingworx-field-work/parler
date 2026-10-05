package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Registration gating for document-knowledge built-ins (docs/agent/document-chunk-tools.md §4.1).
 */
class DocumentKnowledgeToolRegistrationTest {

    private static Set<String> reservedBuiltInNames(ToolRegistry reg) {
        return ReservedBuiltinToolNames.fromRegistry(reg);
    }

    @Test
    void disabled_does_not_register_or_reserve_document_tool_names() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false, false);

        assertFalse(reg.hasTool("search_document_chunks"));
        assertFalse(reg.hasTool("get_document_chunk"));
        assertFalse(reg.hasTool("resolve_document_set"));
        assertFalse(reg.getExecutorOnlyAliases().contains("search_document_chunks"));
        assertFalse(reg.getExecutorOnlyAliases().contains("get_document_chunk"));
        assertFalse(reg.getExecutorOnlyAliases().contains("resolve_document_set"));

        Set<String> reserved = reservedBuiltInNames(reg);
        assertFalse(reserved.contains("search_document_chunks"));
        assertFalse(reserved.contains("get_document_chunk"));
        assertFalse(reserved.contains("resolve_document_set"));
    }

    @Test
    void enabled_registers_document_tools_and_reserves_names() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false, true);

        assertTrue(reg.hasTool("search_document_chunks"));
        assertTrue(reg.hasTool("get_document_chunk"));
        assertTrue(reg.hasTool("resolve_document_set"));
        assertFalse(reg.getExecutorOnlyAliases().contains("search_document_chunks"));
        assertFalse(reg.getExecutorOnlyAliases().contains("get_document_chunk"));
        assertFalse(reg.getExecutorOnlyAliases().contains("resolve_document_set"));

        Set<String> reserved = reservedBuiltInNames(reg);
        assertTrue(reserved.contains("search_document_chunks"));
        assertTrue(reserved.contains("get_document_chunk"));
        assertTrue(reserved.contains("resolve_document_set"));
        assertEquals(31, reg.getAllDefinitions().size());
    }
}
