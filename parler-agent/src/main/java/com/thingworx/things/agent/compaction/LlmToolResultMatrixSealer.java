package com.thingworx.things.agent.compaction;

import java.util.List;

import org.slf4j.Logger;

import com.thingworx.things.agent.llm.ChatMessage;

/**
 * Round-boundary Tier A matrix sealing for tool-result bodies (Phase 2 v1).
 * Runs only after a completed assistant tool batch; mutates {@link ChatMessage} entries in place via
 * {@link List#set} (tool messages are immutable; replacement rows carry the same {@code toolCallId}).
 */
public final class LlmToolResultMatrixSealer {

    private LlmToolResultMatrixSealer() {}

    /**
     * @param assistantToolCallsIndex index of the {@link ChatMessage.Role#ASSISTANT} message with tool_calls
     */
    public static SealStats sealCompletedToolBatch(List<ChatMessage> messages, int assistantToolCallsIndex,
            boolean enabled, Logger log) {
        if (!enabled || messages == null || assistantToolCallsIndex < 0
                || assistantToolCallsIndex >= messages.size()) {
            return SealStats.EMPTY;
        }
        ChatMessage asst = messages.get(assistantToolCallsIndex);
        if (asst.getRole() != ChatMessage.Role.ASSISTANT || !asst.hasToolCalls()) {
            return SealStats.EMPTY;
        }
        int rawChars = 0;
        int replayChars = 0;
        int rewritten = 0;
        for (int i = assistantToolCallsIndex + 1; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m.getRole() != ChatMessage.Role.TOOL) {
                break;
            }
            String content = m.getContent() != null ? m.getContent() : "";
            rawChars += content.length();
            String encoded = InfoTableMatrixCodec.encodeIfEligible(content, log);
            replayChars += encoded.length();
            if (!encoded.equals(content)) {
                rewritten++;
                messages.set(i, ChatMessage.toolResult(m.getToolCallId(), encoded));
            }
        }
        if (rawChars == 0) {
            return SealStats.EMPTY;
        }
        return new SealStats(rawChars, replayChars, replayChars / (double) rawChars, rewritten);
    }

    /**
     * Stats after matrix + cohort passes (raw = tool body chars before any compaction; replay = chars after both).
     */
    public static SealStats statsAfterMatrixAndCohort(int rawTotal, int replayTotal) {
        if (rawTotal <= 0) {
            return SealStats.EMPTY;
        }
        return new SealStats(rawTotal, replayTotal, replayTotal / (double) rawTotal, 0);
    }

    /** Character stats for the tool-result bodies in the sealed batch (for {@code LLM_USAGE}). */
    public static final class SealStats {
        public static final SealStats EMPTY = new SealStats(0, 0, null, 0);

        private final int rawReplayChars;
        private final int replayChars;
        private final Double compactRatio;
        private final int toolBodiesRewritten;

        private SealStats(int rawReplayChars, int replayChars, Double compactRatio, int toolBodiesRewritten) {
            this.rawReplayChars = rawReplayChars;
            this.replayChars = replayChars;
            this.compactRatio = compactRatio;
            this.toolBodiesRewritten = toolBodiesRewritten;
        }

        public int getRawReplayChars() {
            return rawReplayChars;
        }

        public int getReplayChars() {
            return replayChars;
        }

        /** {@code null} when nothing was measured. */
        public Double getCompactRatio() {
            return compactRatio;
        }

        public int getToolBodiesRewritten() {
            return toolBodiesRewritten;
        }

        public boolean isEmpty() {
            return rawReplayChars <= 0;
        }
    }
}
