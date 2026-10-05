package com.thingworx.things.agent;

import java.util.List;

/**
 * Finds the {@code agentThing} field on the newest matching final-assistant stream row for a stable
 * {@code assistantMessageId}, without requiring that {@code agentThing} match the invoking Agent Thing (direct-service
 * policy for direct {@link AgentThing} feedback services).
 */
public final class AssistantStreamFinalAssistantLookup {

    /** Minimal stream row projection for tests and {@link AgentThing#RecordAssistantFeedback}. */
    public static final class AssistantStreamRow {
        private final String role;
        private final String assistantMessageId;
        private final String agentThing;

        public AssistantStreamRow(String role, String assistantMessageId, String agentThing) {
            this.role = role;
            this.assistantMessageId = assistantMessageId;
            this.agentThing = agentThing;
        }

        public String role() {
            return role;
        }

        public String assistantMessageId() {
            return assistantMessageId;
        }

        public String agentThing() {
            return agentThing;
        }
    }

    private AssistantStreamFinalAssistantLookup() {}

    /**
     * Scans from the end of {@code chronologicalRowsOldestFirst} (newest last in list) for the first {@code assistant}
     * row whose {@code assistantMessageId} equals {@code assistantMessageId}.
     *
     * @return trimmed {@code agentThing} from the matched row (may be empty string), or {@code null} if not found
     */
    public static String findAgentThingForAssistantMessageId(List<AssistantStreamRow> chronologicalRowsOldestFirst,
            String assistantMessageId) {
        if (chronologicalRowsOldestFirst == null || assistantMessageId == null) {
            return null;
        }
        String aid = assistantMessageId.trim();
        if (aid.isEmpty()) {
            return null;
        }
        for (int i = chronologicalRowsOldestFirst.size() - 1; i >= 0; i--) {
            AssistantStreamRow r = chronologicalRowsOldestFirst.get(i);
            if (r == null) {
                continue;
            }
            String role = r.role() != null ? r.role().trim() : "";
            if (!"assistant".equalsIgnoreCase(role)) {
                continue;
            }
            String rowId = r.assistantMessageId() != null ? r.assistantMessageId().trim() : "";
            if (!aid.equals(rowId)) {
                continue;
            }
            return r.agentThing() != null ? r.agentThing().trim() : "";
        }
        return null;
    }
}
