package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import com.thingworx.things.agent.compaction.ConversationCheckpointCodec;

/**
 * §9.1: the {@code context_checkpoint} Stream row shape, and the one behaviour that must NOT be inherited from the
 * {@link ChatMessage}-shaped appenders — silent truncation of the envelope.
 */
class AgentMessageStreamCheckpointAppendTest {

    private static final String AGENT = "MyAgentThing";
    private static final String ENVELOPE = "{\"$format\":\"parler.conversation_checkpoint.v1\"}";

    private static Map<String, Object> row(boolean currentShape) {
        return AgentMessageStreamAppender.contextCheckpointRowFields(AGENT, ENVELOPE, currentShape, currentShape);
    }

    // --- the §9.1 row -----------------------------------------------------------------------------------

    @Test
    void theRowCarriesTheLiteralRoleAndTheEnvelopeVerbatim() {
        Map<String, Object> vc = row(true);

        assertEquals("context_checkpoint", vc.get("role"));
        assertEquals(AgentMessageStreamAppender.ROLE_CONTEXT_CHECKPOINT, vc.get("role"));
        assertEquals(ENVELOPE, vc.get("content"), "the envelope is persisted exactly as validated");
        assertEquals(AGENT, vc.get("agentThing"), "§9.3 agent ownership is decided from this cell");
    }

    @Test
    void everyUsageColumnIsZeroedSoNoConsumerCountsABillableRound() {
        // §9.1: an empty llmUsageJson with zero legacy token columns must not create an assistant round, become the
        // final-assistant usage, or enter totals. The row-level half of that guarantee is written here.
        Map<String, Object> vc = row(true);

        assertEquals(Integer.valueOf(0), vc.get("promptTokens"));
        assertEquals(Integer.valueOf(0), vc.get("completionTokens"));
        assertEquals("", vc.get("llmUsageJson"));
        assertEquals("", vc.get("assistantMessageId"));
        assertEquals("", vc.get("toolCallId"));
        assertEquals("", vc.get("toolCalls"));
        assertEquals("", vc.get(AgentMessageStreamAppender.FIELD_EXECUTED_TOOL_NAME));
    }

    @Test
    void anOlderDeployedShapeStillAcceptsTheRow() {
        // The shapeHasField guard is why this is a compatible addition rather than a shape migration.
        Map<String, Object> vc = row(false);

        assertEquals("context_checkpoint", vc.get("role"));
        assertFalse(vc.containsKey(AgentMessageStreamAppender.FIELD_EXECUTED_TOOL_NAME),
                "a column the deployed shape does not have must not be written");
        assertFalse(vc.containsKey(AgentMessageStreamAppender.FIELD_HOST_CONTEXT_SNAPSHOT_JSON));
        assertEquals(List.of("agentThing", "role", "content", "toolCallId", "toolCalls", "promptTokens",
                        "completionTokens", "llmUsageJson", "assistantMessageId"),
                List.copyOf(vc.keySet()),
                "the mandatory columns are all present and in row order");
    }

    // --- the cap is re-asserted, never truncated --------------------------------------------------------

    @Test
    void anOverCapEnvelopeIsRefusedRatherThanTruncated() {
        // The whole reason for a dedicated appender: toValueRow and appendUiFeedback truncate at 500000 chars, which
        // for prose is lossy and for checkpoint JSON is fatal — the row would parse as nothing on every future
        // rehydrate while looking like continuity. Refusing is the only outcome that does not persist a lie.
        String overCap = "{\"$format\":\"parler.conversation_checkpoint.v1\",\"pad\":\""
                + "x".repeat(ConversationCheckpointCodec.MAX_ENVELOPE_CHARS) + "\"}";
        assertTrue(overCap.length() > ConversationCheckpointCodec.MAX_ENVELOPE_CHARS,
                "fixture precondition: the envelope really is over cap");
        assertTrue(overCap.length() < 500_000,
                "fixture precondition: it is still UNDER the persistence cap, so only the re-assert can refuse it");

        assertFalse(AgentMessageStreamAppender.appendContextCheckpoint("thread-1", "src", AGENT, overCap));
    }

    @Test
    void missingArgumentsAppendNothing() {
        assertFalse(AgentMessageStreamAppender.appendContextCheckpoint(null, "src", AGENT, ENVELOPE));
        assertFalse(AgentMessageStreamAppender.appendContextCheckpoint("", "src", AGENT, ENVELOPE));
        assertFalse(AgentMessageStreamAppender.appendContextCheckpoint("thread-1", "src", AGENT, null));
        assertFalse(AgentMessageStreamAppender.appendContextCheckpoint("thread-1", "src", AGENT, ""));
    }


    // --- the shipped shape description -------------------------------------------------------------------

    @Test
    void theShippedRoleDescriptionEnumeratesBothInternalRoles() throws Exception {
        // §13 couples this to the same slice, and the shipped string also predates ui_feedback — so the corrected
        // enumeration lists both rather than fixing one known-stale description by writing another.
        Path p = Paths.get("Entities/DataShapes/AgentMessageData.xml");
        if (!Files.exists(p)) {
            p = Paths.get("parler-agent/Entities/DataShapes/AgentMessageData.xml");
        }
        assertTrue(Files.exists(p), "fixture precondition: the DataShape entity is readable");
        String xml = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        int roleAt = xml.indexOf("name=\"role\"");
        assertTrue(roleAt > 0);
        String roleField = xml.substring(roleAt, xml.indexOf("/>", roleAt));
        assertTrue(roleField.contains("context_checkpoint"), roleField);
        assertTrue(roleField.contains("ui_feedback"), roleField);
    }
}
