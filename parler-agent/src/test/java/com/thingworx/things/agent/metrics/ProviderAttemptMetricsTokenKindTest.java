package com.thingworx.things.agent.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.llm.usage.LlmCallKind;
import com.thingworx.things.agent.llm.usage.LlmUsageCapture;
import com.thingworx.things.agent.llm.usage.LlmUsageSnapshot;

/**
 * Fixture-level token kind inventory for {@link ProviderAttemptMetrics} with a fake sink (metrics-enhancement design:
 * Verification check 1 and the bounded-label part of check 3). Snapshots come from the production usage parser.
 */
class ProviderAttemptMetricsTokenKindTest {

    private static final String OPENAI_SHAPE = "openai-chat-completions-v4";
    private static final String AZURE_SHAPE = "azure-openai-chat-completions-v4";
    private static final String ANTHROPIC_SHAPE = "anthropic-messages-v1";
    private static final String OTHER_SHAPE = "custom-chat-v1";

    private static final String OPENAI_FULL = "{\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":50,"
            + "\"total_tokens\":150,"
            + "\"prompt_tokens_details\":{\"cached_tokens\":30,\"audio_tokens\":0},"
            + "\"completion_tokens_details\":{\"reasoning_tokens\":20,\"audio_tokens\":0,"
            + "\"accepted_prediction_tokens\":2,\"rejected_prediction_tokens\":1}}}";

    private static final String ANTHROPIC_FULL = "{\"usage\":{\"input_tokens\":10,\"cache_read_input_tokens\":20,"
            + "\"cache_creation_input_tokens\":5,"
            + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":3,\"ephemeral_1h_input_tokens\":2},"
            + "\"output_tokens\":8,\"output_tokens_details\":{\"thinking_tokens\":4}}}";

    private static List<String> record(String shape, String body) {
        RecordingMetricsSink sink = new RecordingMetricsSink();
        LlmUsageSnapshot usage = LlmUsageCapture.captureFromResponseBody(body,
                LlmUsageWireIds.forProviderThing("P", "Provider", shape, "model"));
        ProviderAttemptMetrics metrics = new ProviderAttemptMetrics(sink, shape, LlmCallKind.AGENT_ROUND, () -> 0L);
        metrics.finish(ProviderOutcome.SUCCESS, usage);
        metrics.finish(ProviderOutcome.SUCCESS, usage);
        assertEquals(1, sink.withPrefix("usage|").size(), "one usage sample per attempt");
        return sink.withPrefix("tokens|");
    }

    private static List<String> tokens(String family, String... kindValues) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < kindValues.length; i += 2) {
            out.add("tokens|" + family + "|agent_round|" + kindValues[i] + "|" + kindValues[i + 1]);
        }
        return out;
    }

    private static List<String> openAiFullExpected(String family) {
        return tokens(family,
                "input", "100", "output", "50", "total", "150", "input_uncached", "70", "input_cache_read", "30",
                "reasoning", "20", "input_audio", "0", "output_audio", "0", "output_accepted_prediction", "2",
                "output_rejected_prediction", "1");
    }

    @Test
    void openAiShapedFamiliesShareTheFieldMappingAndKeepTheirLabels() {
        assertEquals(openAiFullExpected("openai"), record(OPENAI_SHAPE, OPENAI_FULL));
        assertEquals(openAiFullExpected("azure_openai"), record(AZURE_SHAPE, OPENAI_FULL));
        assertEquals(openAiFullExpected("other"), record(OTHER_SHAPE, OPENAI_FULL));
    }

    @Test
    void reportedZeroIsAMeasurement() {
        String body = "{\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":0}}}";
        assertEquals(tokens("openai", "input", "0", "output", "0", "total", "0", "input_uncached", "0",
                "input_cache_read", "0"), record(OPENAI_SHAPE, body));
    }

    @Test
    void omittedFieldsContributeNoSeries() {
        String body = "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
        assertEquals(tokens("openai", "input", "10", "output", "5"), record(OPENAI_SHAPE, body));
    }

    @Test
    void partialUsageContributesAvailableValidDetail() {
        String body = "{\"usage\":{\"prompt_tokens\":7,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":3,\"audio_tokens\":1}}}";
        assertEquals(tokens("openai", "input", "7", "input_uncached", "4", "input_cache_read", "3",
                "input_audio", "1"), record(OPENAI_SHAPE, body));
    }

    @Test
    void invalidOrOverflowingCountsContributeNoTokenSeries() {
        String negativeRaw = "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,"
                + "\"completion_tokens_details\":{\"audio_tokens\":-1}}}";
        String fractionalRaw = "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,"
                + "\"completion_tokens_details\":{\"rejected_prediction_tokens\":1.5}}}";
        String overflow = "{\"usage\":{\"prompt_tokens\":9223372036854775808,\"completion_tokens\":5}}";
        String anthropicOverflow = "{\"usage\":{\"input_tokens\":9223372036854775807,"
                + "\"cache_read_input_tokens\":1,\"cache_creation_input_tokens\":0,\"output_tokens\":1}}";
        assertEquals(List.of(), record(OPENAI_SHAPE, negativeRaw));
        assertEquals(List.of(), record(OPENAI_SHAPE, fractionalRaw));
        assertEquals(List.of(), record(OPENAI_SHAPE, overflow));
        assertEquals(List.of(), record(ANTHROPIC_SHAPE, anthropicOverflow));
    }

    @Test
    void unavailableUsageContributesNoTokenSeries() {
        assertEquals(List.of(), record(OPENAI_SHAPE, "{\"choices\":[]}"));
        assertEquals(List.of(), record(OPENAI_SHAPE, "{not json"));
    }

    @Test
    void anthropicReportsCacheWritesTtlSplitsAndThinking() {
        assertEquals(tokens("anthropic",
                "input", "35", "output", "8", "total", "43", "input_uncached", "10", "input_cache_read", "20",
                "input_cache_write", "5", "input_cache_write_5m", "3", "input_cache_write_1h", "2",
                "reasoning", "4"), record(ANTHROPIC_SHAPE, ANTHROPIC_FULL));
    }

    @Test
    void anthropicMissingComponentLeavesInputAndTotalAbsent() {
        String body = "{\"usage\":{\"input_tokens\":10,\"cache_read_input_tokens\":20,\"output_tokens\":3}}";
        assertEquals(tokens("anthropic", "output", "3", "input_uncached", "10", "input_cache_read", "20"),
                record(ANTHROPIC_SHAPE, body));
    }

    @Test
    void anthropicDoesNotInferTtlSplits() {
        String body = "{\"usage\":{\"input_tokens\":1,\"cache_read_input_tokens\":0,"
                + "\"cache_creation_input_tokens\":4,\"output_tokens\":2}}";
        List<String> recorded = record(ANTHROPIC_SHAPE, body);
        assertTrue(recorded.stream().noneMatch(e -> e.contains("|input_cache_write_")), recorded::toString);
        assertTrue(recorded.contains("tokens|anthropic|agent_round|input_cache_write|4"), recorded::toString);
    }

    @Test
    void unsupportedFieldsAndArbitraryRawKeysProduceNoSeries() {
        String openAi = "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"foo_tokens\":9,"
                + "\"cache_creation_input_tokens\":4,"
                + "\"prompt_tokens_details\":{\"image_tokens\":3},"
                + "\"completion_tokens_details\":{\"unknown_tokens\":2}}}";
        assertEquals(tokens("openai", "input", "10", "output", "5"), record(OPENAI_SHAPE, openAi));

        String anthropic = "{\"usage\":{\"input_tokens\":10,\"output_tokens\":3,"
                + "\"prompt_tokens_details\":{\"audio_tokens\":5},"
                + "\"completion_tokens_details\":{\"accepted_prediction_tokens\":1}}}";
        assertEquals(tokens("anthropic", "output", "3", "input_uncached", "10"), record(ANTHROPIC_SHAPE, anthropic));
    }

    @Test
    void eachKindIsAddedAtMostOncePerAttempt() {
        List<String> recorded = record(OPENAI_SHAPE, OPENAI_FULL);
        Set<String> kinds = recorded.stream().map(e -> e.split("\\|")[3]).collect(Collectors.toSet());
        assertEquals(recorded.size(), kinds.size());
        recorded = record(ANTHROPIC_SHAPE, ANTHROPIC_FULL);
        kinds = recorded.stream().map(e -> e.split("\\|")[3]).collect(Collectors.toSet());
        assertEquals(recorded.size(), kinds.size());
    }

    @Test
    void tokenKindLabelIsTheBoundedDesignInventory() {
        assertEquals(List.of("input", "output", "total", "input_uncached", "input_cache_read", "input_cache_write",
                "input_cache_write_5m", "input_cache_write_1h", "reasoning", "input_audio", "output_audio",
                "output_accepted_prediction", "output_rejected_prediction"),
                Arrays.stream(TokenKind.values()).map(TokenKind::wireValue).collect(Collectors.toList()));
        Set<TokenKind> mapped = new java.util.HashSet<>(ProviderAttemptMetrics.NORMALIZED_KINDS.keySet());
        mapped.addAll(ProviderAttemptMetrics.RAW_KINDS.keySet());
        assertEquals(Set.of(TokenKind.values()), mapped);
        assertEquals(TokenKind.values().length,
                ProviderAttemptMetrics.NORMALIZED_KINDS.size() + ProviderAttemptMetrics.RAW_KINDS.size());
    }
}
