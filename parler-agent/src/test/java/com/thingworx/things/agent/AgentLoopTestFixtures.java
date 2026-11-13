package com.thingworx.things.agent;

/**
 * Same package as {@link AgentLoop} so compaction tests can construct package-private
 * {@link AgentLoop.AgentResult} fixtures without widening production API.
 */
public final class AgentLoopTestFixtures {

    private AgentLoopTestFixtures() {
    }

    public static AgentLoop.AgentResult successResult(String content) {
        return AgentLoop.AgentResult.success(content, 1, 0, 0, StreamTokenUsage.ZERO);
    }

    /** Exercise the real continuation entry from tests in tool packages without widening production APIs. */
    public static AgentLoop.AgentResult runAfterApproval(AgentLoop loop,
            java.util.List<com.thingworx.things.agent.llm.ChatMessage> messages,
            java.util.List<com.thingworx.things.agent.llm.ToolDefinition> tools,
            com.thingworx.things.agent.tools.PendingApprovalRecord pending, String completedResult) throws Exception {
        return loop.runAfterApproval(messages, tools, null, pending, completedResult);
    }
}
