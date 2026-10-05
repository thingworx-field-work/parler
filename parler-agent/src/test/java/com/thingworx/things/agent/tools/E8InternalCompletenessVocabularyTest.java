package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptorSupport;

/**
 * E8 / BP6: {@link SourceDescriptorSupport#putPublicEnvelopeFields} builds the public packaging
 * subset. Tool <em>descriptions</em> still omit the word {@code completeness} (fields are on
 * success JSON per {@code TABULAR_INSIGHT} §5, not LLM prose).
 */
class E8InternalCompletenessVocabularyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void putPublicEnvelopeFieldsEmitsCompletenessAndCounts() {
        SourceDescriptor d = SourceDescriptor.builder()
                .rowsReturned(3L)
                .build();
        ObjectNode root = MAPPER.createObjectNode();
        SourceDescriptorSupport.putPublicEnvelopeFields(root, d);
        assertTrue(root.has("completeness"), "helper emits completeness");
        assertTrue(root.has("counts"), "helper emits counts");
    }

    @Test
    void advertisedToolDescriptionsStillOmitCompletenessProse() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        for (String name : new String[] {"fetch_cached_result", "tabulate_cached_result", "summarize_cached_result"}) {
            ToolDefinition d = reg.getAllDefinitions().stream()
                    .filter(t -> name.equals(t.getName()))
                    .findFirst()
                    .orElseThrow();
            assertFalse(d.getDescription().contains("completeness"), name + ": " + d.getDescription());
        }
    }
}
