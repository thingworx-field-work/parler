package com.thingworx.things.agent.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses {@code /providers/route_profiles.json} (U7 §7.1 / D8). Profiles reference Provider
 * Thing names only — credentials and native endpoints are rejected as forbidden keys.
 */
public final class ProviderRouteProfilesParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "apikey", "api_key", "credential", "credentials", "password", "secret",
            "endpoint", "baseurl", "base_url", "url", "authorization");

    private ProviderRouteProfilesParser() {}

    public static final class ParseResult {
        private final Map<String, ProviderRouteProfile> profilesById;
        private final List<String> diagnostics;

        ParseResult(Map<String, ProviderRouteProfile> profilesById, List<String> diagnostics) {
            this.profilesById = Map.copyOf(profilesById);
            this.diagnostics = List.copyOf(diagnostics);
        }

        public Map<String, ProviderRouteProfile> profilesById() {
            return profilesById;
        }

        public List<String> diagnostics() {
            return diagnostics;
        }

        public Optional<ProviderRouteProfile> find(String id) {
            if (id == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(profilesById.get(id.trim()));
        }
    }

    public static ParseResult parse(String rawJson) {
        List<String> diagnostics = new ArrayList<>();
        Map<String, ProviderRouteProfile> out = new LinkedHashMap<>();
        if (rawJson == null || rawJson.isBlank()) {
            diagnostics.add("empty route profiles document");
            return new ParseResult(out, diagnostics);
        }
        try {
            JsonNode root = MAPPER.readTree(rawJson);
            JsonNode profilesNode;
            if (root.isArray()) {
                profilesNode = root;
            } else if (root.isObject() && root.has("profiles") && root.get("profiles").isArray()) {
                profilesNode = root.get("profiles");
            } else {
                diagnostics.add("root must be an array or object with profiles array");
                return new ParseResult(out, diagnostics);
            }
            for (int i = 0; i < profilesNode.size(); i++) {
                JsonNode n = profilesNode.get(i);
                if (n == null || !n.isObject()) {
                    diagnostics.add("profiles[" + i + "] skipped: not an object");
                    continue;
                }
                Optional<String> forbidden = firstForbiddenKey(n);
                if (forbidden.isPresent()) {
                    diagnostics.add("profiles[" + i + "] skipped: forbidden key " + forbidden.get());
                    continue;
                }
                try {
                    ProviderRouteProfile p = parseOne(n);
                    if (out.containsKey(p.id())) {
                        diagnostics.add("profiles[" + i + "] skipped: duplicate id " + p.id());
                        continue;
                    }
                    out.put(p.id(), p);
                } catch (IllegalArgumentException ex) {
                    diagnostics.add("profiles[" + i + "] skipped: " + ex.getMessage());
                }
            }
        } catch (Exception ex) {
            diagnostics.add("unreadable route profiles JSON: " + ex.getMessage());
        }
        return new ParseResult(out, diagnostics);
    }

    private static Optional<String> firstForbiddenKey(JsonNode n) {
        var it = n.fieldNames();
        while (it.hasNext()) {
            String k = it.next();
            if (FORBIDDEN_KEYS.contains(k.toLowerCase(Locale.ROOT))) {
                return Optional.of(k);
            }
        }
        return Optional.empty();
    }

    private static ProviderRouteProfile parseOne(JsonNode n) {
        String id = text(n, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id required");
        }
        if (!n.has("providers") || !n.get("providers").isArray() || n.get("providers").isEmpty()) {
            throw new IllegalArgumentException("providers required");
        }
        List<String> providers = new ArrayList<>();
        for (JsonNode p : n.get("providers")) {
            if (p == null || !p.isTextual() || p.asText().isBlank()) {
                throw new IllegalArgumentException("providers entries must be non-empty strings");
            }
            providers.add(p.asText().trim());
        }
        Set<ProviderCapabilityToken> caps = new LinkedHashSet<>();
        if (n.has("requiredCapabilities")) {
            if (!n.get("requiredCapabilities").isArray()) {
                throw new IllegalArgumentException("requiredCapabilities must be an array");
            }
            for (JsonNode c : n.get("requiredCapabilities")) {
                caps.add(ProviderCapabilityToken.valueOf(c.asText().trim().toUpperCase(Locale.ROOT)));
            }
        }
        Set<String> classifications = new LinkedHashSet<>();
        if (n.has("allowedDataClassifications")) {
            if (!n.get("allowedDataClassifications").isArray()) {
                throw new IllegalArgumentException("allowedDataClassifications must be an array");
            }
            for (JsonNode c : n.get("allowedDataClassifications")) {
                if (!c.isTextual() || c.asText().isBlank()) {
                    throw new IllegalArgumentException("allowedDataClassifications entries must be strings");
                }
                classifications.add(c.asText().trim());
            }
        }
        int maxSameProviderRetries = intOr(n, "maxSameProviderRetries", 1);
        int maxProvidersTried = intOr(n, "maxProvidersTried", providers.size());
        int maxTotalAttempts = intOr(n, "maxTotalAttempts", 3);
        long maxCumulativeWaitMs = longOr(n, "maxCumulativeWaitMs", 30_000L);
        long maxWallTimeMs = longOr(n, "maxWallTimeMs", 60_000L);
        // D10: route profile caps are bounded work controls — never accept non-positive wait/wall
        // or attempt ceilings (0/-1 must not become uncapped at runtime).
        if (maxSameProviderRetries < 0) {
            throw new IllegalArgumentException("maxSameProviderRetries must be >= 0");
        }
        if (maxProvidersTried < 1) {
            throw new IllegalArgumentException("maxProvidersTried must be >= 1");
        }
        if (maxTotalAttempts < 1) {
            throw new IllegalArgumentException("maxTotalAttempts must be >= 1");
        }
        if (maxCumulativeWaitMs <= 0L) {
            throw new IllegalArgumentException("maxCumulativeWaitMs must be > 0");
        }
        if (maxWallTimeMs <= 0L) {
            throw new IllegalArgumentException("maxWallTimeMs must be > 0");
        }
        return new ProviderRouteProfile(
                id,
                providers,
                caps,
                classifications,
                maxSameProviderRetries,
                maxProvidersTried,
                maxTotalAttempts,
                maxCumulativeWaitMs,
                maxWallTimeMs,
                text(n, "qualityTier"),
                !n.has("fallbackVisible") || n.get("fallbackVisible").asBoolean(true));
    }

    private static String text(JsonNode n, String field) {
        if (!n.has(field) || n.get(field).isNull()) {
            return "";
        }
        if (!n.get(field).isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return n.get(field).asText("").trim();
    }

    private static int intOr(JsonNode n, String field, int dflt) {
        if (!n.has(field) || n.get(field).isNull()) {
            return dflt;
        }
        if (!n.get(field).isNumber()) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        return n.get(field).asInt();
    }

    private static long longOr(JsonNode n, String field, long dflt) {
        if (!n.has(field) || n.get(field).isNull()) {
            return dflt;
        }
        if (!n.get(field).isNumber()) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        return n.get(field).asLong();
    }
}
