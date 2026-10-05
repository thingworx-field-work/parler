package com.thingworx.things.agent.llm.usage;

import java.time.Instant;

import com.thingworx.things.agent.llm.LlmUsageWireIds;

/**
 * Live-verified Azure usage fixtures: both reconciliation calls include {@code prompt_tokens_details.cached_tokens: 0}.
 */
public final class AzureLatencyUsageFixtures {

    public static final LlmUsageWireIds AZURE_IDS = LlmUsageWireIds.forProviderThing(
            "P", "AzureOpenAI54", "azure-openai-chat-completions-v5", "gpt-5.4");

    private AzureLatencyUsageFixtures() {}

    public static String responseBody(long prompt, long completion, long total) {
        return "{\"usage\":{\"prompt_tokens\":" + prompt
                + ",\"completion_tokens\":" + completion
                + ",\"total_tokens\":" + total
                + ",\"prompt_tokens_details\":{\"cached_tokens\":0}"
                + ",\"latency_checkpoint\":{\"engine_tbt_ms\":10,\"engine_ttft_ms\":584,"
                + "\"engine_ttlt_ms\":1746,\"pre_inference_ms\":451,\"service_tbt_ms\":10,"
                + "\"service_ttft_ms\":1253,\"service_ttlt_ms\":2411,\"user_visible_ttft_ms\":802}}}";
    }

    public static LlmUsageSnapshot capture(long prompt, long completion, long total) {
        return LlmUsageCapture.captureFromResponseBody(responseBody(prompt, completion, total), AZURE_IDS);
    }

    public static LlmUsageReportReducer.CallSummary callSummary(LlmUsageSnapshot snapshot) {
        return new LlmUsageReportReducer.CallSummary(
                "8d67cb32-a78a-4ef3-b318-ff776ceaf4db",
                "logical-azure-1",
                "demo_conversationId",
                "SCPA_Demo_Agent",
                "gpt-5.4",
                "gpt-5.4-2026-03-05",
                "openai",
                "AzureOpenAI54",
                "azure-openai-chat-completions-v5",
                LlmCallKind.AGENT_ROUND,
                Instant.parse("2026-09-13T12:00:00Z"),
                Instant.parse("2026-09-13T12:00:01Z"),
                null,
                LlmCallOutcome.SUCCESS,
                LlmCallDispatchState.ATTEMPTED,
                true,
                true,
                true,
                false,
                snapshot.getStatus(),
                snapshot.getNormalized(),
                snapshot.getPresence(),
                snapshot.getRawUsage());
    }

    public static String azurePriceTableJson() {
        return "{\"version\":\"test-v1\",\"prices\":[{\"providerFamily\":\"openai\","
                + "\"apiShapeId\":\"azure-openai-chat-completions-v5\",\"requestedModel\":\"gpt-5.4\","
                + "\"inputPerMillionUsd\":1,\"outputPerMillionUsd\":2}]}";
    }
}
