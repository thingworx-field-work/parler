package com.thingworx.things.agent.llm.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

class LlmUsageLatencyEvidenceTest {

    private static final BigDecimal EXPECTED_KNOWN_USD = new BigDecimal("0.026261");

    @BeforeEach
    void installSink() {
        LlmCallRecorder.resetForTest();
    }

    @AfterEach
    void cleanup() {
        AgentLlmCallStreamReader.clearTestHooks();
        LlmCallRecorder.resetForTest();
    }

    @Test
    void capturedAzureLatencyWithZeroCached_pricesKnownAmount() {
        LlmUsageSnapshot snapshot = AzureLatencyUsageFixtures.capture(26019, 121, 26140);
        assertEquals(LlmUsageSnapshot.UsageStatus.COMPLETE, snapshot.getStatus());
        assertEquals(Long.valueOf(26019L), snapshot.getNormalized().get("inputTokensTotal"));
        assertEquals(Long.valueOf(26019L), snapshot.getNormalized().get("inputTokensUncached"));
        assertEquals(Long.valueOf(0L), snapshot.getNormalized().get("inputTokensCacheRead"));

        LlmUsageCostCalculator calculator = new LlmUsageCostCalculator(AzureLatencyUsageFixtures.azurePriceTableJson());
        LlmUsageCostCalculator.CostResult result =
                calculator.priceCall(AzureLatencyUsageFixtures.callSummary(snapshot));
        assertEquals("known", result.status);
        assertEquals(0, EXPECTED_KNOWN_USD.compareTo(result.knownUsd));
    }

    @Test
    void capturedAzureLatency_emptyPriceTable_isUnpricedNotCostUnsupported() {
        LlmUsageSnapshot snapshot = AzureLatencyUsageFixtures.capture(1198, 1050, 2248);
        LlmUsageCostCalculator calculator = new LlmUsageCostCalculator("{\"version\":\"unpriced-v1\",\"prices\":[]}");
        LlmUsageCostCalculator.CostResult result =
                calculator.priceCall(AzureLatencyUsageFixtures.callSummary(snapshot));
        assertEquals("unpriced", result.status);
        assertNull(result.knownUsd);
    }

    @Test
    void capturedAzureLatencyWithUnsupportedBillingDimension_staysCostUnsupported() {
        String body = AzureLatencyUsageFixtures.responseBody(26019, 121, 26140);
        body = body.replace("\"latency_checkpoint\"", "\"vendor_billing_tokens\":5,\"latency_checkpoint\"");
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, AzureLatencyUsageFixtures.AZURE_IDS);
        LlmUsageCostCalculator calculator = new LlmUsageCostCalculator(AzureLatencyUsageFixtures.azurePriceTableJson());
        assertEquals("costUnsupported", calculator.priceCall(AzureLatencyUsageFixtures.callSummary(snapshot)).status);
    }

    @Test
    void capturedPartialUsageWithLatency_staysUnpriced() {
        String body = "{\"usage\":{\"prompt_tokens\":26019,\"prompt_tokens_details\":{\"cached_tokens\":0},"
                + "\"latency_checkpoint\":{\"service_ttlt_ms\":2411}}}";
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, AzureLatencyUsageFixtures.AZURE_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.PARTIAL, snapshot.getStatus());
        LlmUsageCostCalculator calculator = new LlmUsageCostCalculator(AzureLatencyUsageFixtures.azurePriceTableJson());
        assertEquals("unpriced", calculator.priceCall(AzureLatencyUsageFixtures.callSummary(snapshot)).status);
    }

    @Test
    void capturedInvalidUsageWithLatency_staysUnpriced() {
        String body = "{\"usage\":{\"prompt_tokens\":\"bad\",\"completion_tokens\":121,\"total_tokens\":26140,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":0},"
                + "\"latency_checkpoint\":{\"service_ttlt_ms\":2411}}}";
        LlmUsageSnapshot snapshot = LlmUsageCapture.captureFromResponseBody(body, AzureLatencyUsageFixtures.AZURE_IDS);
        assertEquals(LlmUsageSnapshot.UsageStatus.INVALID, snapshot.getStatus());
        LlmUsageCostCalculator calculator = new LlmUsageCostCalculator(AzureLatencyUsageFixtures.azurePriceTableJson());
        assertEquals("unpriced", calculator.priceCall(AzureLatencyUsageFixtures.callSummary(snapshot)).status);
    }

    @Test
    void reducerCsv_retainsLatencyValuesAndTokenTotals() throws Exception {
        LlmUsageSnapshot snapshot = AzureLatencyUsageFixtures.capture(26019, 121, 26140);
        LlmCallContext context = LlmCallRecorder.agentRoundContext(
                "rid", "demo_conversationId", "SCPA_Demo_Agent", 1, AzureLatencyUsageFixtures.AZURE_IDS, null);
        LlmCallRecorder.LlmCallAttempt attempt = LlmCallRecorder.begin(context);
        attempt.markDispatched();
        attempt.finishSuccess(
                AzureLatencyUsageFixtures.AZURE_IDS,
                snapshot,
                null,
                200,
                "req",
                "resp",
                "gpt-5.4-2026-03-05",
                10L);

        Instant now = Instant.now();
        LlmUsageReportQuery query = new LlmUsageReportQuery(now.minusSeconds(60), now.plusSeconds(60), null, null, null);
        AgentLlmCallStreamReader.setTestEvents(AgentLlmCallStreamWriter.testCapture());
        ObjectNode report = LlmUsageReportReducer.reduce(
                query,
                AgentLlmCallStreamReader.read(query, now.plusSeconds(60)),
                now.plusSeconds(60),
                new LlmUsageCostCalculator(AzureLatencyUsageFixtures.azurePriceTableJson()));
        assertEquals("known", report.withArray("calls").get(0).path("costStatus").asText());
        assertEquals(2411, report.withArray("calls").get(0).path("rawUsage").path("latency_checkpoint")
                .path("service_ttlt_ms").asInt());

        String csv = LlmUsageCsvWriter.write(report);
        assertTrue(csv.contains("latency_checkpoint/service_ttlt_ms"));
        assertTrue(csv.contains("2411"));
        assertTrue(csv.contains("26019"));
        assertEquals(0, report.path("costUnsupportedCount").asInt());
    }

    @Test
    void reducerWithMatchingPrices_countsKnownNotCostUnsupported() throws Exception {
        LlmUsageSnapshot snapshot = AzureLatencyUsageFixtures.capture(26019, 121, 26140);
        LlmCallContext context = LlmCallRecorder.agentRoundContext(
                "rid", "demo_conversationId", "SCPA_Demo_Agent", 1, AzureLatencyUsageFixtures.AZURE_IDS, null);
        LlmCallRecorder.LlmCallAttempt attempt = LlmCallRecorder.begin(context);
        attempt.markDispatched();
        attempt.finishSuccess(
                AzureLatencyUsageFixtures.AZURE_IDS,
                snapshot,
                null,
                200,
                "req",
                "resp",
                "gpt-5.4-2026-03-05",
                10L);

        Instant now = Instant.now();
        LlmUsageReportQuery query = new LlmUsageReportQuery(now.minusSeconds(60), now.plusSeconds(60), null, null, null);
        AgentLlmCallStreamReader.setTestEvents(AgentLlmCallStreamWriter.testCapture());
        ObjectNode report = LlmUsageReportReducer.reduce(
                query,
                AgentLlmCallStreamReader.read(query, now.plusSeconds(60)),
                now.plusSeconds(60),
                new LlmUsageCostCalculator(AzureLatencyUsageFixtures.azurePriceTableJson()));
        assertEquals(0, report.path("costUnsupportedCount").asInt());
        assertEquals(1, report.path("usageCompleteCount").asInt());
        assertEquals("0.0263", report.path("knownCostUsd").asText());
    }
}
