package com.thingworx.things.agent.playbook;

/**
 * Optional callback after each successful Playbook {@code tool_call} / {@code fan_out} child so AlwaysOn can mirror
 * top-level tool chart/table/tabular wire emission for the same {@code request_id}.
 */
@FunctionalInterface
public interface PlaybookArtifactEmitter {

    /**
     * @param playbookId effective playbook id for the run
     * @param nodeId node id (including {@code trends[0]} style ids for fan-out children)
     * @param toolName executed tool name (LLM-facing id)
     * @param toolCallId synthetic id (typically {@code pb-<uuid>}) matching the Stream tool row
     * @param toolResultJson full tool JSON envelope (same string persisted for top-level tools where applicable)
     */
    void onToolResult(String playbookId, String nodeId, String toolName, String toolCallId, String toolResultJson);
}
