package com.thingworx.things.agent.llm.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class LlmUsageCostCalculatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void priceCall_missingRates_returnsCostUnsupported() {
        LlmUsageReportReducer.CallSummary summary = summary("openai", "openai-chat-completions-v4", "gpt-4o",
                Map.of("inputTokensTotal", 100L, "outputTokensTotal", 20L));
        LlmUsageCostCalculator calculator = new LlmUsageCostCalculator(
                "{\"version\":\"test-v1\",\"prices\":[{\"providerFamily\":\"openai\","
                        + "\"apiShapeId\":\"openai-chat-completions-v4\",\"requestedModel\":\"gpt-4o\"}]}");
        LlmUsageCostCalculator.CostResult result = calculator.priceCall(summary);
        assertEquals("costUnsupported", result.status);
        assertNull(result.knownUsd);
    }

    @Test
    void priceCall_eurCurrencyEntry_returnsUnpriced() {
        LlmUsageReportReducer.CallSummary summary = summary("openai", "openai-chat-completions-v4", "gpt-4o",
                Map.of("inputTokensTotal", 100L, "inputTokensUncached", 100L, "outputTokensTotal", 20L));
        LlmUsageCostCalculator calculator = new LlmUsageCostCalculator(
                "{\"version\":\"test-v1\",\"prices\":[{\"providerFamily\":\"openai\","
                        + "\"requestedModel\":\"gpt-4o\",\"currency\":\"EUR\","
                        + "\"inputPerMillionUsd\":1,\"outputPerMillionUsd\":2}]}");
        LlmUsageCostCalculator.CostResult result = calculator.priceCall(summary);
        assertEquals("unpriced", result.status);
    }

    @Test
    void priceCall_wrongApiShape_returnsUnpriced() {
        LlmUsageReportReducer.CallSummary summary = summary("openai", "openai-chat-completions-v4", "gpt-4o",
                Map.of("inputTokensTotal", 100L, "inputTokensUncached", 100L, "outputTokensTotal", 20L));
        LlmUsageCostCalculator calculator = new LlmUsageCostCalculator(
                "{\"version\":\"test-v1\",\"prices\":[{\"providerFamily\":\"openai\","
                        + "\"apiShapeId\":\"anthropic-messages-v1\",\"requestedModel\":\"gpt-4o\","
                        + "\"inputPerMillionUsd\":1,\"outputPerMillionUsd\":2}]}");
        LlmUsageCostCalculator.CostResult result = calculator.priceCall(summary);
        assertEquals("unpriced", result.status);
    }

    @Test
    void priceCall_positiveAudioTokens_returnsCostUnsupported() throws Exception {
        ObjectNode raw = JSON.createObjectNode();
        raw.put("prompt_tokens", 100);
        raw.put("completion_tokens", 20);
        raw.put("total_tokens", 120);
        ObjectNode promptDetails = raw.putObject("prompt_tokens_details");
        promptDetails.put("cached_tokens", 10);
        promptDetails.put("audio_tokens", 50);
        LlmUsageReportReducer.CallSummary summary = summaryWithRaw(
                "openai", "openai-chat-completions-v4", "gpt-4o",
                Map.of("inputTokensTotal", 100L, "inputTokensUncached", 90L, "inputTokensCacheRead", 10L,
                        "outputTokensTotal", 20L),
                raw);
        LlmUsageCostCalculator calculator = pricedCalculator();
        LlmUsageCostCalculator.CostResult result = calculator.priceCall(summary);
        assertEquals("costUnsupported", result.status);
        assertNull(result.knownUsd);
    }

    @Test
    void priceCall_zeroAudioTokens_returnsKnownCost() throws Exception {
        ObjectNode raw = JSON.createObjectNode();
        raw.put("prompt_tokens", 100);
        raw.put("completion_tokens", 20);
        raw.put("total_tokens", 120);
        ObjectNode promptDetails = raw.putObject("prompt_tokens_details");
        promptDetails.put("cached_tokens", 10);
        promptDetails.put("audio_tokens", 0);
        LlmUsageReportReducer.CallSummary summary = summaryWithRaw(
                "openai", "openai-chat-completions-v4", "gpt-4o",
                Map.of("inputTokensTotal", 100L, "inputTokensUncached", 90L, "inputTokensCacheRead", 10L,
                        "outputTokensTotal", 20L),
                raw);
        LlmUsageCostCalculator calculator = pricedCalculator();
        LlmUsageCostCalculator.CostResult result = calculator.priceCall(summary);
        assertEquals("known", result.status);
    }

    @Test
    void priceCall_positiveIterationsArray_returnsCostUnsupported() throws Exception {
        ObjectNode raw = JSON.createObjectNode();
        raw.put("prompt_tokens", 100);
        raw.put("completion_tokens", 20);
        raw.put("total_tokens", 120);
        raw.putArray("iterations").add(25);
        LlmUsageReportReducer.CallSummary summary = summaryWithRaw(
                "openai", "openai-chat-completions-v4", "gpt-4o",
                Map.of("inputTokensTotal", 100L, "inputTokensUncached", 100L, "outputTokensTotal", 20L),
                raw);
        LlmUsageCostCalculator calculator = pricedCalculator();
        LlmUsageCostCalculator.CostResult result = calculator.priceCall(summary);
        assertEquals("costUnsupported", result.status);
        assertNull(result.knownUsd);
    }

    @Test
    void priceCall_positiveLatencyCheckpoint_returnsKnownCostWithMatchingRates() throws Exception {
        LlmUsageSnapshot snapshot = AzureLatencyUsageFixtures.capture(26019, 121, 26140);
        LlmUsageCostCalculator calculator = new LlmUsageCostCalculator(AzureLatencyUsageFixtures.azurePriceTableJson());
        LlmUsageCostCalculator.CostResult result =
                calculator.priceCall(AzureLatencyUsageFixtures.callSummary(snapshot));
        assertEquals("known", result.status);
        assertEquals(0, new java.math.BigDecimal("0.026261").compareTo(result.knownUsd));
    }

    @Test
    void priceCall_positiveLatencyCheckpoint_emptyPriceTable_returnsUnpriced() throws Exception {
        LlmUsageSnapshot snapshot = AzureLatencyUsageFixtures.capture(1198, 1050, 2248);
        LlmUsageCostCalculator calculator = new LlmUsageCostCalculator(
                "{\"version\":\"unpriced-v1\",\"prices\":[]}");
        LlmUsageCostCalculator.CostResult result =
                calculator.priceCall(AzureLatencyUsageFixtures.callSummary(snapshot));
        assertEquals("unpriced", result.status);
        assertNull(result.knownUsd);
    }

    @Test
    void priceCall_positiveUnknownBillingLeafStillCostUnsupported() throws Exception {
        ObjectNode raw = JSON.createObjectNode();
        raw.put("prompt_tokens", 100);
        raw.put("completion_tokens", 20);
        raw.put("total_tokens", 120);
        raw.put("vendor_billing_tokens", 5);
        LlmUsageReportReducer.CallSummary summary = summaryWithRaw(
                "openai", "openai-chat-completions-v4", "gpt-4o",
                Map.of("inputTokensTotal", 100L, "inputTokensUncached", 100L, "outputTokensTotal", 20L),
                raw);
        LlmUsageCostCalculator calculator = pricedCalculator();
        LlmUsageCostCalculator.CostResult result = calculator.priceCall(summary);
        assertEquals("costUnsupported", result.status);
    }

    @Test
    void priceCall_positiveMsSuffixOutsideLatencyStillCostUnsupported() throws Exception {
        ObjectNode raw = JSON.createObjectNode();
        raw.put("prompt_tokens", 100);
        raw.put("completion_tokens", 20);
        raw.put("total_tokens", 120);
        raw.put("custom_ms", 10);
        LlmUsageReportReducer.CallSummary summary = summaryWithRaw(
                "openai", "openai-chat-completions-v4", "gpt-4o",
                Map.of("inputTokensTotal", 100L, "inputTokensUncached", 100L, "outputTokensTotal", 20L),
                raw);
        LlmUsageCostCalculator calculator = pricedCalculator();
        LlmUsageCostCalculator.CostResult result = calculator.priceCall(summary);
        assertEquals("costUnsupported", result.status);
    }

    @Test
    void priceCall_invalidUsageStatus_returnsUnpriced() {
        LlmUsageReportReducer.CallSummary summary = summary("openai", "openai-chat-completions-v4", "gpt-4o",
                Map.of("inputTokensTotal", 100L, "outputTokensTotal", 20L));
        summary = new LlmUsageReportReducer.CallSummary(
                summary.callId,
                summary.logicalCallId,
                summary.conversationId,
                summary.agentThing,
                summary.requestedModel,
                summary.responseModel,
                summary.providerFamily,
                summary.providerThingName,
                summary.apiShapeId,
                summary.callKind,
                summary.callStartedAt,
                summary.endedAt,
                summary.cancelObservedAt,
                summary.outcome,
                summary.dispatchState,
                summary.started,
                summary.finished,
                summary.dispatched,
                summary.conflict,
                LlmUsageSnapshot.UsageStatus.INVALID,
                summary.normalized,
                summary.presence,
                summary.rawUsage);
        LlmUsageCostCalculator calculator = pricedCalculator();
        LlmUsageCostCalculator.CostResult result = calculator.priceCall(summary);
        assertEquals("unpriced", result.status);
        assertNull(result.knownUsd);
    }

    private static LlmUsageCostCalculator pricedCalculator() {
        return new LlmUsageCostCalculator(
                "{\"version\":\"test-v1\",\"prices\":[{\"providerFamily\":\"openai\","
                        + "\"apiShapeId\":\"openai-chat-completions-v4\",\"requestedModel\":\"gpt-4o\","
                        + "\"inputPerMillionUsd\":1,\"outputPerMillionUsd\":2,\"cacheReadPerMillionUsd\":0.5}]}");
    }

    private static ObjectNode latencyUsage(long prompt, long completion, long total) {
        ObjectNode raw = JSON.createObjectNode();
        raw.put("prompt_tokens", prompt);
        raw.put("completion_tokens", completion);
        raw.put("total_tokens", total);
        raw.putObject("prompt_tokens_details").put("cached_tokens", 0);
        ObjectNode latency = raw.putObject("latency_checkpoint");
        latency.put("engine_tbt_ms", 10);
        latency.put("engine_ttft_ms", 584);
        latency.put("engine_ttlt_ms", 1746);
        latency.put("pre_inference_ms", 451);
        latency.put("service_tbt_ms", 10);
        latency.put("service_ttft_ms", 1253);
        latency.put("service_ttlt_ms", 2411);
        latency.put("user_visible_ttft_ms", 802);
        return raw;
    }

    private static LlmUsageReportReducer.CallSummary summaryWithRaw(
            String providerFamily,
            String apiShapeId,
            String requestedModel,
            Map<String, Long> normalized,
            ObjectNode rawUsage) {
        return new LlmUsageReportReducer.CallSummary(
                "call-1",
                "logical-1",
                "cid",
                "AgentThing",
                requestedModel,
                null,
                providerFamily,
                "ProviderThing",
                apiShapeId,
                LlmCallKind.AGENT_ROUND,
                Instant.parse("2026-09-12T10:00:00Z"),
                Instant.parse("2026-09-12T10:00:01Z"),
                null,
                LlmCallOutcome.SUCCESS,
                LlmCallDispatchState.ATTEMPTED,
                true,
                true,
                true,
                false,
                LlmUsageSnapshot.UsageStatus.COMPLETE,
                normalized,
                Map.of(),
                rawUsage);
    }

    private static LlmUsageReportReducer.CallSummary summary(
            String providerFamily,
            String apiShapeId,
            String requestedModel,
            Map<String, Long> normalized) {
        return new LlmUsageReportReducer.CallSummary(
                "call-1",
                "logical-1",
                "cid",
                "AgentThing",
                requestedModel,
                null,
                providerFamily,
                "ProviderThing",
                apiShapeId,
                LlmCallKind.AGENT_ROUND,
                Instant.parse("2026-09-12T10:00:00Z"),
                Instant.parse("2026-09-12T10:00:01Z"),
                null,
                LlmCallOutcome.SUCCESS,
                LlmCallDispatchState.ATTEMPTED,
                true,
                true,
                true,
                false,
                LlmUsageSnapshot.UsageStatus.COMPLETE,
                normalized,
                Map.of(),
                null);
    }
}
