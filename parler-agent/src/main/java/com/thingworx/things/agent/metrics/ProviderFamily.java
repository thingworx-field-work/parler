package com.thingworx.things.agent.metrics;

/**
 * Bounded {@code provider_family} dimension, mapped from the API shape ID prefix. Unlike the Stream helper, Azure
 * OpenAI is kept separate from OpenAI.
 */
public enum ProviderFamily {
    AZURE_OPENAI("azure_openai"),
    OPENAI("openai"),
    ANTHROPIC("anthropic"),
    OTHER("other");

    private final String wireValue;

    ProviderFamily(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static ProviderFamily fromApiShapeId(String apiShapeId) {
        if (apiShapeId == null) {
            return OTHER;
        }
        if (apiShapeId.startsWith("azure-openai-")) {
            return AZURE_OPENAI;
        }
        if (apiShapeId.startsWith("openai-")) {
            return OPENAI;
        }
        if (apiShapeId.startsWith("anthropic-")) {
            return ANTHROPIC;
        }
        return OTHER;
    }
}
