package com.thingworx.things.agent.llm;

import java.util.Map;

/**
 * Represents a tool that the LLM can invoke during the agent loop.
 * Maps to the OpenAI function-calling schema shared by all supported APIs.
 */
public class ToolDefinition {

    private final String name;
    private final String description;
    private final Map<String, Object> parametersSchema;
    private final boolean playbookSafe;

    public ToolDefinition(String name, String description, Map<String, Object> parametersSchema) {
        this(name, description, parametersSchema, false);
    }

    public ToolDefinition(String name, String description, Map<String, Object> parametersSchema,
            boolean playbookSafe) {
        this.name = name;
        this.description = description;
        this.parametersSchema = parametersSchema;
        this.playbookSafe = playbookSafe;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public Map<String, Object> getParametersSchema() { return parametersSchema; }

    /** When true, {@code tool_call} nodes may invoke this tool during Playbook execution. */
    public boolean isPlaybookSafe() { return playbookSafe; }
}
