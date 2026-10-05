package com.thingworx.things.agent.configrepo;

import java.util.Objects;
import java.util.Optional;

import com.thingworx.things.agent.llm.ToolDefinition;

/** One extended tool row from {@code extended_tools.json} after registration-time resolution. */
public final class ExtendedToolDefinition {

    private final String llmName;
    private final String title;
    private final String whenToUse;
    private final String resolvedTargetThingName;
    private final String serviceName;
    private final boolean hitlBypass;
    /** When true, tool executes but is omitted from the merged LLM tool list. */
    private final boolean executorOnly;
    private final ToolDefinition toolDefinition;
    /** U7 / G13 capability metadata; null for legacy entries without capability fields. */
    private final ServiceCapabilityMetadata capability;

    public ExtendedToolDefinition(String llmName, String title, String whenToUse, String resolvedTargetThingName,
            String serviceName, boolean hitlBypass, boolean executorOnly, ToolDefinition toolDefinition) {
        this(llmName, title, whenToUse, resolvedTargetThingName, serviceName, hitlBypass, executorOnly, toolDefinition,
                null);
    }

    public ExtendedToolDefinition(String llmName, String title, String whenToUse, String resolvedTargetThingName,
            String serviceName, boolean hitlBypass, boolean executorOnly, ToolDefinition toolDefinition,
            ServiceCapabilityMetadata capability) {
        this.llmName = llmName;
        this.title = title;
        this.whenToUse = whenToUse;
        this.resolvedTargetThingName = resolvedTargetThingName;
        this.serviceName = serviceName;
        this.hitlBypass = hitlBypass;
        this.executorOnly = executorOnly;
        this.toolDefinition = toolDefinition;
        this.capability = capability;
    }

    public String llmName() {
        return llmName;
    }

    public String title() {
        return title;
    }

    public String whenToUse() {
        return whenToUse;
    }

    public String resolvedTargetThingName() {
        return resolvedTargetThingName;
    }

    public String serviceName() {
        return serviceName;
    }

    public boolean hitlBypass() {
        return hitlBypass;
    }

    public boolean executorOnly() {
        return executorOnly;
    }

    public ToolDefinition toolDefinition() {
        return toolDefinition;
    }

    public Optional<ServiceCapabilityMetadata> capability() {
        return Optional.ofNullable(capability);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ExtendedToolDefinition)) {
            return false;
        }
        ExtendedToolDefinition that = (ExtendedToolDefinition) o;
        return hitlBypass == that.hitlBypass
                && executorOnly == that.executorOnly
                && Objects.equals(llmName, that.llmName)
                && Objects.equals(title, that.title)
                && Objects.equals(whenToUse, that.whenToUse)
                && Objects.equals(resolvedTargetThingName, that.resolvedTargetThingName)
                && Objects.equals(serviceName, that.serviceName)
                && Objects.equals(toolDefinition, that.toolDefinition)
                && Objects.equals(capability, that.capability);
    }

    @Override
    public int hashCode() {
        return Objects.hash(llmName, title, whenToUse, resolvedTargetThingName, serviceName, hitlBypass, executorOnly,
                toolDefinition, capability);
    }
}
