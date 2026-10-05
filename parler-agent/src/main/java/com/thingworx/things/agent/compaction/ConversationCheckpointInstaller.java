package com.thingworx.things.agent.compaction;

import java.util.ArrayList;
import java.util.List;

import com.thingworx.things.agent.llm.ChatMessage;

/**
 * Materializes a validated checkpoint into the live working list (§8.1 steps 6–7, §10.1).
 *
 * <p><b>The published set is the validated set.</b> Installation does not "remove the covered prefix and hope the
 * rest is right": it rebuilds the working list as exactly
 * {@code surviving stable system rows + injected semantic assistant + checkpoint.retainedTail()} — the same three
 * terms {@link ConversationCheckpointGenerator#materializedWorkingSetShrinks} summed when it accepted the
 * checkpoint. Anything less makes that acceptance proof describe a list nobody publishes.
 *
 * <p>Two concrete losses this closes, both invisible if only the covered prefix is removed:
 * <ul>
 *   <li>The generator may summarize a strict <em>oldest sub-prefix</em> when its request cap is tighter than the
 *       plan's covered prefix. It then carries the unselected suffix at the front of {@code retainedTail()}.
 *       Deleting the whole prefix would delete turns that were never summarized.</li>
 *   <li>Even when the whole prefix was summarized, the live tail is not the checkpoint tail: the latter has passed
 *       role/provenance projection and the §6.1 tail caps, and excludes evidence the dry run removed. The storage
 *       trimmer enforces a character budget, not those caps, so the two sets can differ.</li>
 * </ul>
 *
 * <p>Pure list surgery, kept separate from generation so the decision to install and the act of installing are
 * separately testable. The caller owns the list; this mutates it in place, exactly as the other post-turn
 * normalization helpers do.
 */
public final class ConversationCheckpointInstaller {

    private ConversationCheckpointInstaller() {}

    /**
     * Replaces the working list contents with the checkpoint's working set, in place.
     *
     * <p>The covered span is matched by <b>position and reference</b> against {@code plan.coveredPrefix()}, not by
     * value: the covered rows must still be the leading non-{@code SYSTEM} rows of the live list, so a plan computed
     * against a different list is refused rather than mis-applied, and equal-looking rows elsewhere are never
     * mistaken for them.
     *
     * <p><b>Exactly one working checkpoint (§5 invariant 2).</b> The rebuilt list contains one injected row by
     * construction. A prior checkpoint row always sits at the head of the covered prefix, so it is inside the
     * summarized span; a retained tail that nevertheless carried one is refused rather than published, because two
     * checkpoints would be counted twice by the planner and narrated twice to the model.
     *
     * @param messages   the live working list, mutated in place
     * @param plan       the boundary plan the checkpoint was generated from
     * @param checkpoint the validated checkpoint to install
     * @return {@code true} when the replacement was applied; {@code false} leaves {@code messages} untouched
     */
    public static boolean install(List<ChatMessage> messages,
            ConversationCompactionBoundarySelector.BoundaryPlan plan,
            ConversationCheckpoint checkpoint) {
        if (messages == null || plan == null || !plan.isCheckpointEligible() || checkpoint == null
                || checkpoint.semantic() == null) {
            return false;
        }
        ChatMessage injected = ConversationCheckpointCodec.toInjectedAssistant(checkpoint.semantic());
        if (injected == null) {
            return false;
        }
        // Position/reference validation: refuse a plan that no longer describes this list.
        if (!leadsWithCoveredPrefix(messages, plan.coveredPrefix())) {
            return false;
        }

        List<ChatMessage> rebuilt = new ArrayList<>(messages.size());
        // The stable prompt lane, taken from the dry run's survivors so the published set is the validated set.
        // The removal passes only ever drop USER/ASSISTANT pairs and assistant tool-call batches, so this is the
        // same set of system rows the live list holds.
        for (ChatMessage m : plan.materializedSurvivors()) {
            if (m != null && m.getRole() == ChatMessage.Role.SYSTEM) {
                rebuilt.add(m);
            }
        }
        rebuilt.add(injected);
        for (ConversationCheckpoint.RetainedRow r : checkpoint.retainedTail()) {
            ChatMessage row = toWorkingMessage(r);
            if (row == null || ConversationCheckpointCodec.isInjectedCheckpoint(row)) {
                return false;
            }
            rebuilt.add(row);
        }

        messages.clear();
        messages.addAll(rebuilt);
        return true;
    }

    /** A retained row back as a working message; {@code null} for a role the working set cannot carry. */
    static ChatMessage toWorkingMessage(ConversationCheckpoint.RetainedRow r) {
        if (r == null) {
            return null;
        }
        if (ConversationCheckpoint.RetainedRow.ROLE_USER.equals(r.role())) {
            return ChatMessage.user(r.content());
        }
        if (ConversationCheckpoint.RetainedRow.ROLE_ASSISTANT.equals(r.role())) {
            return ChatMessage.assistant(r.content());
        }
        return null;
    }

    /**
     * Whether {@code coveredSpan} is exactly the leading non-{@code SYSTEM} rows of {@code messages}, in order and
     * by reference.
     */
    private static boolean leadsWithCoveredPrefix(List<ChatMessage> messages, List<ChatMessage> coveredSpan) {
        if (coveredSpan == null || coveredSpan.isEmpty()) {
            return false;
        }
        int cursor = 0;
        for (ChatMessage want : coveredSpan) {
            while (cursor < messages.size() && messages.get(cursor) != null
                    && messages.get(cursor).getRole() == ChatMessage.Role.SYSTEM) {
                cursor++;
            }
            if (cursor >= messages.size() || messages.get(cursor) != want) {
                return false;
            }
            cursor++;
        }
        return true;
    }
}
