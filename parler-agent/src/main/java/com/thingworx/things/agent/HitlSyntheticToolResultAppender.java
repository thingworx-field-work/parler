package com.thingworx.things.agent;

import java.util.List;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.HitlInterruptedBatchSiblingResult;
import com.thingworx.things.agent.tools.PendingApprovalRecord;

/**
 * Persists HITL synthetic {@code role=tool} rows to {@link AgentMessageStream} alongside in-memory conversation
 * mutation so durable replay matches non-HITL assistant/tool/assistant sequences.
 */
public final class HitlSyntheticToolResultAppender {

    private HitlSyntheticToolResultAppender() {}

    /**
     * Appends a synthetic tool-result to {@code msgs} and to the Agent message stream (best-effort; stream failures are
     * logged inside {@link AgentMessageStreamAppender} and do not propagate).
     */
    public static void appendDurable(
            List<ChatMessage> msgs,
            String toolCallId,
            String resultJson,
            String streamThreadKey,
            String streamEntrySource,
            String agentThingName) {
        appendDurable(msgs, toolCallId, resultJson, streamThreadKey, streamEntrySource, agentThingName, null);
    }

    /**
     * @param executedToolNameOrNull LLM tool id for {@link ChatMessage#getExecutedToolName()} (HITL gated tool); may be
     *                               null for non-tool-specific synthetic rows
     */
    public static void appendDurable(
            List<ChatMessage> msgs,
            String toolCallId,
            String resultJson,
            String streamThreadKey,
            String streamEntrySource,
            String agentThingName,
            String executedToolNameOrNull) {
        if (msgs == null || toolCallId == null || toolCallId.isEmpty()) {
            return;
        }
        ChatMessage tr = ChatMessage.toolResult(toolCallId, resultJson != null ? resultJson : "", executedToolNameOrNull);
        msgs.add(tr);
        if (streamThreadKey == null || streamThreadKey.isEmpty()) {
            return;
        }
        String src = streamEntrySource != null && !streamEntrySource.isEmpty() ? streamEntrySource : streamThreadKey;
        String agent = agentThingName != null ? agentThingName : "";
        AgentMessageStreamAppender.append(streamThreadKey, tr, src, agent, StreamTokenUsage.ZERO);
    }

    /**
     * Appends synthetic tool rows for interrupted parallel tool calls in an HITL batch (same durability as
     * {@link #appendDurable}).
     */
    public static void appendInterruptedSiblingsDurable(
            PendingApprovalRecord rec,
            List<ChatMessage> msgs,
            String streamThreadKey,
            String streamEntrySource,
            String agentThingName) {
        if (rec == null || msgs == null) {
            return;
        }
        for (ToolCall tc : rec.getInterruptedBatchSiblingToolCalls()) {
            if (tc != null && tc.getId() != null && !tc.getId().isEmpty()) {
                appendDurable(msgs, tc.getId(), HitlInterruptedBatchSiblingResult.JSON, streamThreadKey, streamEntrySource,
                        agentThingName, tc.getFunctionName());
            }
        }
    }
}
