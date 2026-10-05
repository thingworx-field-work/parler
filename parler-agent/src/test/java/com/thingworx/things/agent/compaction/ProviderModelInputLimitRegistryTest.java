package com.thingworx.things.agent.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

class ProviderModelInputLimitRegistryTest {

    @Test
    void normalizeModelId_stripsAzureStyleSuffix() {
        assertEquals("gpt-4o", ProviderModelInputLimitRegistry.normalizeModelId("GPT-4o@deployment-eu"));
    }

    @Test
    void lookupOpenAi_gpt4o_cluster() {
        OptionalInt lim = ProviderModelInputLimitRegistry.lookupInputTokenLimit("openai-chat-completions-v4", "gpt-4o");
        assertTrue(lim.isPresent());
        assertEquals(128_000, lim.getAsInt());
    }

    @Test
    void lookupAnthropic_sonnet4_family() {
        OptionalInt lim = ProviderModelInputLimitRegistry.lookupInputTokenLimit("anthropic-messages-v1", "claude-sonnet-4-20250514");
        assertTrue(lim.isPresent());
        assertEquals(200_000, lim.getAsInt());
    }

    @Test
    void lookupUnknownModel_empty() {
        assertFalse(ProviderModelInputLimitRegistry.lookupInputTokenLimit("openai-chat-completions-v5", "unknown-model-xyz")
                .isPresent());
    }
}
