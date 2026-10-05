package com.thingworx.things.agent.llm;

import java.util.Objects;

/**
 * Telemetry identity for LLM rounds per {@code docs/agent/llm-api-provider.md} §10.2.
 */
public final class LlmUsageWireIds {

    private final String providerThingName;
    private final String providerTemplateName;
    private final String apiShapeId;
    private final String model;

    public LlmUsageWireIds(
            String providerThingName,
            String providerTemplateName,
            String apiShapeId,
            String model) {
        this.providerThingName = providerThingName != null ? providerThingName : "";
        this.providerTemplateName = providerTemplateName != null ? providerTemplateName : "";
        this.apiShapeId = apiShapeId != null ? apiShapeId : "";
        this.model = model != null ? model : "";
    }

    public String getProviderThingName() {
        return providerThingName;
    }

    public String getProviderTemplateName() {
        return providerTemplateName;
    }

    public String getApiShapeId() {
        return apiShapeId;
    }

    public String getModel() {
        return model;
    }

    public LlmUsageWireIds withModel(String newModel) {
        return new LlmUsageWireIds(providerThingName, providerTemplateName, apiShapeId, newModel);
    }

    public static LlmUsageWireIds forProviderThing(
            String providerThingName,
            String providerTemplateName,
            String apiShapeId,
            String model) {
        return new LlmUsageWireIds(providerThingName, providerTemplateName, apiShapeId, model);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LlmUsageWireIds)) {
            return false;
        }
        LlmUsageWireIds that = (LlmUsageWireIds) o;
        return Objects.equals(providerThingName, that.providerThingName)
                && Objects.equals(providerTemplateName, that.providerTemplateName)
                && Objects.equals(apiShapeId, that.apiShapeId)
                && Objects.equals(model, that.model);
    }

    @Override
    public int hashCode() {
        return Objects.hash(providerThingName, providerTemplateName, apiShapeId, model);
    }
}
