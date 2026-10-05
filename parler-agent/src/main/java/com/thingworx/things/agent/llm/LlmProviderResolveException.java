package com.thingworx.things.agent.llm;

/**
 * Thrown when {@link LLMAPIProviderResolver} cannot resolve {@code llmApiProviderRef}.
 */
public final class LlmProviderResolveException extends Exception {

    private static final long serialVersionUID = 1L;

    private final LlmProviderResolveErrorCode code;

    public LlmProviderResolveException(LlmProviderResolveErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public LlmProviderResolveErrorCode getCode() {
        return code;
    }
}
