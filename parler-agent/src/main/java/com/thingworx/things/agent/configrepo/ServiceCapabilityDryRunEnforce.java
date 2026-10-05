package com.thingworx.things.agent.configrepo;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * SPR-5: enforce declared {@code dryRun.parameter} on capability execute (§5.1.8).
 *
 * <p>When {@code dryRun.supported=true}, the named parameter must participate in invocation:
 * missing → inject {@code true} (safe default); wrong JSON type → fail closed; boolean → keep.
 * Capabilities without dry-run support are unchanged.
 */
public final class ServiceCapabilityDryRunEnforce {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ServiceCapabilityDryRunEnforce() {}

    public static final class Result {
        private final String argumentsJson;
        private final String blockReason;

        private Result(String argumentsJson, String blockReason) {
            this.argumentsJson = argumentsJson;
            this.blockReason = blockReason;
        }

        public static Result ok(String argumentsJson) {
            return new Result(argumentsJson != null ? argumentsJson : "{}", null);
        }

        public static Result blocked(String reason) {
            return new Result(null, reason != null ? reason : "dry-run enforce failed");
        }

        public Optional<String> blockReason() {
            return Optional.ofNullable(blockReason);
        }

        public String argumentsJson() {
            return argumentsJson != null ? argumentsJson : "{}";
        }

        public boolean blocked() {
            return blockReason != null;
        }
    }

    /**
     * Apply dry-run enforce to tool-call argument JSON for the given capability metadata.
     * Legacy / null capability, or {@code dryRunSupported=false}, returns arguments unchanged.
     */
    public static Result apply(String argumentsJson, ServiceCapabilityMetadata capability) {
        if (capability == null || !capability.dryRunSupported()) {
            return Result.ok(argumentsJson);
        }
        String param = capability.dryRunParameter();
        if (param == null || param.isBlank()) {
            return Result.blocked("dryRun.supported requires a non-blank dryRun.parameter");
        }
        String raw = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        final JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (Exception e) {
            return Result.blocked("dry-run enforce: arguments are not JSON");
        }
        if (root == null || !root.isObject()) {
            return Result.blocked("dry-run enforce: arguments must be a JSON object");
        }
        ObjectNode obj = (ObjectNode) root;
        if (!obj.has(param) || obj.get(param).isNull()) {
            obj.put(param, true);
            try {
                return Result.ok(JSON.writeValueAsString(obj));
            } catch (Exception e) {
                return Result.blocked("dry-run enforce: failed to rewrite arguments");
            }
        }
        JsonNode v = obj.get(param);
        if (!v.isBoolean()) {
            return Result.blocked("dry-run parameter '" + param + "' must be boolean");
        }
        return Result.ok(raw);
    }
}
