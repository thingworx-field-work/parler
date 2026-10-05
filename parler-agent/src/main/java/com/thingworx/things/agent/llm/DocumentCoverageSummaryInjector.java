package com.thingworx.things.agent.llm;

import java.util.List;

/**
 * Inserts a one-round ephemeral system message that forces a <em>coverage-grounded</em> finalize when the
 * C1 retrieval-saturation guard fires ({@code docs/operations/document-retrieval-convergence.md}). Unlike the
 * plain forced-summary round (which only removes tools), this instructs the model to answer from the evidence
 * already surfaced and to state explicitly which requested symptoms/causes/remedies the manual does NOT cover,
 * instead of continuing to re-search a coverage gap. The message is removed after the round so it never enters
 * stored conversation history.
 */
public final class DocumentCoverageSummaryInjector {

    /** Detect our own injected block when removing after the round. */
    public static final String PREFIX = "Retrieval is saturated: ";

    private DocumentCoverageSummaryInjector() {}

    public static String buildSystemContent() {
        return ParlerSuffixFraming.SERVER_INSTRUCTION + "\n" + PREFIX
                + "you have repeatedly retrieved the same document content and no new evidence is appearing. "
                + "Do NOT search or fetch again. Answer the user's question now using only the document evidence "
                + "already surfaced in this conversation. Your answer MUST:\n"
                + "1. Cite the specific chunks and PDF page links already retrieved (use the sourceLinks/page "
                + "anchors shown in the prior tool results).\n"
                + "2. Directly answer every sub-question that the retrieved evidence DOES cover.\n"
                + "3. Explicitly state which requested symptoms, causes, or remedies the manual does NOT cover, "
                + "rather than implying the answer is incomplete or that more searching is needed.\n"
                + "Be honest about coverage gaps: a clear \"the manual does not list X\" is the correct answer "
                + "when the evidence does not contain it.";
    }

    /**
     * @return index of the inserted guidance row, or {@code -1} when nothing was inserted
     */
    public static int insertForApiRound(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return -1;
        }
        ChatMessage guidance = ChatMessage.system(buildSystemContent());
        int last = messages.size() - 1;
        if (messages.get(last).getRole() == ChatMessage.Role.USER) {
            messages.add(last, guidance);
            return last;
        }
        messages.add(guidance);
        return messages.size() - 1;
    }

    public static void removeAtIndex(List<ChatMessage> messages, int insertedIdx) {
        if (messages == null || insertedIdx < 0 || insertedIdx >= messages.size()) {
            return;
        }
        ChatMessage m = messages.get(insertedIdx);
        if (m.getRole() == ChatMessage.Role.SYSTEM && m.getContent() != null
                && m.getContent().startsWith(ParlerSuffixFraming.SERVER_INSTRUCTION + "\n" + PREFIX)) {
            messages.remove(insertedIdx);
        }
    }
}
