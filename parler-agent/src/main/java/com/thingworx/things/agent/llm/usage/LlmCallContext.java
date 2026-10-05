package com.thingworx.things.agent.llm.usage;

import java.util.Objects;

import com.thingworx.things.agent.llm.LlmUsageWireIds;

/**
 * Non-wire call identity passed on {@link com.thingworx.things.agent.llm.LlmChatRequest}.
 * Immutable snapshot taken at call creation time (CC-7.2 / CC-7.3).
 */
public final class LlmCallContext {

    private final LlmCallKind callKind;
    private final String logicalCallId;
    private final String turnRequestId;
    private final String conversationId;
    private final String agentThing;
    private final Integer roundIndex;
    private final int attemptIndex;
    private final String retryOfCallId;
    private final LlmUsageWireIds wireIds;
    private final LlmCallContextPlanSnapshot contextPlan;

    private LlmCallContext(Builder builder) {
        this.callKind = Objects.requireNonNull(builder.callKind, "callKind");
        this.logicalCallId = requireNonBlank(builder.logicalCallId, "logicalCallId");
        this.turnRequestId = nullToEmpty(builder.turnRequestId);
        this.conversationId = nullToEmpty(builder.conversationId);
        this.agentThing = nullToEmpty(builder.agentThing);
        this.roundIndex = builder.roundIndex;
        this.attemptIndex = builder.attemptIndex > 0 ? builder.attemptIndex : 1;
        this.retryOfCallId = builder.retryOfCallId;
        this.wireIds = builder.wireIds;
        this.contextPlan = builder.contextPlan;
    }

    public static Builder builder(LlmCallKind callKind, String logicalCallId) {
        return new Builder(callKind, logicalCallId);
    }

    public LlmCallKind getCallKind() {
        return callKind;
    }

    public String getLogicalCallId() {
        return logicalCallId;
    }

    public String getTurnRequestId() {
        return turnRequestId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getAgentThing() {
        return agentThing;
    }

    public Integer getRoundIndex() {
        return roundIndex;
    }

    public int getAttemptIndex() {
        return attemptIndex;
    }

    public String getRetryOfCallId() {
        return retryOfCallId;
    }

    public LlmUsageWireIds getWireIds() {
        return wireIds;
    }

    public LlmCallContextPlanSnapshot getContextPlan() {
        return contextPlan;
    }

    public LlmCallContext withWireIds(LlmUsageWireIds ids) {
        if (Objects.equals(wireIds, ids)) {
            return this;
        }
        return toBuilder().wireIds(ids).build();
    }

    public Builder toBuilder() {
        return new Builder(callKind, logicalCallId)
                .turnRequestId(turnRequestId)
                .conversationId(conversationId)
                .agentThing(agentThing)
                .roundIndex(roundIndex)
                .attemptIndex(attemptIndex)
                .retryOfCallId(retryOfCallId)
                .wireIds(wireIds)
                .contextPlan(contextPlan);
    }

    public static final class Builder {
        private final LlmCallKind callKind;
        private final String logicalCallId;
        private String turnRequestId;
        private String conversationId;
        private String agentThing;
        private Integer roundIndex;
        private int attemptIndex = 1;
        private String retryOfCallId;
        private LlmUsageWireIds wireIds;
        private LlmCallContextPlanSnapshot contextPlan;

        private Builder(LlmCallKind callKind, String logicalCallId) {
            this.callKind = callKind;
            this.logicalCallId = logicalCallId;
        }

        public Builder turnRequestId(String value) {
            this.turnRequestId = value;
            return this;
        }

        public Builder conversationId(String value) {
            this.conversationId = value;
            return this;
        }

        public Builder agentThing(String value) {
            this.agentThing = value;
            return this;
        }

        public Builder roundIndex(Integer value) {
            this.roundIndex = value;
            return this;
        }

        public Builder attemptIndex(int value) {
            this.attemptIndex = value;
            return this;
        }

        public Builder retryOfCallId(String value) {
            this.retryOfCallId = value;
            return this;
        }

        public Builder wireIds(LlmUsageWireIds value) {
            this.wireIds = value;
            return this;
        }

        public Builder contextPlan(LlmCallContextPlanSnapshot value) {
            this.contextPlan = value;
            return this;
        }

        public LlmCallContext build() {
            return new LlmCallContext(this);
        }
    }

    private static String requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
