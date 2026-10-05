package com.thingworx.things.agent;

import org.json.JSONObject;

import com.thingworx.things.Thing;
import com.thingworx.things.agent.llm.ChatMessage;

/**
 * Runs {@link ParlerTableFileExportHook} once per qualifying TOOL message and embeds export metadata under
 * {@link ParlerTableExportSidecar#JSON_KEY} on the JSON persisted to {@link AgentMessageStreamAppender} (in-memory
 * {@link ChatMessage} list for the LLM stays unchanged — callers pass a new {@link ChatMessage} only to the appender).
 */
public final class ParlerToolStreamTableExportSidecar {

    private ParlerToolStreamTableExportSidecar() {}

    /**
     * @param remoteConversation optional (activity line skipped when null); hook still runs when agent is configured
     * @param wireRequestId      used in export path / diagnostics; may be null
     * @param wireConversationId wire conversation id when known; may be null
     * @return {@code m} when no list-class table, export unchanged ({@code exportStatus} still {@code none}), or JSON merge fails
     */
    public static ChatMessage augmentToolMessageForStreamAppend(
            ChatMessage m,
            Thing remoteConversation,
            String wireRequestId,
            String wireConversationId) {
        if (m == null || m.getRole() != ChatMessage.Role.TOOL) {
            return m;
        }
        String body = m.getContent();
        if (body == null || body.isEmpty()) {
            return m;
        }
        JSONObject table = ParlerToolTableWireUtil.tableBlockFromListClassToolJson(body, m.getExecutedToolName());
        if (table == null) {
            return m;
        }
        ParlerTableFileExportHook.apply(remoteConversation, wireRequestId, wireConversationId, table);
        if ("none".equals(table.optString("exportStatus", "none"))) {
            return m;
        }
        try {
            JSONObject root = new JSONObject(body);
            root.put(ParlerTableExportSidecar.JSON_KEY, ParlerTableExportSidecar.sidecarFromTable(table));
            String merged = root.toString();
            return ChatMessage.toolResult(m.getToolCallId(), merged, m.getExecutedToolName());
        } catch (Exception e) {
            return m;
        }
    }
}
