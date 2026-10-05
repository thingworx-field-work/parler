package com.thingworx.things.agent.compaction;

import java.util.Locale;
import java.util.OptionalInt;

/**
 * Static provider/model input token limits for context budget telemetry
 * ({@code docs/agent/context-compaction.md} §7). Unknown models omit a limit so callers use
 * {@code llmContextMaxChars} alone.
 */
public final class ProviderModelInputLimitRegistry {

    private ProviderModelInputLimitRegistry() {}

    /**
     * @param apiShapeId from {@link com.thingworx.things.agent.llm.LlmUsageWireIds#getApiShapeId()} (lowercased match)
     * @param model        effective model or deployment id (normalized before lookup)
     * @return input token limit when known
     */
    public static OptionalInt lookupInputTokenLimit(String apiShapeId, String model) {
        String shape = apiShapeId != null ? apiShapeId.toLowerCase(Locale.ROOT) : "";
        String m = normalizeModelId(model);
        if (m.isEmpty()) {
            return OptionalInt.empty();
        }
        if (shape.contains("anthropic")) {
            return lookupAnthropic(m);
        }
        // OpenAI + Azure Chat Completions shapes share OpenAI model id conventions after normalization.
        return lookupOpenAiFamily(m);
    }

    static String normalizeModelId(String model) {
        if (model == null) {
            return "";
        }
        String s = model.trim().toLowerCase(Locale.ROOT);
        int at = s.indexOf('@');
        if (at > 0) {
            s = s.substring(0, at);
        }
        return s;
    }

    private static OptionalInt lookupAnthropic(String m) {
        // Longest / most specific prefixes first (docs/agent/context-compaction.md §7 table).
        if (m.startsWith("claude-3-7-sonnet") || m.startsWith("claude-3-5-haiku")) {
            return OptionalInt.of(200_000);
        }
        if (m.startsWith("claude-opus-4") || m.startsWith("claude-sonnet-4") || m.startsWith("claude-haiku-4")) {
            return OptionalInt.of(200_000);
        }
        return OptionalInt.empty();
    }

    private static OptionalInt lookupOpenAiFamily(String m) {
        if (m.startsWith("gpt-4.1")) {
            return OptionalInt.of(1_047_576);
        }
        if (m.startsWith("gpt-5")) {
            return OptionalInt.of(400_000);
        }
        if (m.startsWith("gpt-4o") || m.startsWith("gpt-4-turbo")) {
            return OptionalInt.of(128_000);
        }
        return OptionalInt.empty();
    }
}
