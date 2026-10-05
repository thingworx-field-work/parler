package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LlmUsageWireIdsTest {

    @Test
    void forProviderThing_embedsFourFields() {
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing(
                "MyProvider", "OpenAIChatV5Provider", "openai-chat-completions-v5", "gpt-5.4-mini");
        assertEquals("MyProvider", ids.getProviderThingName());
        assertEquals("OpenAIChatV5Provider", ids.getProviderTemplateName());
        assertEquals("openai-chat-completions-v5", ids.getApiShapeId());
        assertEquals("gpt-5.4-mini", ids.getModel());
    }

    @Test
    void withModel_replacesModelOnly() {
        LlmUsageWireIds base = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        LlmUsageWireIds next = base.withModel("gpt-4.1-mini");
        assertEquals("gpt-4.1-mini", next.getModel());
        assertEquals("openai-chat-completions-v4", next.getApiShapeId());
    }
}
