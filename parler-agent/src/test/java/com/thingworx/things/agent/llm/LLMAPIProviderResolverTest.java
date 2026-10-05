package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LLMAPIProviderResolverTest {

    @Test
    void resolve_blankRef_throwsNoProviderConfigured() {
        LlmProviderResolveException e = assertThrows(LlmProviderResolveException.class,
                () -> LLMAPIProviderResolver.resolve(""));
        assertEquals(LlmProviderResolveErrorCode.NO_LLM_API_PROVIDER_CONFIGURED, e.getCode());
    }

    @Test
    void resolve_whitespaceRef_throwsNoProviderConfigured() {
        LlmProviderResolveException e = assertThrows(LlmProviderResolveException.class,
                () -> LLMAPIProviderResolver.resolve("   "));
        assertEquals(LlmProviderResolveErrorCode.NO_LLM_API_PROVIDER_CONFIGURED, e.getCode());
    }

    @Test
    void resolve_nullRef_throwsNoProviderConfigured() {
        LlmProviderResolveException e = assertThrows(LlmProviderResolveException.class,
                () -> LLMAPIProviderResolver.resolve(null));
        assertEquals(LlmProviderResolveErrorCode.NO_LLM_API_PROVIDER_CONFIGURED, e.getCode());
    }
}
