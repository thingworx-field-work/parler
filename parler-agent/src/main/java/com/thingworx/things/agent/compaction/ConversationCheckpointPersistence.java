package com.thingworx.things.agent.compaction;

import com.thingworx.things.agent.AgentMessageStreamAppender;

/**
 * §10.2 step 6: the seam between checkpoint creation and the {@code context_checkpoint} Stream row.
 *
 * <p>Exists so the post-turn entry can be exercised on both sides of a persistence failure. The row append needs a
 * live ThingWorx Stream Thing, which no unit test has, so without this seam every test would see §8.4's failure
 * branch and the success path would be unreachable — the reverse of what the tests are for. Follows
 * {@link LlmReplayCompactionGate}'s {@link ThreadLocal} override style so parallel test runs cannot interfere.
 */
public final class ConversationCheckpointPersistence {

    /** The one operation §10.2 step 6 performs. */
    public interface RowAppender {
        boolean append(String streamThreadKey, String streamEntrySource, String agentThingName,
                String checkpointEnvelopeJson);
    }

    private static final ThreadLocal<RowAppender> TEST_APPENDER_OVERRIDE = new ThreadLocal<>();

    private ConversationCheckpointPersistence() {}

    /**
     * Appends the checkpoint row.
     *
     * <p><b>The Stream key is derived, not passed.</b> All eight post-turn call sites compute both the key and the
     * source as {@code streamConversationKey(conversationId)}, which returns the conversation id itself whenever it
     * is non-empty — and a checkpoint only exists when it is, since the boundary selector refuses a blank id with
     * {@code NO_CONVERSATION_ID} and §9.3 excludes adhoc single-turn sources. Deriving it here is what makes §10.2's
     * "the synchronous, asynchronous, AlwaysOn and approval-continuation paths must not each write their own policy" structural instead of a rule eight callers must
     * each remember.
     *
     * <p><b>The id is used verbatim.</b> {@code Chat} and {@code ChatAsync} gate on {@code isEmpty()}, validate the
     * supplied id against {@code AgentThreadDataTable} as an exact key, and pass it on unchanged — so a thread id
     * carrying leading or trailing whitespace reaches the Stream verbatim, and {@code _conversations} and the
     * per-conversation lock key on it verbatim too. Normalizing it here would file the checkpoint under a different
     * Stream source than the final-assistant watermark row it must be validated against, and restart lookup by the
     * conversation's exact source would never find it. Identity preservation applies to every accepted key.
     *
     * @return {@code false} when nothing was persisted; the caller keeps the JVM working checkpoint either way (§8.4)
     */
    public static boolean append(String conversationId, String agentThingName, String checkpointEnvelopeJson) {
        if (conversationId == null || conversationId.isBlank() || checkpointEnvelopeJson == null) {
            return false;
        }
        String key = conversationId;
        RowAppender override = TEST_APPENDER_OVERRIDE.get();
        if (override != null) {
            return override.append(key, key, agentThingName, checkpointEnvelopeJson);
        }
        return AgentMessageStreamAppender.appendContextCheckpoint(key, key, agentThingName, checkpointEnvelopeJson);
    }

    /** Test-only: route appends to {@code appenderOrNull}; {@code null} restores the Stream appender. */
    public static void setAppenderForTest(RowAppender appenderOrNull) {
        TEST_APPENDER_OVERRIDE.set(appenderOrNull);
    }

    /** Clears all test hooks (call from {@code @AfterEach} in tests that use this seam). */
    public static void clearAllTestHooks() {
        TEST_APPENDER_OVERRIDE.set(null);
    }
}
