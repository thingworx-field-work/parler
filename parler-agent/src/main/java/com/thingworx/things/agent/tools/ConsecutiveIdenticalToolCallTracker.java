package com.thingworx.things.agent.tools;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Detects the third consecutive identical {@code (toolName, arguments, result)} tool call within one logical turn
 * (see {@code docs/agent/multi-chart-and-thrashing-safeguards.md} §3).
 */
public final class ConsecutiveIdenticalToolCallTracker {

    private static final ObjectMapper CANON_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .disable(SerializationFeature.INDENT_OUTPUT);

    private final Map<String, Bucket> buckets = new HashMap<>();

    private static final class Bucket {
        private final String lastResultDigestHex;
        private int identicalStreak;

        private Bucket(String lastResultDigestHex, int identicalStreak) {
            this.lastResultDigestHex = lastResultDigestHex;
            this.identicalStreak = identicalStreak;
        }
    }

    /**
     * If this dispatch should be short-circuited, records the block, bumps turn telemetry, and returns the synthetic
     * JSON envelope; otherwise empty.
     */
    public synchronized Optional<String> interceptThirdIdentical(String toolName, JsonNode argsNode) {
        Optional<String> env = shouldBlock(toolName, argsNode);
        if (env.isPresent()) {
            recordBlocked(toolName, argsNode);
            return env;
        }
        return Optional.empty();
    }

    public synchronized void recordCompletion(String toolName, JsonNode argsNode, String resultJson) {
        if (toolName == null || toolName.isEmpty()) {
            return;
        }
        String argsHash = sha256Hex(stableJsonBytes(argsNode));
        String key = bucketKey(toolName, argsHash);
        JsonNode resultForHash = resultJson == null
                ? JsonNodeFactory.instance.nullNode()
                : parseJsonForStableHash(resultJson);
        String resultDigest = sha256Hex(stableJsonBytes(resultForHash));
        Bucket b = buckets.get(key);
        if (b == null || !b.lastResultDigestHex.equals(resultDigest)) {
            buckets.put(key, new Bucket(resultDigest, 1));
        } else {
            b.identicalStreak++;
        }
    }

    public synchronized void recordBlocked(String toolName, JsonNode argsNode) {
        if (toolName == null || toolName.isEmpty()) {
            return;
        }
        String argsHash = sha256Hex(stableJsonBytes(argsNode));
        String key = bucketKey(toolName, argsHash);
        Bucket b = buckets.get(key);
        if (b != null) {
            b.identicalStreak++;
        }
        AgentToolContext.incrementRepetitionBlockedCountForTurnPerf();
    }

    private Optional<String> shouldBlock(String toolName, JsonNode argsNode) {
        if (toolName == null || toolName.isEmpty()) {
            return Optional.empty();
        }
        String argsHash = sha256Hex(stableJsonBytes(argsNode));
        String key = bucketKey(toolName, argsHash);
        Bucket b = buckets.get(key);
        if (b != null && b.identicalStreak >= 2) {
            return Optional.of(syntheticEnvelope(toolName, b.lastResultDigestHex));
        }
        return Optional.empty();
    }

    private static String bucketKey(String toolName, String argsHashHex) {
        return toolName + '\u0001' + argsHashHex;
    }

    private static String syntheticEnvelope(String toolName, String priorResultDigestHex) {
        String digest12 = priorResultDigestHex != null && priorResultDigestHex.length() >= 12
                ? priorResultDigestHex.substring(0, 12)
                : (priorResultDigestHex != null ? priorResultDigestHex : "");
        String safeName = escapeJsonString(toolName);
        return "{\"status\":\"error\",\"code\":\"REPETITION_BLOCKED\","
                + "\"message\":\"Tool '" + safeName + "' was called with identical arguments and returned identical "
                + "results 2 times already in this turn. The data has not changed. Stop calling this tool with these "
                + "arguments, and either finalize the answer with the data you have or take a different approach.\","
                + "\"toolName\":\"" + safeName + "\",\"repetitionCount\":2,\"priorResultDigest\":\"" + digest12 + "\"}";
    }

    private static String escapeJsonString(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    /**
     * Parses JSON (or non-JSON) into a canonical tree for stable SHA-256 hashing — same rules for tool arguments and
     * tool results ({@code docs/agent/multi-chart-and-thrashing-safeguards.md} §3.2).
     */
    public static JsonNode parseJsonForStableHash(String json) {
        try {
            if (json == null || json.isBlank()) {
                return CANON_MAPPER.readTree("{}");
            }
            JsonNode root = CANON_MAPPER.readTree(json);
            return canonicalizeJson(root);
        } catch (Exception e) {
            return JsonNodeFactory.instance.textNode(json == null ? "" : json);
        }
    }

    /** Parses tool arguments for hashing; object keys are canonicalized recursively. */
    public static JsonNode parseArgumentsForHashing(String argumentsJson) {
        return parseJsonForStableHash(argumentsJson);
    }

    static JsonNode canonicalizeJson(JsonNode n) {
        if (n == null || n.isNull()) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (n.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            java.util.TreeMap<String, JsonNode> sorted = new java.util.TreeMap<>();
            n.fields().forEachRemaining(e -> sorted.put(e.getKey(), canonicalizeJson(e.getValue())));
            sorted.forEach(out::set);
            return out;
        }
        if (n.isArray()) {
            ArrayNode a = JsonNodeFactory.instance.arrayNode();
            for (JsonNode c : n) {
                a.add(canonicalizeJson(c));
            }
            return a;
        }
        return n;
    }

    private static byte[] stableJsonBytes(JsonNode node) {
        try {
            return CANON_MAPPER.writeValueAsBytes(node);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data != null ? data : new byte[0]);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "0";
        }
    }
}
