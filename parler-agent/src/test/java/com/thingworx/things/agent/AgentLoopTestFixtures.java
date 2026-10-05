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
}
