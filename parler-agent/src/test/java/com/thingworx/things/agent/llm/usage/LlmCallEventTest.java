package com.thingworx.things.agent.llm.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.llm.LlmUsageWireIds;

class LlmCallEventTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void oversizeEnvelope_setsPartialStatusAndJavaCharCount() throws Exception {
        String unicode = "\u4e00".repeat(270_000);
        ObjectNode rawUsage = JSON.createObjectNode();
        rawUsage.put("prompt_tokens", 1);
        rawUsage.put("payload", unicode);
        LlmUsageSnapshot usage = new LlmUsageSnapshot.Builder()
                .status(LlmUsageSnapshot.UsageStatus.COMPLETE)
                .rawUsage(rawUsage)
                .putNormalized("inputTokensTotal", 1L, LlmUsageSnapshot.FieldPresence.REPORTED)
                .build();
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "OpenAI", "openai-chat-completions-v4", "gpt-4o");
        LlmCallContext context = LlmCallRecorder.agentRoundContext("rid", "cid", "AgentThing", 1, ids, null);
        String rawJson = JSON.writeValueAsString(rawUsage);
        String envelope = LlmCallEvent.buildEventJson(
                context,
                LlmCallEventType.CALL_FINISHED,
                2,
                LlmCallDispatchState.ATTEMPTED,
                LlmCallOutcome.SUCCESS,
                null,
                null,
                1L,
                200,
                "req",
                "resp",
                "gpt-4o",
                "stop",
                null,
                null,
                usage,
                "collector");
        ObjectNode parsed = (ObjectNode) JSON.readTree(envelope);
        ObjectNode usageNode = (ObjectNode) parsed.get("usage");
        assertEquals("partial", usageNode.get("status").asText());
        assertEquals(rawJson.length(), usageNode.get("rawUsageChars").asInt());
        assertTrue(usageNode.get("rawUsageSha256").asText().equals(sha256Hex(rawJson)));
        assertTrue(!usageNode.has("rawUsage"));
    }

    private static String sha256Hex(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
