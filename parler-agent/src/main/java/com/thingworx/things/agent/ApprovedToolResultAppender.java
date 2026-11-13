package com.thingworx.things.agent;

import java.util.List;

import com.thingworx.things.agent.ToolResultEgressGateway.EgressResult;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.tools.AgentToolContext;

/**
 * Presents an approved (HITL) tool's result to the model exactly once (topic round 6, R26d). The approve continuation
 * executes the tool itself and never passes through {@code AgentLoop}'s egress call, so this is where the presented
 * string is finalized before {@link HitlSyntheticToolResultAppender#appendDurable} adds it to the in-memory history
 * and the message stream.
 *
 * <p>The input is the result after {@code TabularChartRoundHooks.augmentJsonSourceHandle}, so the presented row, the
 * history and the next model request carry the same chart {@code cacheId}. Only the string given to
 * {@code appendDurable} is the presented one; the evidence seed, the completed gated result and the chart and task
 * hooks keep the raw string.
 */
final class ApprovedToolResultAppender {

    private ApprovedToolResultAppender() {
    }

    /**
     * Finalizes and appends the approved result: it passes through the egress gateway once, with the budget captured
     * when the approved execution started, so it gets the same reductions, overflow response and note as an ordinary
     * result; when it was reduced or annotated, the raw result is kept as the full body exactly as the ordinary path
     * does. The stored budget is removed afterwards.
     *
     * @return the presented string that was appended
     */
    static String append(List<ChatMessage> messages, String toolCallId, String executedToolName, String rawResult,
            List<String> dataClassification, ResultBudget budget, String streamThreadKey, String streamEntrySource,
            String agentThingName) {
        String raw = rawResult != null ? rawResult : "";
        String presented;
        try {
            EgressResult egress = ToolResultEgressGateway.compactForLlmAppend(executedToolName, toolCallId, raw, null,
                    dataClassification != null ? dataClassification : List.of(), budget);
            presented = egress.getLlmContent();
            if ((egress.isCompacted() || egress.isAnnotated())
                    && AgentToolContext.peekToolEgressFullJsonForToolCall(toolCallId) == null) {
                AgentToolContext.setToolEgressFullJsonForToolCall(toolCallId, raw);
            }
        } finally {
            AgentToolContext.removeResultBudget(toolCallId);
        }
        HitlSyntheticToolResultAppender.appendDurable(messages, toolCallId, presented, streamThreadKey,
                streamEntrySource, agentThingName, executedToolName);
        return presented;
    }
}
