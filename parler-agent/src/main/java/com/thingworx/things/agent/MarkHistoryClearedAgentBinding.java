package com.thingworx.things.agent;

/**
 * Optional {@code AgentThreadDataTable.agentName} binding when writing {@code historyClearedAt}. Used by
 * {@link AgentThreadDataTableSupport#markHistoryCleared}; direct {@link AgentThing#SetConversationHistoryCutoff} passes
 * {@code null} so no thread-row agent comparison runs (turn-actions / conversation-continuity product policy).
 */
public final class MarkHistoryClearedAgentBinding {

    private MarkHistoryClearedAgentBinding() {}

    /**
     * When {@code expectedAgentThingName} is non-blank, it must equal the trimmed thread row {@code agentName}.
     *
     * @throws Exception when binding is required and does not match
     */
    public static void verifyExpectedAgentThingIfPresent(String expectedAgentThingName, String rowAgentName,
            String conversationId, String messagePrefix) throws Exception {
        String agentName = rowAgentName != null ? rowAgentName.trim() : "";
        if (expectedAgentThingName != null && !expectedAgentThingName.trim().isEmpty()
                && !expectedAgentThingName.trim().equals(agentName)) {
            throw new Exception(messagePrefix + ": Thread agent mismatch for conversation " + conversationId + ".");
        }
    }
}
