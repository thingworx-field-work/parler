package com.thingworx.things.agent.llm;

import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.Thing;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.things.agent.llm.providers.LLMAPIProviderThing;

/**
 * Resolves {@code AgentThing.llmApiProviderRef} to a live {@link LLMAPIProviderThing} ({@code docs/agent/llm-api-provider.md} §5.6).
 */
public final class LLMAPIProviderResolver {

    private LLMAPIProviderResolver() {}

    public static LLMAPIProviderThing resolve(String llmApiProviderRef) throws LlmProviderResolveException {
        if (llmApiProviderRef == null || llmApiProviderRef.isBlank()) {
            throw new LlmProviderResolveException(
                    LlmProviderResolveErrorCode.NO_LLM_API_PROVIDER_CONFIGURED,
                    "AgentThing has no llmApiProviderRef set");
        }
        Object rent = PlatformAccess.findProgrammatic(
                llmApiProviderRef.trim(), RelationshipTypes.ThingworxRelationshipTypes.Thing);
        if (!(rent instanceof Thing)) {
            throw new LlmProviderResolveException(
                    LlmProviderResolveErrorCode.LLM_API_PROVIDER_NOT_FOUND,
                    "no Thing named '" + llmApiProviderRef + "'");
        }
        Thing thing = (Thing) rent;
        if (!(thing instanceof LLMAPIProviderThing)) {
            throw new LlmProviderResolveException(
                    LlmProviderResolveErrorCode.LLM_API_PROVIDER_WRONG_TEMPLATE,
                    "Thing '" + llmApiProviderRef + "' is not an LLM API Provider");
        }
        LLMAPIProviderThing provider = (LLMAPIProviderThing) thing;
        if (!provider.isEnabled()) {
            throw new LlmProviderResolveException(
                    LlmProviderResolveErrorCode.LLM_API_PROVIDER_DISABLED,
                    "Provider '" + llmApiProviderRef + "' is disabled");
        }
        return provider;
    }
}
