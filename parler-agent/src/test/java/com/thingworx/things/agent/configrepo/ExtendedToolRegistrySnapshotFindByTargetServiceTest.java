package com.thingworx.things.agent.configrepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolDefinition;

class ExtendedToolRegistrySnapshotFindByTargetServiceTest {

    private static ExtendedToolDefinition def(String llmName, String thing, String svc) {
        return new ExtendedToolDefinition(llmName, "t", "w", thing, svc, false, false,
                new ToolDefinition(llmName, "d", Map.of()));
    }

    @Test
    void findByTargetService_returns_smallest_llm_name_when_duplicate_targets() {
        ExtendedToolRegistrySnapshot reg = ExtendedToolRegistrySnapshot.ok(List.of(
                def("zebra_tool", "MyThing", "RunIt"),
                def("alpha_tool", "MyThing", "RunIt")));
        assertTrue(reg.findByTargetService("MyThing", "RunIt").isPresent());
        assertEquals("alpha_tool", reg.findByTargetService("MyThing", "RunIt").get().llmName());
    }

    @Test
    void findByTargetService_empty_when_no_match() {
        ExtendedToolRegistrySnapshot reg = ExtendedToolRegistrySnapshot.ok(List.of(def("a", "T1", "S1")));
        assertTrue(reg.findByTargetService("T2", "S1").isEmpty());
    }
}
