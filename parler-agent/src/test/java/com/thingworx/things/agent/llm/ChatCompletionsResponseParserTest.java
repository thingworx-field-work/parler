package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class ChatCompletionsResponseParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void parsesCachedPromptTokensWhenPresent() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"x\"},"
                + "\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":5000,\"completion_tokens\":10,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":4000}}}";
        LlmResponse r = ChatCompletionsResponseParser.parse(body, "req-a");
        assertEquals(5000, r.getPromptTokens());
        assertEquals(5000, r.getInputTokens());
        assertEquals(4000, r.getCachedPromptTokens());
        assertEquals(10, r.getCompletionTokens());
        assertEquals("req-a", r.getProviderRequestId());
    }

    @Test
    void toleratesMissingPromptTokensDetails() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"x\"},"
                + "\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}";
        LlmResponse r = ChatCompletionsResponseParser.parse(body, null);
        assertEquals(0, r.getCachedPromptTokens());
    }

    @Test
    void throwsWhenNoChoices() {
        String body = "{\"choices\":[]}";
        assertThrows(RuntimeException.class, () -> ChatCompletionsResponseParser.parse(body, null));
    }

    @Test
    void parsesReasoningTokensWhenPresent_withoutSubtractingCompletion() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\"},"
                + "\"finish_reason\":\"length\"}],"
                + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":2048,"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":2048}}}";
        LlmResponse r = ChatCompletionsResponseParser.parse(body, "req-reason");
        assertEquals(2048, r.getCompletionTokens());
        assertEquals(2048, r.getOutputTokens());
        assertEquals(2048, r.getReasoningTokens());
    }

    @Test
    void toleratesMissingCompletionTokensDetails() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"x\"},"
                + "\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}";
        LlmResponse r = ChatCompletionsResponseParser.parse(body, null);
        assertEquals(1, r.getCompletionTokens());
        assertEquals(0, r.getReasoningTokens());
    }

    @Test
    void parseRoot_direct() throws Exception {
        JsonNode root = JSON.readTree("{\"choices\":[{\"message\":{\"content\":\"z\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2}}");
        LlmResponse r = ChatCompletionsResponseParser.parseRoot(root, null);
        assertEquals(1, r.getPromptTokens());
        assertEquals(2, r.getCompletionTokens());
    }
}
