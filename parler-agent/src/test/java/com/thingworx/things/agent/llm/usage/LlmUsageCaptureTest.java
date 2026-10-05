package com.thingworx.things.agent.llm.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.thingworx.things.agent.llm.LlmUsageWireIds;

class LlmUsageCaptureTest {

    private static final LlmUsageWireIds OPENAI_IDS =
            LlmUsageWireIds.forProviderThing("P", "OpenAI", "openai-chat-completions-v4", "gpt-4o");
    private static final LlmUsageWireIds ANTHROPIC_IDS =
            LlmUsageWireIds.forProviderThing("P", "Anthropic", "anthropic-messages-v1", "claude-sonnet-4-20250514");

    @AfterEach
    void cleanup() {
        LlmCallRecorder.resetForTest();
    }

    @Test
    void openAi_completeUsage_normalizesExpectedFields() {
        String body = "{\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":40,\"total_tokens\":160,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":20},"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":5}}}";
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, OPENAI_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.COMPLETE, snapshot.getStatus());
        assertEquals(Long.valueOf(120L), snapshot.getNormalized().get("inputTokensTotal"));
        assertEquals(Long.valueOf(100L), snapshot.getNormalized().get("inputTokensUncached"));
        assertEquals(Long.valueOf(20L), snapshot.getNormalized().get("inputTokensCacheRead"));
        assertEquals(Long.valueOf(40L), snapshot.getNormalized().get("outputTokensTotal"));
        assertEquals(Long.valueOf(5L), snapshot.getNormalized().get("reasoningTokens"));
    }

    @Test
    void anthropic_completeUsage_normalizesExpectedFields() {
        String body = "{\"usage\":{\"input_tokens\":10,\"output_tokens\":20,\"cache_read_input_tokens\":3,"
                + "\"cache_creation_input_tokens\":2,\"cache_creation\":{\"ephemeral_5m_input_tokens\":2,"
                + "\"ephemeral_1h_input_tokens\":0},\"output_tokens_details\":{\"thinking_tokens\":4}}}";
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, ANTHROPIC_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.COMPLETE, snapshot.getStatus());
        assertEquals(Long.valueOf(10L), snapshot.getNormalized().get("inputTokensUncached"));
        assertEquals(Long.valueOf(15L), snapshot.getNormalized().get("inputTokensTotal"));
        assertEquals(Long.valueOf(20L), snapshot.getNormalized().get("outputTokensTotal"));
        assertEquals(Long.valueOf(4L), snapshot.getNormalized().get("reasoningTokens"));
        assertNotNull(snapshot.getRawUsage());
    }

    @Test
    void negativeTokens_markInvalid() {
        String body = "{\"usage\":{\"prompt_tokens\":-1,\"completion_tokens\":10,\"total_tokens\":9}}";
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, OPENAI_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.INVALID, snapshot.getStatus());
    }

    @Test
    void fractionalTokens_markInvalid() {
        String body = "{\"usage\":{\"prompt_tokens\":3.7,\"completion_tokens\":1,\"total_tokens\":4.7}}";
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, OPENAI_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.INVALID, snapshot.getStatus());
    }

    @Test
    void openAi_missingCached_leavesUncachedAbsent() {
        String body = "{\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":40,\"total_tokens\":160}}";
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, OPENAI_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.COMPLETE, snapshot.getStatus());
        assertTrue(snapshot.getNormalized().containsKey("inputTokensTotal"));
        assertFalse(snapshot.getNormalized().containsKey("inputTokensUncached"));
    }

    @Test
    void invalidJson_marksPartialWithoutFabricatingZeros() {
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody("{not-json", OPENAI_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.PARTIAL, snapshot.getStatus());
        assertTrue(snapshot.getNormalized().isEmpty());
    }

    @Test
    void overflowTokens_markInvalid() {
        String body = "{\"usage\":{\"prompt_tokens\":18446744073709551616,\"completion_tokens\":1,\"total_tokens\":1}}";
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, OPENAI_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.INVALID, snapshot.getStatus());
    }

    @Test
    void contradictoryCachedPrompt_markInvalid() {
        String body = "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":20}}}";
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, OPENAI_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.INVALID, snapshot.getStatus());
    }

    @Test
    void anthropic_cacheCreationWithoutTtlBreakdown_marksCompleteNotPartial() {
        String body = "{\"usage\":{\"input_tokens\":10,\"output_tokens\":20,\"cache_creation_input_tokens\":5}}";
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, ANTHROPIC_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.COMPLETE, snapshot.getStatus());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingOrNullUsageCases")
    void missingOrNullUsage_marksUnavailable(String label, String body) {
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, OPENAI_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.UNAVAILABLE, snapshot.getStatus(), label);
        assertTrue(snapshot.getNormalized().isEmpty(), label);
        assertNull(snapshot.getRawUsage(), label);
    }

    static Stream<Arguments> missingOrNullUsageCases() {
        return Stream.of(
                Arguments.of("missing", "{\"choices\":[]}"),
                Arguments.of("null", "{\"usage\":null,\"choices\":[]}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonObjectUsageCases")
    void nonObjectUsage_marksInvalidWithTypeError(String label, String body, String expectedError) {
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, OPENAI_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.INVALID, snapshot.getStatus(), label);
        assertEquals(expectedError, snapshot.getCaptureError(), label);
        assertNotNull(snapshot.getRawUsage(), label);
        assertTrue(snapshot.getNormalized().isEmpty(), label);
    }

    static Stream<Arguments> nonObjectUsageCases() {
        return Stream.of(
                Arguments.of("string", "{\"usage\":\"n/a\"}", "usage_type_string"),
                Arguments.of("number", "{\"usage\":42}", "usage_type_number"),
                Arguments.of("boolean", "{\"usage\":true}", "usage_type_boolean"),
                Arguments.of("array", "{\"usage\":[1,2]}", "usage_type_array"));
    }

    @Test
    void emptyUsageObject_marksPartialWithoutZeros() {
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody("{\"usage\":{}}", OPENAI_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.PARTIAL, snapshot.getStatus());
        assertEquals("usage_incomplete", snapshot.getCaptureError());
        assertNotNull(snapshot.getRawUsage());
        assertTrue(snapshot.getRawUsage().isObject());
        assertTrue(snapshot.getNormalized().isEmpty());
    }

    @Test
    void openAi_missingRequiredCounter_marksPartialAndPreservesKnownValues() {
        String body = "{\"usage\":{\"prompt_tokens\":10}}";
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, OPENAI_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.PARTIAL, snapshot.getStatus());
        assertEquals(Long.valueOf(10L), snapshot.getNormalized().get("inputTokensTotal"));
        assertFalse(snapshot.getNormalized().containsKey("outputTokensTotal"));
        assertEquals(LlmUsageSnapshot.FieldPresence.REPORTED, snapshot.getPresence().get("inputTokensTotal"));
        assertEquals(LlmUsageSnapshot.FieldPresence.ABSENT, snapshot.getPresence().get("outputTokensTotal"));
        assertEquals(LlmUsageSnapshot.FieldPresence.ABSENT, snapshot.getPresence().get("totalTokens"));
    }

    @Test
    void anthropic_missingOutputCounter_marksPartialWithAbsentPresenceForMissingTopLevel() {
        String body = "{\"usage\":{\"input_tokens\":10}}";
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, ANTHROPIC_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.PARTIAL, snapshot.getStatus());
        assertEquals(Long.valueOf(10L), snapshot.getNormalized().get("inputTokensUncached"));
        assertFalse(snapshot.getNormalized().containsKey("outputTokensTotal"));
        assertEquals(LlmUsageSnapshot.FieldPresence.REPORTED, snapshot.getPresence().get("inputTokensUncached"));
        assertEquals(LlmUsageSnapshot.FieldPresence.ABSENT, snapshot.getPresence().get("outputTokensTotal"));
    }

    @Test
    void anthropic_zeroCacheCreateWithContradictoryTtlBreakdown_marksInvalid() {
        String body = "{\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"cache_read_input_tokens\":0,"
                + "\"cache_creation_input_tokens\":0,"
                + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":100,\"ephemeral_1h_input_tokens\":0}}}";
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, ANTHROPIC_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.INVALID, snapshot.getStatus());
        assertEquals("usage_invalid_counter", snapshot.getCaptureError());
        assertEquals(Long.valueOf(100L), snapshot.getNormalized().get("cacheWrite5mTokens"));
        assertEquals(Long.valueOf(0L), snapshot.getNormalized().get("inputTokensCacheWrite"));
    }

    @Test
    void anthropic_matchingCacheCreateTtlBreakdown_marksComplete() {
        String body = "{\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"cache_read_input_tokens\":0,"
                + "\"cache_creation_input_tokens\":100,"
                + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":100,\"ephemeral_1h_input_tokens\":0}}}";
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, ANTHROPIC_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.COMPLETE, snapshot.getStatus());
        assertEquals(Long.valueOf(100L), snapshot.getNormalized().get("inputTokensCacheWrite"));
        assertEquals(Long.valueOf(100L), snapshot.getNormalized().get("cacheWrite5mTokens"));
    }

    @Test
    void anthropic_allZeroCacheFields_marksComplete() {
        String body = "{\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"cache_read_input_tokens\":0,"
                + "\"cache_creation_input_tokens\":0,"
                + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":0,\"ephemeral_1h_input_tokens\":0}}}";
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, ANTHROPIC_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.COMPLETE, snapshot.getStatus());
    }

    @Test
    void openAi_explicitZeroCounters_marksCompleteAndDistinguishesFromAbsent() {
        String body = "{\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}}";
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, OPENAI_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.COMPLETE, snapshot.getStatus());
        assertEquals(Long.valueOf(0L), snapshot.getNormalized().get("inputTokensTotal"));
        assertEquals(Long.valueOf(0L), snapshot.getNormalized().get("outputTokensTotal"));
        assertEquals(LlmUsageSnapshot.FieldPresence.ABSENT, snapshot.getPresence().get("inputTokensCacheRead"));
    }

    @Test
    void openAi_stringNumericCounter_marksInvalidWithoutCoercion() {
        String body = "{\"usage\":{\"prompt_tokens\":\"10\",\"completion_tokens\":5,\"total_tokens\":15}}";
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, OPENAI_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.INVALID, snapshot.getStatus());
        assertEquals("usage_invalid_counter", snapshot.getCaptureError());
    }

    @Test
    void openAi_invalidDetailsContainer_marksInvalid() {
        String body = "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15,"
                + "\"prompt_tokens_details\":\"bad\"}}";
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, OPENAI_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.INVALID, snapshot.getStatus());
        assertEquals("usage_invalid_container", snapshot.getCaptureError());
    }

    @Test
    void openAi_unknownExtensionField_preservesRawAndStaysComplete() {
        String body = "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15,"
                + "\"vendor_extension\":{\"nested\":99}}}";
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, OPENAI_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.COMPLETE, snapshot.getStatus());
        assertTrue(snapshot.getRawUsage().has("vendor_extension"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedOptionalCounterCases")
    void openAi_malformedOptionalKnownCounter_marksInvalid(String label, String usageFragment) {
        String body = "{\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"total_tokens\":120,"
                + usageFragment + "}}";
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, OPENAI_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.INVALID, snapshot.getStatus(), label);
        assertEquals("usage_invalid_counter", snapshot.getCaptureError(), label);
        assertEquals(Long.valueOf(100L), snapshot.getNormalized().get("inputTokensTotal"), label);
    }

    static Stream<Arguments> malformedOptionalCounterCases() {
        return Stream.of(
                Arguments.of("input-audio-negative", "\"prompt_tokens_details\":{\"audio_tokens\":-1}"),
                Arguments.of("output-audio-string", "\"completion_tokens_details\":{\"audio_tokens\":\"bad\"}"),
                Arguments.of("accepted-prediction-negative",
                        "\"completion_tokens_details\":{\"accepted_prediction_tokens\":-1}"),
                Arguments.of("rejected-prediction-fractional",
                        "\"completion_tokens_details\":{\"rejected_prediction_tokens\":0.5}"));
    }

    @Test
    void anthropic_invalidDetailsContainer_marksInvalid() {
        String body = "{\"usage\":{\"input_tokens\":10,\"output_tokens\":5,"
                + "\"output_tokens_details\":[]}}";
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, ANTHROPIC_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.INVALID, snapshot.getStatus());
        assertEquals("usage_invalid_container", snapshot.getCaptureError());
    }
}
