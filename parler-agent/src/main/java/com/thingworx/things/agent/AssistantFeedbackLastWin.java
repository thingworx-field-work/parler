package com.thingworx.things.agent;

import java.util.HashMap;
import java.util.Map;

import org.json.JSONObject;

/**
 * Last-win thumbs aggregation from chronological {@code ui_feedback} stream rows (JSON {@code type=assistant_feedback}).
 * Pure Java / no ThingWorx row types — used by {@link AgentMessageStreamHistoryExporter} and unit-tested without a
 * platform fixture.
 */
public final class AssistantFeedbackLastWin {

    /** Minimal row view: {@code role} + {@code content} (JSON body for {@code ui_feedback}). */
    public static final class RoleContentRow {
        private final String role;
        private final String content;

        public RoleContentRow(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String role() {
            return role;
        }

        public String content() {
            return content;
        }
    }

    private AssistantFeedbackLastWin() {}

    /**
     * Walks rows in chronological order (oldest → newest); later entries win for the same {@code assistantMessageId}.
     */
    public static Map<String, String> lastWinRatingsChronological(Iterable<RoleContentRow> chronologicalRows) {
        Map<String, String> m = new HashMap<>();
        if (chronologicalRows == null) {
            return m;
        }
        for (RoleContentRow dataRow : chronologicalRows) {
            if (dataRow == null) {
                continue;
            }
            String role = dataRow.role() != null ? dataRow.role().trim().toLowerCase() : "";
            if (!"ui_feedback".equals(role)) {
                continue;
            }
            String body = dataRow.content() != null ? dataRow.content() : "";
            if (body.isEmpty()) {
                continue;
            }
            try {
                JSONObject o = new JSONObject(body);
                if (!"assistant_feedback".equals(o.optString("type", "").trim())) {
                    continue;
                }
                String aid = o.optString("assistantMessageId", "").trim();
                String rating = o.optString("rating", "").trim().toLowerCase();
                if (!aid.isEmpty() && ("up".equals(rating) || "down".equals(rating))) {
                    m.put(aid, rating);
                }
            } catch (Exception ignored) {
                // malformed JSON — skip
            }
        }
        return m;
    }
}
