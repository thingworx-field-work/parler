package com.thingworx.things.agent;

/**
 * Per-turn Stream rehydration policy resolved from the entry point ({@code docs/agent/conversation-continuity.md} §9, §14).
 */
public final class ConversationRehydrateSettings {

    /** Default {@link #maxRehydrateMessages} for Parler AlwaysOn paths. */
    public static final int DEFAULT_MAX_REHYDRATE_MESSAGES = 300;
    /** Default {@link #maxRehydrateChars} (UTF-16 code units). */
    public static final int DEFAULT_MAX_REHYDRATE_CHARS = 200_000;

    private final boolean streamRehydrationEnabled;
    private final int maxRehydrateMessages;
    private final int maxRehydrateChars;
    private final boolean rehydrateWarnOnSkippedRows;

    private ConversationRehydrateSettings(boolean streamRehydrationEnabled, int maxRehydrateMessages,
            int maxRehydrateChars, boolean rehydrateWarnOnSkippedRows) {
        this.streamRehydrationEnabled = streamRehydrationEnabled;
        this.maxRehydrateMessages = maxRehydrateMessages;
        this.maxRehydrateChars = maxRehydrateChars;
        this.rehydrateWarnOnSkippedRows = rehydrateWarnOnSkippedRows;
    }

    public static ConversationRehydrateSettings disabled() {
        return new ConversationRehydrateSettings(false, DEFAULT_MAX_REHYDRATE_MESSAGES, DEFAULT_MAX_REHYDRATE_CHARS,
                true);
    }

    /** {@code SubmitUserPrompt} / {@code ParlerStreamToRemoteThing} ({@code docs/agent/conversation-continuity.md}). */
    public static ConversationRehydrateSettings parlerAlwaysOnDefaults() {
        return new ConversationRehydrateSettings(true, DEFAULT_MAX_REHYDRATE_MESSAGES, DEFAULT_MAX_REHYDRATE_CHARS,
                true);
    }

    /** Explicit caps (tests and callers that override defaults). */
    public static ConversationRehydrateSettings custom(boolean streamRehydrationEnabled, int maxRehydrateMessages,
            int maxRehydrateChars, boolean rehydrateWarnOnSkippedRows) {
        return new ConversationRehydrateSettings(streamRehydrationEnabled, maxRehydrateMessages, maxRehydrateChars,
                rehydrateWarnOnSkippedRows);
    }

    public boolean isStreamRehydrationEnabled() {
        return streamRehydrationEnabled;
    }

    public int getMaxRehydrateMessages() {
        return maxRehydrateMessages;
    }

    public int getMaxRehydrateChars() {
        return maxRehydrateChars;
    }

    public boolean isRehydrateWarnOnSkippedRows() {
        return rehydrateWarnOnSkippedRows;
    }
}
