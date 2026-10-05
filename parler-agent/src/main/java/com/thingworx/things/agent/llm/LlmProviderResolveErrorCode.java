package com.thingworx.things.agent.llm;

/**
 * Typed outcomes for {@link LLMAPIProviderResolver} per {@code docs/agent/llm-api-provider.md} §5.6.
 */
public enum LlmProviderResolveErrorCode {
    NO_LLM_API_PROVIDER_CONFIGURED,
    LLM_API_PROVIDER_NOT_FOUND,
    LLM_API_PROVIDER_WRONG_TEMPLATE,
    LLM_API_PROVIDER_DISABLED
}
