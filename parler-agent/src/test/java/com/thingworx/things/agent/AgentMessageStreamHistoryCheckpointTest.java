package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * §9.1: the UI history exporter must skip {@code context_checkpoint} explicitly before turn segmentation, and no
 * Stream-based usage consumer may treat one as a billable assistant round.
 */
class AgentMessageStreamHistoryCheckpointTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENVELOPE =
            "{\"$format\":\"parler.conversation_checkpoint.v1\",\"semantic\":{\"goal\":\"compare pump throughput\"}}";

    private static ValueCollection row(String role, String content, String assistantMessageId, String usageJson) {
        ValueCollection vc = new ValueCollection();
        vc.put("role", new StringPrimitive(role));
        vc.put("content", new StringPrimitive(content));
        vc.put("assistantMessageId", new StringPrimitive(assistantMessageId));
        vc.put("llmUsageJson", new StringPrimitive(usageJson));
        return vc;
    }

    private static ValueCollection checkpointRow() {
        return row(AgentMessageStreamAppender.ROLE_CONTEXT_CHECKPOINT, ENVELOPE, "", "");
    }

    private static List<String> kinds(JsonNode root) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : root.get("rows")) {
            out.add(r.path("kind").asText());
        }
        return out;
    }

    @Test
    void aCheckpointAfterTheFinalAssistantProducesNoExtraTurn() throws Exception {
        JsonNode root = JSON.readTree(AgentMessageStreamHistoryExporter.buildHistoryJson(List.of(
                row("user", "first question", "", ""),
                row("assistant", "first answer", "amid-1", ""),
                checkpointRow())));

        assertEquals(List.of("user", "assistant"), kinds(root),
                "the checkpoint row is skipped, not appended as a turn tail");
        assertEquals("first answer", root.get("rows").get(1).get("markdown").asText());
    }

    @Test
    void aWindowBeginningWithACheckpointProducesNoUserlessSegment() throws Exception {
        // Turn segmentation emits a segment for a non-empty pending tail even with no user row, so an unknown-role
        // fallthrough at the head of the window would invent a leading assistant turn out of an internal row.
        JsonNode root = JSON.readTree(AgentMessageStreamHistoryExporter.buildHistoryJson(List.of(
                checkpointRow(),
                row("user", "question after the checkpoint", "", ""),
                row("assistant", "answer after the checkpoint", "amid-2", ""))));

        assertEquals(List.of("user", "assistant"), kinds(root), root.toString());
        assertEquals("question after the checkpoint", root.get("rows").get(0).get("text").asText());
    }

    @Test
    void theEnvelopeNeverReachesTheUiWire() throws Exception {
        String json = AgentMessageStreamHistoryExporter.buildHistoryJson(List.of(
                row("user", "first question", "", ""),
                row("assistant", "first answer", "amid-1", ""),
                checkpointRow()));

        assertFalse(json.contains("parler.conversation_checkpoint.v1"), json);
        assertFalse(json.contains("compare pump throughput"),
                "checkpoint semantic text is not user-visible history");
    }

    @Test
    void aCheckpointDoesNotBecomeTheFinalAssistantUsageOrIdentity() throws Exception {
        // §9.1: an empty llmUsageJson with zero token columns must not create a round, become the
        // final-assistant usage, or enter totals. The row's own columns are already zeroed; this locks the
        // consumer-side gate that keeps it from being selected at all.
        JsonNode root = JSON.readTree(AgentMessageStreamHistoryExporter.buildHistoryJson(List.of(
                row("user", "first question", "", ""),
                row("assistant", "first answer", "amid-1",
                        "{\"promptTokens\":11,\"completionTokens\":7}"),
                checkpointRow())));

        JsonNode assistant = root.get("rows").get(1);
        assertEquals("amid-1", assistant.get("assistantMessageId").asText(),
                "identity still comes from the final assistant row, not the newer internal row");
        assertTrue(assistant.has("llmUsage"), root.toString());
        assertEquals(11, assistant.get("llmUsage").get("promptTokens").asInt(),
                "the assistant's own usage survives a trailing checkpoint");
    }

    @Test
    void aWindowContainingOnlyACheckpointExportsNothing() throws Exception {
        // A bounded newest-N window can land on internal rows alone. Falling through would emit an assistant turn
        // with empty markdown — a turn the conversation never had.
        JsonNode root = JSON.readTree(
                AgentMessageStreamHistoryExporter.buildHistoryJson(List.of(checkpointRow())));

        assertEquals(List.of(), kinds(root), root.toString());
    }

    @Test
    void aConversationWithoutCheckpointsIsExportedExactlyAsBefore() throws Exception {
        JsonNode root = JSON.readTree(AgentMessageStreamHistoryExporter.buildHistoryJson(List.of(
                row("user", "first question", "", ""),
                row("assistant", "first answer", "amid-1", ""),
                row("user", "second question", "", ""),
                row("assistant", "second answer", "amid-2", ""))));

        assertEquals(List.of("user", "assistant", "user", "assistant"), kinds(root));
    }
}
