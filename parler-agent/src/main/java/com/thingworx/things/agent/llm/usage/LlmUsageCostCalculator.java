package com.thingworx.things.agent.llm.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Prices normalized usage from {@code usagePricesJson} (CC-7.6).
 */
public final class LlmUsageCostCalculator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");
    private static final Set<String> SUPPORTED_OPENAI_BILLING_LEAVES = Set.of(
            "prompt_tokens",
            "completion_tokens",
            "total_tokens",
            "prompt_tokens_details/cached_tokens",
            "completion_tokens_details/reasoning_tokens",
            "completion_tokens_details/accepted_prediction_tokens",
            "completion_tokens_details/rejected_prediction_tokens");
    private static final Set<String> SUPPORTED_ANTHROPIC_BILLING_LEAVES = Set.of(
            "input_tokens",
            "output_tokens",
            "cache_read_input_tokens",
            "cache_creation_input_tokens",
            "cache_creation/ephemeral_5m_input_tokens",
            "cache_creation/ephemeral_1h_input_tokens",
            "output_tokens_details/thinking_tokens");
    private static final Set<String> NON_BILLING_DIAGNOSTIC_ROOTS = Set.of("latency_checkpoint");

    private final String priceVersion;
    private final List<PriceEntry> entries;

    public LlmUsageCostCalculator(String usagePricesJson) {
        Parsed parsed = parse(usagePricesJson);
        this.priceVersion = parsed.version;
        this.entries = parsed.entries;
    }

    public String getPriceVersion() {
        return priceVersion;
    }

    public CostResult priceCall(LlmUsageReportReducer.CallSummary summary) {
        if (summary == null || summary.conflict) {
            return CostResult.unpriced();
        }
        if (summary.usageStatus != LlmUsageSnapshot.UsageStatus.COMPLETE) {
            return CostResult.unpriced();
        }
        if (hasUnsupportedBillingDimensions(summary)) {
            return CostResult.unsupported();
        }
        List<PriceEntry> matches = new ArrayList<>();
        Instant at = summary.callStartedAt != null ? summary.callStartedAt : Instant.now();
        for (PriceEntry entry : entries) {
            if (entry.matches(summary, at)) {
                matches.add(entry);
            }
        }
        if (matches.isEmpty()) {
            return CostResult.unpriced();
        }
        if (matches.size() > 1) {
            return CostResult.conflict();
        }
        PriceEntry price = matches.get(0);
        if ("anthropic".equalsIgnoreCase(summary.providerFamily)) {
            return priceAnthropic(price, summary);
        }
        return priceOpenAi(price, summary);
    }

    private static boolean hasUnsupportedBillingDimensions(LlmUsageReportReducer.CallSummary summary) {
        if (summary.rawUsage == null || summary.rawUsage.isMissingNode() || !summary.rawUsage.isObject()) {
            return false;
        }
        Set<String> supportedLeaves = "anthropic".equalsIgnoreCase(summary.providerFamily)
                ? SUPPORTED_ANTHROPIC_BILLING_LEAVES
                : SUPPORTED_OPENAI_BILLING_LEAVES;
        return hasPositiveUnsupportedLeaf(summary.rawUsage, "", supportedLeaves);
    }

    private static boolean hasPositiveUnsupportedLeaf(JsonNode node, String prefix, Set<String> supportedLeaves) {
        if (node == null || node.isMissingNode()) {
            return false;
        }
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                String path = prefix.isEmpty() ? name : prefix + "/" + name;
                if (hasPositiveUnsupportedLeaf(node.get(name), path, supportedLeaves)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                if (hasPositiveUnsupportedLeaf(element, prefix, supportedLeaves)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isNumber() && node.asLong(0L) > 0L) {
            if (isNonBillingDiagnosticPath(prefix)) {
                return false;
            }
            return !supportedLeaves.contains(prefix);
        }
        return false;
    }

    private static boolean isNonBillingDiagnosticPath(String path) {
        for (String root : NON_BILLING_DIAGNOSTIC_ROOTS) {
            if (path.equals(root) || path.startsWith(root + "/")) {
                return true;
            }
        }
        return false;
    }

    private CostResult priceOpenAi(PriceEntry price, LlmUsageReportReducer.CallSummary summary) {
        Long prompt = value(summary, "inputTokensTotal");
        Long cached = value(summary, "inputTokensCacheRead");
        Long uncached = value(summary, "inputTokensUncached");
        Long completion = value(summary, "outputTokensTotal");
        if (prompt == null || completion == null || price.inputPerMillion == null || price.outputPerMillion == null) {
            return CostResult.unsupported();
        }
        long uncachedTokens;
        if (uncached != null) {
            uncachedTokens = uncached;
        } else if (cached != null) {
            uncachedTokens = prompt - cached;
            if (uncachedTokens < 0) {
                return CostResult.unsupported();
            }
        } else {
            return CostResult.unsupported();
        }
        BigDecimal cost = BigDecimal.ZERO;
        cost = cost.add(millionTokenCost(uncachedTokens, price.inputPerMillion));
        if (cached != null) {
            if (cached > 0 && price.cacheReadPerMillion == null) {
                return CostResult.unsupported();
            }
            if (price.cacheReadPerMillion != null) {
                cost = cost.add(millionTokenCost(cached, price.cacheReadPerMillion));
            }
        }
        cost = cost.add(millionTokenCost(completion, price.outputPerMillion));
        return CostResult.known(cost);
    }

    private CostResult priceAnthropic(PriceEntry price, LlmUsageReportReducer.CallSummary summary) {
        Long input = value(summary, "inputTokensUncached");
        Long cacheRead = value(summary, "inputTokensCacheRead");
        Long write5m = value(summary, "cacheWrite5mTokens");
        Long write1h = value(summary, "cacheWrite1hTokens");
        Long cacheWrite = value(summary, "inputTokensCacheWrite");
        Long output = value(summary, "outputTokensTotal");
        if (input == null || output == null || price.inputPerMillion == null || price.outputPerMillion == null) {
            return CostResult.unsupported();
        }
        BigDecimal cost = BigDecimal.ZERO;
        cost = cost.add(millionTokenCost(input, price.inputPerMillion));
        if (cacheRead != null && cacheRead > 0) {
            if (price.cacheReadPerMillion == null) {
                return CostResult.unsupported();
            }
            cost = cost.add(millionTokenCost(cacheRead, price.cacheReadPerMillion));
        }
        if (write5m != null && write5m > 0) {
            if (price.write5mPerMillion == null) {
                return CostResult.unsupported();
            }
            cost = cost.add(millionTokenCost(write5m, price.write5mPerMillion));
        }
        if (write1h != null && write1h > 0) {
            if (price.write1hPerMillion == null) {
                return CostResult.unsupported();
            }
            cost = cost.add(millionTokenCost(write1h, price.write1hPerMillion));
        }
        if (cacheWrite != null && cacheWrite > 0) {
            long ttlSum = (write5m != null ? write5m : 0L) + (write1h != null ? write1h : 0L);
            if (ttlSum != cacheWrite) {
                return CostResult.unsupported();
            }
            if ((write5m == null || write5m == 0L) && (write1h == null || write1h == 0L)) {
                return CostResult.unsupported();
            }
        }
        cost = cost.add(millionTokenCost(output, price.outputPerMillion));
        return CostResult.known(cost);
    }

    private static Long value(LlmUsageReportReducer.CallSummary summary, String key) {
        return summary.normalized != null ? summary.normalized.get(key) : null;
    }

    private static BigDecimal millionTokenCost(long tokens, BigDecimal perMillion) {
        if (perMillion == null) {
            throw new IllegalStateException("rate required");
        }
        return perMillion.multiply(BigDecimal.valueOf(tokens)).divide(ONE_MILLION, 12, RoundingMode.HALF_UP);
    }

    private static String serviceTierFromRaw(JsonNode rawUsage) {
        if (rawUsage == null || rawUsage.isMissingNode()) {
            return null;
        }
        JsonNode tier = rawUsage.path("service_tier");
        if (tier.isMissingNode() || tier.isNull()) {
            return null;
        }
        String text = tier.asText(null);
        return text != null && !text.isBlank() ? text : null;
    }

    private static Parsed parse(String json) {
        Parsed parsed = new Parsed();
        parsed.version = "unpriced-v1";
        if (json == null || json.isBlank()) {
            return parsed;
        }
        try {
            JsonNode root = JSON.readTree(json);
            if (root.has("version")) {
                parsed.version = root.get("version").asText("unpriced-v1");
            }
            JsonNode prices = root.path("prices");
            if (!prices.isArray()) {
                return parsed;
            }
            for (JsonNode node : prices) {
                String currency = text(node, "currency");
                if (currency != null && !"USD".equalsIgnoreCase(currency)) {
                    continue;
                }
                PriceEntry entry = new PriceEntry();
                entry.providerFamily = text(node, "providerFamily");
                entry.apiShapeId = text(node, "apiShapeId");
                entry.requestedModel = text(node, "requestedModel");
                entry.serviceTier = text(node, "serviceTier");
                entry.effectiveFrom = parseInstant(text(node, "effectiveFrom"));
                entry.effectiveTo = parseInstant(text(node, "effectiveTo"));
                entry.inputPerMillion = decimal(node, "inputPerMillionUsd");
                entry.outputPerMillion = decimal(node, "outputPerMillionUsd");
                entry.cacheReadPerMillion = decimal(node, "cacheReadPerMillionUsd");
                entry.write5mPerMillion = decimal(node, "cacheWrite5mPerMillionUsd");
                entry.write1hPerMillion = decimal(node, "cacheWrite1hPerMillionUsd");
                parsed.entries.add(entry);
            }
        } catch (Exception ignored) {
            // keep defaults
        }
        return parsed;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            return null;
        }
        return value.decimalValue();
    }

    private static final class Parsed {
        private String version = "unpriced-v1";
        private final List<PriceEntry> entries = new ArrayList<>();
    }

    private static final class PriceEntry {
        private String providerFamily;
        private String apiShapeId;
        private String requestedModel;
        private String serviceTier;
        private Instant effectiveFrom;
        private Instant effectiveTo;
        private BigDecimal inputPerMillion;
        private BigDecimal outputPerMillion;
        private BigDecimal cacheReadPerMillion;
        private BigDecimal write5mPerMillion;
        private BigDecimal write1hPerMillion;

        boolean matches(LlmUsageReportReducer.CallSummary summary, Instant at) {
            if (providerFamily != null && !providerFamily.equalsIgnoreCase(summary.providerFamily)) {
                return false;
            }
            if (apiShapeId != null && !apiShapeId.equals(summary.apiShapeId)) {
                return false;
            }
            if (requestedModel != null && !requestedModel.equals(summary.requestedModel)) {
                return false;
            }
            if (serviceTier != null && !serviceTier.isBlank()) {
                String callTier = serviceTierFromRaw(summary.rawUsage);
                if (callTier == null || !serviceTier.equalsIgnoreCase(callTier)) {
                    return false;
                }
            }
            if (effectiveFrom != null && at.isBefore(effectiveFrom)) {
                return false;
            }
            if (effectiveTo != null && !at.isBefore(effectiveTo)) {
                return false;
            }
            return at != null;
        }
    }

    public static final class CostResult {
        public final BigDecimal knownUsd;
        public final boolean unpriced;
        public final boolean unsupported;
        public final String status;

        private CostResult(BigDecimal knownUsd, boolean unpriced, boolean unsupported, String status) {
            this.knownUsd = knownUsd;
            this.unpriced = unpriced;
            this.unsupported = unsupported;
            this.status = status;
        }

        public static CostResult known(BigDecimal usd) {
            return new CostResult(usd, false, false, "known");
        }

        public static CostResult unpriced() {
            return new CostResult(null, true, false, "unpriced");
        }

        public static CostResult unsupported() {
            return new CostResult(null, false, true, "costUnsupported");
        }

        public static CostResult conflict() {
            return new CostResult(null, false, true, "priceConflict");
        }
    }
}
