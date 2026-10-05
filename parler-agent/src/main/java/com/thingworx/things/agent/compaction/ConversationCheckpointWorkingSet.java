package com.thingworx.things.agent.compaction;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The "current JVM working checkpoint" §8.4 names: the newest validated {@link ConversationCheckpoint} per
 * <em>owning AgentThing and conversation</em>, held for the life of that conversation's in-memory replay.
 *
 * <p><b>Why this exists.</b> §6.3 defines repeated compaction as summarizing "the previous validated checkpoint
 * semantic + the new complete turns". The injected assistant row is prose written for the model; re-parsing it back
 * into a structured state would invent precision the row does not carry, and it cannot carry the server-authored
 * {@code evidenceRefs} at all. Without this, every compaction after the first would re-summarize from prose and
 * silently drop the prior refs instead of revalidating and carrying them forward.
 *
 * <p><b>Scope is the pair, never the conversation id alone.</b> {@code AgentThing._conversations} is an instance
 * field, so two AgentThings can hold different working conversations under the same id. A key of conversation id
 * alone would let one agent's next summary request consume the other's task state and evidence refs, let it
 * overwrite that state, and let either agent's clear service evict both. The envelope's own
 * {@code (conversationId, agentThing)} source identity is the scope, so it is the key — and a cached entry whose
 * {@code source} disagrees with the key it was found under is refused rather than used.
 *
 * <p><b>Lifetime.</b> Cleared at the same two points that evict a conversation from {@code _conversations}
 * ({@code ClearConversation} and {@code SetConversationHistoryCutoff}). It therefore holds nothing
 * {@code _conversations} does not already hold for the same conversation, and a JVM restart simply loses it — which
 * §8.4 already describes as the fallback state. Slice C's {@code context_checkpoint} Stream row makes this a cache
 * in front of a durable source rather than the only source.
 */
public final class ConversationCheckpointWorkingSet {

    private static final ConcurrentMap<Key, ConversationCheckpoint> BY_OWNER = new ConcurrentHashMap<>();

    private ConversationCheckpointWorkingSet() {}

    /** Immutable {@code (agentThing, conversationId)} identity; a delimited string key could be forged by either. */
    private static final class Key {
        private final String agentThing;
        private final String conversationId;

        private Key(String agentThing, String conversationId) {
            this.agentThing = agentThing;
            this.conversationId = conversationId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key)) {
                return false;
            }
            Key k = (Key) o;
            return agentThing.equals(k.agentThing) && conversationId.equals(k.conversationId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(agentThing, conversationId);
        }
    }

    /** {@code null} when either half of the identity is missing. */
    private static Key keyFor(String agentThingName, String conversationId) {
        if (agentThingName == null || agentThingName.isBlank()
                || conversationId == null || conversationId.isBlank()) {
            return null;
        }
        // Verbatim, for the same reason the Stream key is: _conversations and the per-conversation lock use exact
        // String equality, so " abc " and "abc" are two conversations. Normalizing here would merge their working
        // checkpoints and hand one conversation's task state to the other.
        return new Key(agentThingName, conversationId);
    }

    private static boolean sourceMatches(ConversationCheckpoint checkpoint, Key key) {
        ConversationCheckpoint.Source s = checkpoint.source();
        return s != null
                && s.agentThing().equals(key.agentThing)
                && s.conversationId().equals(key.conversationId);
    }

    /** The newest validated checkpoint for this AgentThing's conversation, or {@code null}. */
    public static ConversationCheckpoint current(String agentThingName, String conversationId) {
        Key key = keyFor(agentThingName, conversationId);
        if (key == null) {
            return null;
        }
        ConversationCheckpoint found = BY_OWNER.get(key);
        // A checkpoint whose own identity disagrees with where it was filed cannot be attributed to this turn.
        return found != null && sourceMatches(found, key) ? found : null;
    }

    /** Records {@code checkpoint} as the working checkpoint, replacing any older one (§5 invariant 2). */
    public static void record(String agentThingName, String conversationId, ConversationCheckpoint checkpoint) {
        Key key = keyFor(agentThingName, conversationId);
        if (key == null || checkpoint == null || !sourceMatches(checkpoint, key)) {
            return;
        }
        BY_OWNER.put(key, checkpoint);
    }

    /** Drops the working checkpoint for a conversation being evicted from one AgentThing's {@code _conversations}. */
    public static void clearConversation(String agentThingName, String conversationId) {
        Key key = keyFor(agentThingName, conversationId);
        if (key != null) {
            BY_OWNER.remove(key);
        }
    }

    /** Test seam: no production path clears every conversation at once. */
    static void clearAllForTests() {
        BY_OWNER.clear();
    }
}
