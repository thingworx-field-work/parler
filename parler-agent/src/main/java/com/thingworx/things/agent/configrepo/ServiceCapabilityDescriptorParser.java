package com.thingworx.things.agent.configrepo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Parses and validates additive U7 / G13 capability fields on one {@code extended_tools.json}
 * entry under {@link ServiceCapabilityManifestPolicy} (SPR-0/SPR-1).
 *
 * <p>Legacy entries with no capability fields succeed with {@link ParseResult#legacy()}. Invalid
 * capability shapes or ServiceDefinition mismatches return {@link ParseResult#skip(String)}.
 * Declared subfields that are present with the wrong JSON type fail closed (skip), and are never
 * treated as absent.
 */
public final class ServiceCapabilityDescriptorParser {

    private ServiceCapabilityDescriptorParser() {}

    public static final class ParseResult {
        private final boolean skip;
        private final String skipReason;
        private final ServiceCapabilityMetadata metadata;

        private ParseResult(boolean skip, String skipReason, ServiceCapabilityMetadata metadata) {
            this.skip = skip;
            this.skipReason = skipReason;
            this.metadata = metadata;
        }

        public static ParseResult legacy() {
            return new ParseResult(false, null, null);
        }

        public static ParseResult ok(ServiceCapabilityMetadata metadata) {
            return new ParseResult(false, null, metadata);
        }

        public static ParseResult skip(String reason) {
            return new ParseResult(true, reason, null);
        }

        public boolean shouldSkip() {
            return skip;
        }

        public String skipReason() {
            return skipReason;
        }

        public Optional<ServiceCapabilityMetadata> metadata() {
            return Optional.ofNullable(metadata);
        }
    }

    /** Distinguishes missing/null from present-with-wrong-type for optional string fields. */
    static final class OptionalText {
        enum Kind {
            ABSENT,
            PRESENT,
            WRONG_TYPE
        }

        private final Kind kind;
        private final String value;

        private OptionalText(Kind kind, String value) {
            this.kind = kind;
            this.value = value;
        }

        static OptionalText absent() {
            return new OptionalText(Kind.ABSENT, null);
        }

        static OptionalText present(String value) {
            return new OptionalText(Kind.PRESENT, value);
        }

        static OptionalText wrongType() {
            return new OptionalText(Kind.WRONG_TYPE, null);
        }

        Kind kind() {
            return kind;
        }

        String value() {
            return value;
        }

        boolean isPresent() {
            return kind == Kind.PRESENT;
        }

        boolean isWrongType() {
            return kind == Kind.WRONG_TYPE;
        }
    }

    /**
     * @param entry tool JSON object
     * @param params Service parameter lookup after target resolution; may be null for JSON-only checks
     */
    public static ParseResult parse(JsonNode entry, ServiceParameterLookup params) {
        if (entry == null || !entry.isObject()) {
            return ParseResult.skip("entry is not a JSON object");
        }
        for (String forbidden : ServiceCapabilityManifestPolicy.FORBIDDEN_U7_SEMANTIC_FIELDS) {
            if (entry.has(forbidden)) {
                return ParseResult.skip("forbidden U8/U11 field present: " + forbidden);
            }
        }

        boolean declaresCapability = declaresCapabilityFields(entry);
        if (!declaresCapability) {
            return ParseResult.legacy();
        }

        if (!entry.has("risk") || entry.get("risk").isNull()) {
            return ParseResult.skip("capability fields require risk");
        }
        if (!entry.get("risk").isTextual()) {
            return ParseResult.skip("risk must be a string");
        }
        ServiceCapabilityRisk risk;
        try {
            risk = ServiceCapabilityRisk.valueOf(entry.get("risk").asText().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ParseResult.skip("invalid risk (expected READ_ONLY|MUTATING|DESTRUCTIVE|ADMIN)");
        }

        OptionalText purposeText = textOptional(entry, "purpose");
        if (purposeText.isWrongType()) {
            return ParseResult.skip("purpose must be a string when present");
        }
        String purpose = purposeText.isPresent() ? purposeText.value() : null;

        OptionalText capabilityVersionText = textOptional(entry, "capabilityVersion");
        if (capabilityVersionText.isWrongType()) {
            return ParseResult.skip("capabilityVersion must be a string when present");
        }
        String capabilityVersion = capabilityVersionText.isPresent() ? capabilityVersionText.value() : null;
        if (capabilityVersion == null && entry.has("version") && !entry.get("version").isNull()) {
            // Conceptual §6.1 entry version; root file version remains separate.
            if (!entry.get("version").isTextual()) {
                return ParseResult.skip("version must be a string when present on a capability entry");
            }
            String v = entry.get("version").asText().trim();
            capabilityVersion = v.isEmpty() ? null : v;
        }

        boolean dryRunSupported = false;
        String dryRunParameter = null;
        if (entry.has("dryRun")) {
            JsonNode dry = entry.get("dryRun");
            if (dry == null || !dry.isObject()) {
                return ParseResult.skip("dryRun must be an object when present");
            }
            if (!dry.has("supported") || !dry.get("supported").isBoolean()) {
                return ParseResult.skip("dryRun.supported must be boolean");
            }
            dryRunSupported = dry.get("supported").asBoolean();
            OptionalText dryParam = textOptional(dry, "parameter");
            if (dryParam.isWrongType()) {
                return ParseResult.skip("dryRun.parameter must be a string when present");
            }
            if (dryRunSupported) {
                if (!dryParam.isPresent()) {
                    return ParseResult.skip("dryRun.parameter required when supported=true");
                }
                dryRunParameter = dryParam.value();
            } else if (dryParam.isPresent()) {
                dryRunParameter = dryParam.value();
            }
        }

        ServiceIdempotencyMode idempotencyMode = null;
        String idempotencyParameter = null;
        if (entry.has("idempotency")) {
            JsonNode idemp = entry.get("idempotency");
            if (idemp == null || !idemp.isObject()) {
                return ParseResult.skip("idempotency must be an object when present");
            }
            if (!idemp.has("mode") || !idemp.get("mode").isTextual()) {
                return ParseResult.skip("idempotency.mode required");
            }
            try {
                idempotencyMode = ServiceIdempotencyMode.valueOf(
                        idemp.get("mode").asText().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ParseResult.skip("invalid idempotency.mode");
            }
            OptionalText idempParam = textOptional(idemp, "parameter");
            if (idempParam.isWrongType()) {
                return ParseResult.skip("idempotency.parameter must be a string when present");
            }
            if (idempotencyMode == ServiceIdempotencyMode.CALLER_KEY && !idempParam.isPresent()) {
                return ParseResult.skip("idempotency.parameter required for CALLER_KEY");
            }
            if (idempotencyMode == ServiceIdempotencyMode.NONE && idemp.has("parameter")
                    && !idemp.get("parameter").isNull()) {
                // Present (any JSON type) is forbidden for NONE — wrong-type already skipped above.
                return ParseResult.skip("idempotency.parameter must be omitted when mode=NONE");
            }
            if (idempParam.isPresent()) {
                idempotencyParameter = idempParam.value();
            }
        }

        List<String> dataClassification = List.of();
        if (entry.has("dataClassification")) {
            JsonNode arr = entry.get("dataClassification");
            if (arr == null || !arr.isArray() || arr.size() == 0) {
                return ParseResult.skip("dataClassification must be a non-empty string array when present");
            }
            List<String> parsed = new ArrayList<>();
            for (JsonNode n : arr) {
                if (n == null || !n.isTextual() || n.asText().trim().isEmpty()) {
                    return ParseResult.skip("dataClassification entries must be non-empty strings");
                }
                parsed.add(n.asText().trim());
            }
            dataClassification = parsed;
        }

        ServiceCapabilityAdmission admission = null;
        if (entry.has("admission")) {
            if (!entry.get("admission").isTextual()) {
                return ParseResult.skip("admission must be a string");
            }
            try {
                admission = ServiceCapabilityAdmission.valueOf(
                        entry.get("admission").asText().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ParseResult.skip("invalid admission (expected OFF|NARROW|LAZY|DEFAULT)");
            }
        }

        boolean enabled = true;
        if (entry.has("enabled")) {
            if (!entry.get("enabled").isBoolean()) {
                return ParseResult.skip("enabled must be boolean when present");
            }
            enabled = entry.get("enabled").asBoolean();
        }

        String inputSchemaDigest = null;
        String inputProfileRef = null;
        if (entry.has("inputSemantics")) {
            JsonNode in = entry.get("inputSemantics");
            if (in == null || !in.isObject()) {
                return ParseResult.skip("inputSemantics must be an object when present");
            }
            OptionalText digest = textOptional(in, "schemaDigest");
            if (digest.isWrongType()) {
                return ParseResult.skip("inputSemantics.schemaDigest must be a string when present");
            }
            OptionalText profileRef = textOptional(in, "profileRef");
            if (profileRef.isWrongType()) {
                return ParseResult.skip("inputSemantics.profileRef must be a string when present");
            }
            if (digest.isPresent()) {
                inputSchemaDigest = digest.value();
            }
            if (profileRef.isPresent()) {
                inputProfileRef = profileRef.value();
            }
        }

        String outputSchemaDigest = null;
        String outputProfileRef = null;
        List<String> businessErrors = List.of();
        if (entry.has("outputSemantics")) {
            JsonNode out = entry.get("outputSemantics");
            if (out == null || !out.isObject()) {
                return ParseResult.skip("outputSemantics must be an object when present");
            }
            OptionalText digest = textOptional(out, "schemaDigest");
            if (digest.isWrongType()) {
                return ParseResult.skip("outputSemantics.schemaDigest must be a string when present");
            }
            OptionalText profileRef = textOptional(out, "profileRef");
            if (profileRef.isWrongType()) {
                return ParseResult.skip("outputSemantics.profileRef must be a string when present");
            }
            if (digest.isPresent()) {
                outputSchemaDigest = digest.value();
            }
            if (profileRef.isPresent()) {
                outputProfileRef = profileRef.value();
            }
            if (out.has("businessErrors")) {
                JsonNode errs = out.get("businessErrors");
                if (errs == null || !errs.isArray()) {
                    return ParseResult.skip("outputSemantics.businessErrors must be an array");
                }
                List<String> parsed = new ArrayList<>();
                for (JsonNode n : errs) {
                    if (n != null && n.isTextual() && !n.asText().trim().isEmpty()) {
                        parsed.add(n.asText().trim());
                    } else if (n != null && !n.isNull()) {
                        return ParseResult.skip("outputSemantics.businessErrors entries must be strings");
                    }
                }
                businessErrors = parsed;
            }
        }

        if (params != null) {
            if (dryRunSupported && !params.hasParameter(dryRunParameter)) {
                return ParseResult.skip("dryRun.parameter not on ServiceDefinition: " + dryRunParameter);
            }
            if (idempotencyMode == ServiceIdempotencyMode.CALLER_KEY
                    && !params.hasParameter(idempotencyParameter)) {
                return ParseResult.skip(
                        "idempotency.parameter not on ServiceDefinition: " + idempotencyParameter);
            }
            if (inputSchemaDigest != null && !inputSchemaDigest.isBlank()) {
                String live = params.inputShapeDigest();
                if (live != null && !live.isBlank() && !live.equalsIgnoreCase(inputSchemaDigest.trim())) {
                    return ParseResult.skip("SCHEMA_DRIFT: inputSemantics.schemaDigest mismatch");
                }
            }
        }

        ServiceCapabilityRuntimeState state = enabled
                ? ServiceCapabilityRuntimeState.ACTIVE
                : ServiceCapabilityRuntimeState.DISABLED;

        return ParseResult.ok(new ServiceCapabilityMetadata(
                purpose,
                capabilityVersion,
                risk,
                dryRunSupported,
                dryRunParameter,
                idempotencyMode,
                idempotencyParameter,
                dataClassification,
                admission,
                enabled,
                inputSchemaDigest,
                inputProfileRef,
                outputSchemaDigest,
                outputProfileRef,
                businessErrors,
                state));
    }

    static boolean declaresCapabilityFields(JsonNode entry) {
        return entry.has("risk")
                || entry.has("purpose")
                || entry.has("capabilityVersion")
                || entry.has("dryRun")
                || entry.has("idempotency")
                || entry.has("dataClassification")
                || entry.has("admission")
                || entry.has("inputSemantics")
                || entry.has("outputSemantics")
                || entry.has("enabled");
    }

    /**
     * Optional string field: absent/null/blank → {@link OptionalText.Kind#ABSENT}; non-textual
     * present → {@link OptionalText.Kind#WRONG_TYPE}; non-blank text → {@link OptionalText.Kind#PRESENT}.
     */
    static OptionalText textOptional(JsonNode o, String field) {
        if (o == null || !o.has(field) || o.get(field).isNull()) {
            return OptionalText.absent();
        }
        if (!o.get(field).isTextual()) {
            return OptionalText.wrongType();
        }
        String s = o.get(field).asText();
        if (s == null) {
            return OptionalText.absent();
        }
        s = s.trim();
        return s.isEmpty() ? OptionalText.absent() : OptionalText.present(s);
    }
}
