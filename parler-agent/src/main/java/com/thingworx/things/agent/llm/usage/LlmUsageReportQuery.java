package com.thingworx.things.agent.llm.usage;

import java.time.Instant;
import java.util.Objects;

/**
 * Four business filters for helper report services (CC-7.6).
 */
public final class LlmUsageReportQuery {

    private final Instant rangeStart;
    private final Instant rangeEnd;
    private final String conversationId;
    private final String agentThing;
    private final String model;

    public LlmUsageReportQuery(
            Instant rangeStart,
            Instant rangeEnd,
            String conversationId,
            String agentThing,
            String model) {
        if (rangeStart == null || rangeEnd == null || !rangeStart.isBefore(rangeEnd)) {
            throw new IllegalArgumentException("timeRange requires start before end");
        }
        this.rangeStart = rangeStart;
        this.rangeEnd = rangeEnd;
        this.conversationId = blankToNull(conversationId);
        this.agentThing = blankToNull(agentThing);
        this.model = blankToNull(model);
    }

    public Instant getRangeStart() {
        return rangeStart;
    }

    public Instant getRangeEnd() {
        return rangeEnd;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getAgentThing() {
        return agentThing;
    }

    public String getModel() {
        return model;
    }

    public boolean matches(LlmCallEvent event) {
        if (event == null) {
            return false;
        }
        Instant started = event.getCallStartedAt();
        if (started == null || started.isBefore(rangeStart) || !started.isBefore(rangeEnd)) {
            return false;
        }
        if (conversationId != null && !conversationId.equals(event.getConversationId())) {
            return false;
        }
        if (agentThing != null && !agentThing.equals(event.getAgentThing())) {
            return false;
        }
        if (model != null && !model.equals(event.getRequestedModel())) {
            return false;
        }
        return true;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    @Override
    public String toString() {
        return "LlmUsageReportQuery{start=" + rangeStart + ", end=" + rangeEnd
                + ", conversationId=" + conversationId + ", agentThing=" + agentThing + ", model=" + model + '}';
    }

    @Override
    public int hashCode() {
        return Objects.hash(rangeStart, rangeEnd, conversationId, agentThing, model);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof LlmUsageReportQuery)) {
            return false;
        }
        LlmUsageReportQuery other = (LlmUsageReportQuery) obj;
        return Objects.equals(rangeStart, other.rangeStart)
                && Objects.equals(rangeEnd, other.rangeEnd)
                && Objects.equals(conversationId, other.conversationId)
                && Objects.equals(agentThing, other.agentThing)
                && Objects.equals(model, other.model);
    }
}
