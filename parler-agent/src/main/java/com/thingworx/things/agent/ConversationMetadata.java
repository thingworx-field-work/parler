package com.thingworx.things.agent;

import org.joda.time.DateTime;

/**
 * Thread row fields used by Stream rehydration and history export ({@code AgentThreadDataTable}).
 */
public final class ConversationMetadata {

    private final String conversationId;
    private final String agentName;
    private final DateTime historyClearedAtOrNull;

    public ConversationMetadata(String conversationId, String agentName, DateTime historyClearedAtOrNull) {
        this.conversationId = conversationId != null ? conversationId : "";
        this.agentName = agentName != null ? agentName : "";
        this.historyClearedAtOrNull = historyClearedAtOrNull;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getAgentName() {
        return agentName;
    }

    /**
     * When non-null, effective persisted history for UI/LLM begins strictly after this instant (see
     * {@link StreamHistoryBounds#queryStartAfterClear(DateTime)}).
     */
    public DateTime getHistoryClearedAtOrNull() {
        return historyClearedAtOrNull;
    }
}
