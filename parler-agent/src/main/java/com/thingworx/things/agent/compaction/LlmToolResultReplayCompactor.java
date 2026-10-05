package com.thingworx.things.agent.compaction;

import java.util.List;

import org.slf4j.Logger;

import com.thingworx.things.agent.llm.ChatMessage;

/**
 * Phase 2 round-boundary replay compaction: Tier A matrix seal, then Tier 0 cohort merge when applicable.
 */
public final class LlmToolResultReplayCompactor {

    private LlmToolResultReplayCompactor() {}

    /**
     * Mutates consecutive {@link ChatMessage.Role#TOOL} bodies after the assistant tool-call row.
     *
     * @return aggregate character stats (raw = sum of tool bodies before any rewrite; replay = sum after matrix + cohort)
     */
    public static LlmToolResultMatrixSealer.SealStats compactBatch(List<ChatMessage> messages,
            int assistantToolCallsIndex, boolean enabled, Logger log) {
        if (!enabled || messages == null || assistantToolCallsIndex < 0
                || assistantToolCallsIndex >= messages.size()) {
            return LlmToolResultMatrixSealer.SealStats.EMPTY;
        }
        ChatMessage asst = messages.get(assistantToolCallsIndex);
        if (asst.getRole() != ChatMessage.Role.ASSISTANT || !asst.hasToolCalls()) {
            return LlmToolResultMatrixSealer.SealStats.EMPTY;
        }
        int rawTotal = 0;
        for (int i = assistantToolCallsIndex + 1; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m.getRole() != ChatMessage.Role.TOOL) {
                break;
            }
            String c = m.getContent();
            rawTotal += c != null ? c.length() : 0;
        }
        if (rawTotal == 0) {
            return LlmToolResultMatrixSealer.SealStats.EMPTY;
        }
        LlmToolResultMatrixSealer.sealCompletedToolBatch(messages, assistantToolCallsIndex, true, log);
        LlmToolResultCohortMerger.apply(messages, assistantToolCallsIndex, log);
        int replayTotal = 0;
        for (int i = assistantToolCallsIndex + 1; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m.getRole() != ChatMessage.Role.TOOL) {
                break;
            }
            String c = m.getContent();
            replayTotal += c != null ? c.length() : 0;
        }
        return LlmToolResultMatrixSealer.statsAfterMatrixAndCohort(rawTotal, replayTotal);
    }
}
