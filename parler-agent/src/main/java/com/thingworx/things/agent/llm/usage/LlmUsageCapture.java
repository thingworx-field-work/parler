package com.thingworx.things.agent.llm.usage;

import java.math.BigInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.llm.LlmUsageWireIds;

/**
 * Captures provider usage from response JSON before content parsing (CC-7.3 / CC-7.5).
 */
public final class LlmUsageCapture {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BigInteger MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);

    private LlmUsageCapture() {}

    public static LlmUsageSnapshot captureFromResponseBody(String responseBody, LlmUsageWireIds wireIds) {
        if (responseBody == null || responseBody.isBlank()) {
            return LlmUsageSnapshot.unavailable();
        }
        try {
            JsonNode root = JSON.readTree(responseBody);
            return captureFromRoot(root, wireIds);
        } catch (Exception e) {
            return new LlmUsageSnapshot.Builder()
                    .status(LlmUsageSnapshot.UsageStatus.PARTIAL)
                    .captureError("usage_json_invalid")
                    .build();
        }
    }

    public static LlmUsageSnapshot captureFromRoot(JsonNode root, LlmUsageWireIds wireIds) {
        if (root == null || root.isMissingNode()) {
            return LlmUsageSnapshot.unavailable();
        }
        JsonNode usage = root.path("usage");
        if (usage.isMissingNode() || usage.isNull()) {
            return LlmUsageSnapshot.unavailable();
        }
        if (!usage.isObject()) {
            return new LlmUsageSnapshot.Builder()
                    .status(LlmUsageSnapshot.UsageStatus.INVALID)
                    .captureError(usageTypeError(usage))
                    .rawUsage(usage)
                    .build();
        }
        if (usage.isEmpty()) {
            return new LlmUsageSnapshot.Builder()
                    .status(LlmUsageSnapshot.UsageStatus.PARTIAL)
                    .captureError("usage_incomplete")
                    .rawUsage(usage.deepCopy())
                    .build();
        }
        String family = providerFamily(wireIds);
        ObjectNode rawCopy = (ObjectNode) usage.deepCopy();
        if ("anthropic".equals(family)) {
            return parseAnthropic(rawCopy);
        }
        return parseOpenAi(rawCopy);
    }

    private static String providerFamily(LlmUsageWireIds wireIds) {
        if (wireIds == null) {
            return "openai";
        }
        String shape = wireIds.getApiShapeId() != null ? wireIds.getApiShapeId().toLowerCase() : "";
        if (shape.startsWith("anthropic-")) {
            return "anthropic";
        }
        return "openai";
    }

    private static String usageTypeError(JsonNode usage) {
        if (usage.isTextual()) {
            return "usage_type_string";
        }
        if (usage.isNumber()) {
            return "usage_type_number";
        }
        if (usage.isBoolean()) {
            return "usage_type_boolean";
        }
        if (usage.isArray()) {
            return "usage_type_array";
        }
        return "usage_type_invalid";
    }

    private static LlmUsageSnapshot parseOpenAi(ObjectNode raw) {
        LlmUsageSnapshot.Builder builder = new LlmUsageSnapshot.Builder().rawUsage(raw);
        if (invalidDetailsContainer(raw.path("prompt_tokens_details"))
                || invalidDetailsContainer(raw.path("completion_tokens_details"))) {
            return builder.status(LlmUsageSnapshot.UsageStatus.INVALID)
                    .captureError("usage_invalid_container")
                    .build();
        }

        Long prompt = nonNegativeLong(raw.path("prompt_tokens"));
        Long completion = nonNegativeLong(raw.path("completion_tokens"));
        Long total = nonNegativeLong(raw.path("total_tokens"));
        Long cached = nonNegativeLong(raw.path("prompt_tokens_details").path("cached_tokens"));
        Long reasoning = nonNegativeLong(raw.path("completion_tokens_details").path("reasoning_tokens"));

        boolean invalid = hasInvalidNumber(raw.path("prompt_tokens"))
                || hasInvalidNumber(raw.path("completion_tokens"))
                || hasInvalidNumber(raw.path("total_tokens"))
                || hasInvalidNumber(raw.path("prompt_tokens_details").path("cached_tokens"))
                || hasInvalidNumber(raw.path("prompt_tokens_details").path("audio_tokens"))
                || hasInvalidNumber(raw.path("completion_tokens_details").path("reasoning_tokens"))
                || hasInvalidNumber(raw.path("completion_tokens_details").path("audio_tokens"))
                || hasInvalidNumber(raw.path("completion_tokens_details").path("accepted_prediction_tokens"))
                || hasInvalidNumber(raw.path("completion_tokens_details").path("rejected_prediction_tokens"));

        put(builder, "inputTokensTotal", prompt, reportedOrAbsent(prompt));
        put(builder, "outputTokensTotal", completion, reportedOrAbsent(completion));
        put(builder, "totalTokens", total, reportedOrAbsent(total));
        put(builder, "inputTokensCacheRead", cached, cached != null
                ? LlmUsageSnapshot.FieldPresence.REPORTED
                : LlmUsageSnapshot.FieldPresence.ABSENT);
        put(builder, "reasoningTokens", reasoning, reasoning != null
                ? LlmUsageSnapshot.FieldPresence.REPORTED
                : LlmUsageSnapshot.FieldPresence.ABSENT);

        if (prompt != null && cached != null && prompt >= cached) {
            put(builder, "inputTokensUncached", prompt - cached, LlmUsageSnapshot.FieldPresence.DERIVED);
        } else if (prompt != null && cached != null) {
            invalid = true;
        }

        if (!invalid && prompt != null && cached != null && cached > prompt) {
            invalid = true;
        }
        if (!invalid && reasoning != null && completion != null && reasoning > completion) {
            invalid = true;
        }
        if (!invalid && prompt != null && completion != null && total != null) {
            try {
                long expected = Math.addExact(prompt, completion);
                if (total != expected) {
                    invalid = true;
                }
            } catch (ArithmeticException overflow) {
                invalid = true;
            }
        }

        if (invalid) {
            builder.status(LlmUsageSnapshot.UsageStatus.INVALID).captureError("usage_invalid_counter");
            return builder.build();
        }
        if (prompt == null || completion == null) {
            builder.status(LlmUsageSnapshot.UsageStatus.PARTIAL).captureError("usage_incomplete");
            return builder.build();
        }
        builder.status(LlmUsageSnapshot.UsageStatus.COMPLETE);
        return builder.build();
    }

    private static LlmUsageSnapshot parseAnthropic(ObjectNode raw) {
        LlmUsageSnapshot.Builder builder = new LlmUsageSnapshot.Builder().rawUsage(raw);
        if (invalidDetailsContainer(raw.path("output_tokens_details"))
                || invalidDetailsContainer(raw.path("cache_creation"))) {
            return builder.status(LlmUsageSnapshot.UsageStatus.INVALID)
                    .captureError("usage_invalid_container")
                    .build();
        }

        Long input = nonNegativeLong(raw.path("input_tokens"));
        Long output = nonNegativeLong(raw.path("output_tokens"));
        Long cacheRead = nonNegativeLong(raw.path("cache_read_input_tokens"));
        Long cacheCreate = nonNegativeLong(raw.path("cache_creation_input_tokens"));
        Long thinking = nonNegativeLong(raw.path("output_tokens_details").path("thinking_tokens"));
        Long write5m = nonNegativeLong(raw.path("cache_creation").path("ephemeral_5m_input_tokens"));
        Long write1h = nonNegativeLong(raw.path("cache_creation").path("ephemeral_1h_input_tokens"));

        boolean invalid = hasInvalidNumber(raw.path("input_tokens"))
                || hasInvalidNumber(raw.path("output_tokens"))
                || hasInvalidNumber(raw.path("cache_read_input_tokens"))
                || hasInvalidNumber(raw.path("cache_creation_input_tokens"))
                || hasInvalidNumber(raw.path("output_tokens_details").path("thinking_tokens"))
                || hasInvalidNumber(raw.path("cache_creation").path("ephemeral_5m_input_tokens"))
                || hasInvalidNumber(raw.path("cache_creation").path("ephemeral_1h_input_tokens"));

        put(builder, "inputTokensUncached", input, reportedOrAbsent(input));
        put(builder, "inputTokensCacheRead", cacheRead, cacheRead != null
                ? LlmUsageSnapshot.FieldPresence.REPORTED
                : LlmUsageSnapshot.FieldPresence.ABSENT);
        put(builder, "inputTokensCacheWrite", cacheCreate, cacheCreate != null
                ? LlmUsageSnapshot.FieldPresence.REPORTED
                : LlmUsageSnapshot.FieldPresence.ABSENT);
        put(builder, "cacheWrite5mTokens", write5m, write5m != null
                ? LlmUsageSnapshot.FieldPresence.REPORTED
                : LlmUsageSnapshot.FieldPresence.ABSENT);
        put(builder, "cacheWrite1hTokens", write1h, write1h != null
                ? LlmUsageSnapshot.FieldPresence.REPORTED
                : LlmUsageSnapshot.FieldPresence.ABSENT);
        put(builder, "outputTokensTotal", output, reportedOrAbsent(output));
        put(builder, "reasoningTokens", thinking, thinking != null
                ? LlmUsageSnapshot.FieldPresence.REPORTED
                : LlmUsageSnapshot.FieldPresence.ABSENT);

        if (input != null && cacheRead != null && cacheCreate != null) {
            try {
                long totalInput = Math.addExact(input, Math.addExact(cacheRead, cacheCreate));
                if (totalInput >= 0) {
                    put(builder, "inputTokensTotal", totalInput, LlmUsageSnapshot.FieldPresence.DERIVED);
                }
            } catch (ArithmeticException overflow) {
                invalid = true;
            }
        }
        if (output != null && input != null && cacheRead != null && cacheCreate != null) {
            try {
                long total = Math.addExact(output, Math.addExact(input, Math.addExact(cacheRead, cacheCreate)));
                put(builder, "totalTokens", total, LlmUsageSnapshot.FieldPresence.DERIVED);
            } catch (ArithmeticException overflow) {
                invalid = true;
            }
        }

        if (!invalid && thinking != null && output != null && thinking > output) {
            invalid = true;
        }

        if (cacheCreate != null && (write5m != null || write1h != null)) {
            long ttlSum = 0L;
            boolean hasTtl = false;
            if (write5m != null) {
                ttlSum = write5m;
                hasTtl = true;
            }
            if (write1h != null) {
                try {
                    ttlSum = hasTtl ? Math.addExact(ttlSum, write1h) : write1h;
                    hasTtl = true;
                } catch (ArithmeticException overflow) {
                    invalid = true;
                }
            }
            if (hasTtl && ttlSum != cacheCreate) {
                invalid = true;
            }
        }

        if (invalid) {
            builder.status(LlmUsageSnapshot.UsageStatus.INVALID).captureError("usage_invalid_counter");
            return builder.build();
        }
        if (input == null || output == null) {
            builder.status(LlmUsageSnapshot.UsageStatus.PARTIAL).captureError("usage_incomplete");
            return builder.build();
        }
        builder.status(LlmUsageSnapshot.UsageStatus.COMPLETE);
        return builder.build();
    }

    private static LlmUsageSnapshot.FieldPresence reportedOrAbsent(Long value) {
        return value != null
                ? LlmUsageSnapshot.FieldPresence.REPORTED
                : LlmUsageSnapshot.FieldPresence.ABSENT;
    }

    private static boolean invalidDetailsContainer(JsonNode node) {
        return !node.isMissingNode() && !node.isNull() && !node.isObject();
    }

    private static void put(
            LlmUsageSnapshot.Builder builder,
            String key,
            Long value,
            LlmUsageSnapshot.FieldPresence presence) {
        builder.putNormalized(key, value, presence);
    }

    private static boolean hasInvalidNumber(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (!node.isNumber()) {
            return true;
        }
        if (!node.isIntegralNumber()) {
            return true;
        }
        BigInteger value = node.bigIntegerValue();
        return value.signum() < 0 || value.compareTo(MAX_LONG) > 0;
    }

    private static Long nonNegativeLong(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber()) {
            return null;
        }
        BigInteger value = node.bigIntegerValue();
        if (value.signum() < 0 || value.compareTo(MAX_LONG) > 0) {
            return null;
        }
        return value.longValue();
    }
}
