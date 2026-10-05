package com.thingworx.things.agent.configrepo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.tools.ThingworxRootEntityTypes;

/**
 * Parsed {@code /policies/invoke_service.json} allow-only policy (configuration repository topic).
 */
public final class InvokeServiceAllowPolicy {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Malformed JSON or invalid rule shape: never bypass HITL; operators should see ERROR diagnostics. */
    public static final InvokeServiceAllowPolicy INVALID = new InvokeServiceAllowPolicy(List.of(), true, false);

    /** Policy file absent or empty body: never bypass HITL; distinct from {@link #INVALID} for diagnostics only. */
    public static final InvokeServiceAllowPolicy FILE_MISSING = new InvokeServiceAllowPolicy(List.of(), false, true);

    private final List<Rule> rules;
    private final boolean invalid;
    private final boolean fileMissing;

    private InvokeServiceAllowPolicy(List<Rule> rules, boolean invalid, boolean fileMissing) {
        this.rules = rules;
        this.invalid = invalid;
        this.fileMissing = fileMissing;
    }

    public boolean isInvalid() {
        return invalid;
    }

    /** True when the policy file was absent or had no readable content (not a parse failure). */
    public boolean isFileMissing() {
        return fileMissing;
    }

    public int ruleCount() {
        return rules.size();
    }

    /**
     * Rule ids after sort (priority ascending, then declaration order in the JSON file). Intended for unit tests and
     * stable diagnostics.
     */
    public List<String> sortedRuleIdsInEffectOrder() {
        if (invalid || fileMissing) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Rule r : rules) {
            out.add(r.id());
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * @param entityType effective root service-target type name (e.g. Thing)
     * @param entityName  resolved entity name
     * @param serviceName resolved service name
     * @return true when a rule matches (bypass HITL for invoke_service)
     */
    public boolean allowsBypass(String entityType, String entityName, String serviceName) {
        if (invalid || fileMissing || rules.isEmpty()) {
            return false;
        }
        if (entityType == null || entityName == null || serviceName == null) {
            return false;
        }
        for (Rule r : rules) {
            if (r.matches(entityType, entityName, serviceName)) {
                return true;
            }
        }
        return false;
    }

    public static InvokeServiceAllowPolicy parseJsonOrInvalid(String json) {
        return parseJsonOrInvalid(json, null);
    }

    /**
     * @param log when non-null, structural invalid policy content is logged at ERROR (per configuration-repository.md).
     */
    public static InvokeServiceAllowPolicy parseJsonOrInvalid(String json, Logger log) {
        if (json == null || json.isBlank()) {
            return FILE_MISSING;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                logInvalid(log, "invoke_service policy: root is not a JSON object");
                return INVALID;
            }
            int ver = root.path("version").asInt(-1);
            if (ver != 1) {
                logInvalid(log, "invoke_service policy: unsupported or missing version (expected 1)");
                return INVALID;
            }
            JsonNode rulesNode = root.get("rules");
            if (rulesNode == null || !rulesNode.isArray()) {
                logInvalid(log, "invoke_service policy: rules must be a JSON array");
                return INVALID;
            }
            List<Rule> rules = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            int fileIndex = 0;
            for (JsonNode rn : rulesNode) {
                if (rn == null || !rn.isObject()) {
                    logInvalid(log, "invoke_service policy: each rule must be a JSON object");
                    return INVALID;
                }
                String id = textNonEmpty(rn, "id");
                if (id == null || ids.contains(id)) {
                    logInvalid(log, "invoke_service policy: duplicate or empty rule id");
                    return INVALID;
                }
                ids.add(id);
                if (!rn.has("priority") || !rn.get("priority").canConvertToInt()) {
                    logInvalid(log, "invoke_service policy: rule " + id + " missing integer priority");
                    return INVALID;
                }
                int priority = rn.get("priority").asInt();
                JsonNode match = rn.get("match");
                if (match == null || !match.isObject()) {
                    logInvalid(log, "invoke_service policy: rule " + id + " missing match object");
                    return INVALID;
                }
                List<String> entityTypes = readPatternArray(match, "entityTypes");
                List<String> entityNames = readPatternArray(match, "entityNames");
                List<String> serviceNames = readPatternArray(match, "serviceNames");
                if (entityTypes == null || entityNames == null || serviceNames == null) {
                    logInvalid(log, "invoke_service policy: rule " + id + " has invalid match arrays");
                    return INVALID;
                }
                if (!validEntityTypePatterns(entityTypes)) {
                    logInvalid(log, "invoke_service policy: rule " + id + " has invalid entityTypes patterns");
                    return INVALID;
                }
                rules.add(new Rule(id, priority, fileIndex, entityTypes, entityNames, serviceNames));
                fileIndex++;
            }
            rules.sort(Comparator.comparingInt((Rule r) -> r.priority).thenComparingInt(r -> r.fileOrderIndex));
            return new InvokeServiceAllowPolicy(Collections.unmodifiableList(rules), false, false);
        } catch (Exception e) {
            if (log != null) {
                log.error("invoke_service policy: JSON parse failed: {}", e.getMessage(), e);
            }
            return INVALID;
        }
    }

    private static void logInvalid(Logger log, String message) {
        if (log != null) {
            log.error("{}", message);
        }
    }

    private static String textNonEmpty(JsonNode o, String field) {
        if (!o.has(field) || !o.get(field).isTextual()) {
            return null;
        }
        String s = o.get(field).asText().trim();
        return s.isEmpty() ? null : s;
    }

    /** @return null when invalid (missing field, empty array, blank pattern) */
    private static boolean validEntityTypePatterns(List<String> patterns) {
        for (String p : patterns) {
            if (!p.contains("*")) {
                boolean ok = ThingworxRootEntityTypes.sortedRootEntityTypeNames().stream().anyMatch(n -> n.equals(p));
                if (!ok) {
                    return false;
                }
            }
        }
        return true;
    }

    private static List<String> readPatternArray(JsonNode match, String field) {
        JsonNode arr = match.get(field);
        if (arr == null || !arr.isArray() || arr.size() == 0) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            if (n == null || !n.isTextual()) {
                return null;
            }
            String p = n.asText();
            if (p == null || p.isBlank()) {
                return null;
            }
            if (p.contains("**")) {
                return null;
            }
            out.add(p);
        }
        return out;
    }

    private static final class Rule {
        private final String id;
        private final int priority;
        private final int fileOrderIndex;
        private final List<String> entityTypes;
        private final List<String> entityNames;
        private final List<String> serviceNames;

        Rule(String id, int priority, int fileOrderIndex, List<String> entityTypes, List<String> entityNames,
                List<String> serviceNames) {
            this.id = id;
            this.priority = priority;
            this.fileOrderIndex = fileOrderIndex;
            this.entityTypes = entityTypes;
            this.entityNames = entityNames;
            this.serviceNames = serviceNames;
        }

        String id() {
            return id;
        }

        int priority() {
            return priority;
        }

        int fileOrderIndex() {
            return fileOrderIndex;
        }

        boolean matches(String entityType, String entityName, String serviceName) {
            return anyMatches(entityTypes, entityType) && anyMatches(entityNames, entityName)
                    && anyMatches(serviceNames, serviceName);
        }

        private static boolean anyMatches(List<String> patterns, String value) {
            for (String p : patterns) {
                if (GlobPattern.matches(p, value)) {
                    return true;
                }
            }
            return false;
        }
    }
}
