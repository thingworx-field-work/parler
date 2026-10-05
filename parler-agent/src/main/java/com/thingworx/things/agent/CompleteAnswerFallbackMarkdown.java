package com.thingworx.things.agent;

import java.util.Iterator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ChatMessage;

/**
 * LLM-less fallback when the provider returns tool calls on a post-marker no-tool round
 * ({@code docs/agent/llm-performance.md} §7).
 */
public final class CompleteAnswerFallbackMarkdown {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CompleteAnswerFallbackMarkdown() {}

    /**
     * Walks assistant/tool messages from newest to oldest and builds a small Markdown summary from the first
     * parseable {@code tabulate_cached_result} tool JSON that contains a {@code rows} array.
     */
    public static String fromMessages(java.util.List<ChatMessage> messages) {
        if (messages == null) {
            return "# Results\n\n*(No conversation context.)*";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m.getRole() != ChatMessage.Role.TOOL) {
                continue;
            }
            String c = m.getContent();
            if (c == null || !c.contains("\"rows\"")) {
                continue;
            }
            try {
                JsonNode root = MAPPER.readTree(c);
                JsonNode rows = root.path("rows");
                if (!rows.isArray() || rows.isEmpty()) {
                    continue;
                }
                int n = rows.size();
                StringBuilder sb = new StringBuilder();
                sb.append("# Results\n\n");
                sb.append("**").append(n).append("** row").append(n == 1 ? "" : "s").append(".\n\n");
                sb.append("| Row | Summary |\n|---:|---|\n");
                int cap = Math.min(n, 25);
                for (int r = 0; r < cap; r++) {
                    JsonNode row = rows.get(r);
                    sb.append("| ").append(r + 1).append(" | ");
                    sb.append(compactRow(row));
                    sb.append(" |\n");
                }
                if (n > cap) {
                    sb.append("\n*…").append(n - cap).append(" additional rows omitted from this fallback view.*\n");
                }
                return sb.toString();
            } catch (Exception ignored) {
                // try older tool message
            }
        }
        return "# Results\n\n*(Structured tabular data is available in the tool context.)*";
    }

    private static String compactRow(JsonNode row) {
        if (row == null || !row.isObject()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = row.fieldNames();
        int k = 0;
        while (it.hasNext() && k < 6) {
            String name = it.next();
            JsonNode v = row.get(name);
            if (k > 0) {
                sb.append("; ");
            }
            sb.append(name).append('=');
            if (v == null || v.isNull()) {
                sb.append("null");
            } else if (v.isValueNode()) {
                String s = v.asText("");
                if (s.length() > 40) {
                    s = s.substring(0, 37) + "...";
                }
                sb.append(s);
            } else {
                sb.append("(…)");
            }
            k++;
        }
        return sb.toString();
    }
}
