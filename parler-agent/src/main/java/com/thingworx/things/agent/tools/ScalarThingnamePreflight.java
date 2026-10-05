package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.entities.RootEntity;
import com.thingworx.entities.utils.EntityUtilities;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.Thing;
import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.PromptContextCacheSnapshot;
import com.thingworx.things.agent.ToolResultEgressGateway;
import com.thingworx.things.agent.taxonomy.ApplicationSemanticTaxonomySnapshot;
import com.thingworx.logging.LogUtilities;

import org.slf4j.Logger;

/**
 * Shared scalar {@code THINGNAME} gate for model-facing built-ins and refactored extended-tool preflight.
 * Uses visibility-aware {@link EntityUtilities#findEntity(String, com.thingworx.relationships.RelationshipTypes.ThingworxRelationshipTypes)}
 * for the normal path (same precedent as {@code discover_thing_members}).
 *
 * @see ExtendedToolThingnamePreflight
 */
public final class ScalarThingnamePreflight {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(ScalarThingnamePreflight.class);

    /**
     * When non-null, {@link #isModelVisibleThing(String)} returns this predicate's result for the trimmed name (unit
     * tests and narrow harnesses — avoids constructing a platform {@link Thing}). Cleared by
     * {@link #clearModelGateOverrideForTests()}.
     */
    static volatile Predicate<String> modelVisibleThingNamePredicateForTests;

    /**
     * When non-null, replaces {@link #resolveVisibleApplicationThing(String)} entirely (offline tests and narrow
     * harnesses). Return {@code null} when the supplied name must fail the model-facing gate.
     */
    static volatile Function<String, Thing> resolveVisibleThingOverrideForTests;

    /**
     * When non-null, overrides {@link #resolveThingRecoverableThisTurn()} (unit tests only; cleared by
     * {@link #clearModelGateOverrideForTests()}).
     */
    static volatile Boolean resolveThingRecoverableOverrideForTests;

    /**
     * When non-null, overrides the canonical Thing name string produced after a successful
     * {@link #gateApplicationThing} (narrow harnesses only). Cleared by {@link #clearModelGateOverrideForTests()}.
     *
     * <p>Runs only after {@link #resolveVisibleApplicationThing(String)} returns a non-null {@link Thing}. Offline JUnit
     * on this extension classpath cannot construct a platform {@link Thing} ({@code new Thing()} pulls additional
     * platform types); treat this hook as <strong>integration / Composer harness only</strong>, not as a substitute for
     * {@link #canonicalThingNameFromResolvedThing} unit tests.
     */
    static volatile Function<Thing, String> gateCanonicalThingNameOverrideForTests;

    private ScalarThingnamePreflight() {}

    /**
     * Outcome of {@link #gateApplicationThing(String, String)} for first-party built-ins: either non-null
     * {@link #errorJson} (return that UTF-8 JSON to the model) or a resolved visible {@link Thing} with
     * {@link #canonicalThingName} for platform calls and success payloads.
     */
    public static final class ApplicationThingGateOutcome {
        public final Thing thing;
        public final String canonicalThingName;
        public final String errorJson;

        private ApplicationThingGateOutcome(Thing thing, String canonicalThingName, String errorJson) {
            this.thing = thing;
            this.canonicalThingName = canonicalThingName;
            this.errorJson = errorJson;
        }

        /** {@code true} when {@link #errorJson} is non-null (preflight failed). */
        public boolean isError() {
            return errorJson != null;
        }
    }

    private static ApplicationThingGateOutcome gateOk(Thing thing, String canonicalThingName) {
        return new ApplicationThingGateOutcome(thing, canonicalThingName, null);
    }

    private static ApplicationThingGateOutcome gateErr(String errorJson) {
        return new ApplicationThingGateOutcome(null, null, errorJson);
    }

    /**
     * Single gate for built-in scalar Thing instance arguments: null, missing, or blank after trim →
     * {@code THINGNAME_VALUE_REQUIRED}; non-blank but not a visible Thing → {@code IDENTITY_RESOLUTION_REQUIRED} with
     * trimmed {@code suppliedValue}; on success returns the visible {@link Thing} and a canonical name (prefer
     * {@link Thing#getName()} when non-blank) for {@code AlertFunctions} / response echoes.
     */
    public static ApplicationThingGateOutcome gateApplicationThing(String parameterName, String rawThingName) {
        if (rawThingName == null) {
            return gateErr(thingNameValueRequiredJson(parameterName));
        }
        String trimmed = rawThingName.trim();
        if (trimmed.isEmpty()) {
            return gateErr(thingNameValueRequiredJson(parameterName));
        }
        Thing t = resolveVisibleApplicationThing(trimmed);
        if (t == null) {
            return gateErr(identityResolutionRequiredJson(parameterName, trimmed));
        }
        String canon;
        Function<Thing, String> cgo = gateCanonicalThingNameOverrideForTests;
        if (cgo != null) {
            String c = cgo.apply(t);
            canon = (c != null && !c.isBlank()) ? c.trim() : trimmed;
        } else {
            canon = canonicalThingNameFromResolvedThing(t, trimmed);
        }
        return gateOk(t, canon);
    }

    /**
     * List gate for {@code query_alert_summary} {@code thingNames[]}: per-name identity outcomes; zero resolves →
     * whole-call {@code IDENTITY_RESOLUTION_REQUIRED}; blank/missing array → {@code THINGNAME_VALUE_REQUIRED}.
     */
    public static ApplicationThingsListGateOutcome gateApplicationThings(String parameterName, JsonNode thingNamesNode) {
        if (thingNamesNode == null || thingNamesNode.isNull() || !thingNamesNode.isArray()) {
            return ApplicationThingsListGateOutcome.wholeCallError(thingNameValueRequiredJson(parameterName));
        }
        if (thingNamesNode.size() == 0) {
            return ApplicationThingsListGateOutcome.wholeCallError(thingNameValueRequiredJson(parameterName));
        }
        for (JsonNode el : thingNamesNode) {
            if (el == null || !el.isTextual()) {
                return ApplicationThingsListGateOutcome.wholeCallError(thingNameValueRequiredJson(parameterName));
            }
            String rawCheck = el.asText();
            if (rawCheck == null || rawCheck.trim().isEmpty()) {
                return ApplicationThingsListGateOutcome.wholeCallError(thingNameValueRequiredJson(parameterName));
            }
        }
        List<String> canonicalNames = new ArrayList<>();
        ArrayNode identityErrors = MAPPER.createArrayNode();
        for (JsonNode el : thingNamesNode) {
            String raw = el.asText();
            String trimmed = raw.trim();
            ApplicationThingGateOutcome g = gateApplicationThing(parameterName, raw);
            if (g.isError()) {
                // Predicate-only JUnit harness: accept visible names without a platform Thing handle.
                if (modelVisibleThingNamePredicateForTests != null
                        && modelVisibleThingNamePredicateForTests.test(trimmed)) {
                    canonicalNames.add(canonicalThingNameFromResolvedThing(null, trimmed));
                    continue;
                }
                try {
                    ObjectNode err = (ObjectNode) MAPPER.readTree(g.errorJson);
                    if (!err.has("thingName") && raw != null) {
                        err.put("thingName", raw.trim());
                    }
                    identityErrors.add(err);
                } catch (Exception ignored) {
                    ObjectNode err = MAPPER.createObjectNode();
                    err.put("thingName", raw != null ? raw.trim() : "");
                    err.put("code", "IDENTITY_RESOLUTION_REQUIRED");
                    identityErrors.add(err);
                }
            } else {
                canonicalNames.add(g.canonicalThingName);
            }
        }
        if (canonicalNames.isEmpty()) {
            String firstSupplied = firstSuppliedValue(thingNamesNode);
            try {
                ObjectNode o = (ObjectNode) MAPPER.readTree(
                        identityResolutionRequiredJson(parameterName, firstSupplied != null ? firstSupplied : ""));
                if (identityErrors.size() > 0) {
                    o.set("identityErrors", identityErrors);
                }
                if (thingNamesNode.size() >= 2) {
                    o.put("resultKind", ToolResultEgressGateway.RESULT_KIND_ALERT_SUMMARY_MULTI);
                    o.put("thingsRequested", thingNamesNode.size());
                }
                return ApplicationThingsListGateOutcome.wholeCallError(MAPPER.writeValueAsString(o));
            } catch (Exception e) {
                return ApplicationThingsListGateOutcome.wholeCallError(
                        identityResolutionRequiredJson(parameterName, firstSupplied != null ? firstSupplied : ""));
            }
        }
        return ApplicationThingsListGateOutcome.partialSuccess(canonicalNames, identityErrors);
    }

    private static String firstSuppliedValue(JsonNode thingNamesNode) {
        for (JsonNode el : thingNamesNode) {
            if (el != null && el.isTextual()) {
                String t = el.asText().trim();
                if (!t.isEmpty()) {
                    return t;
                }
            }
        }
        return null;
    }

    /**
     * Outcome of {@link #gateApplicationThings(String, JsonNode)}.
     */
    public static final class ApplicationThingsListGateOutcome {
        public final List<String> canonicalThingNames;
        public final ArrayNode identityErrors;
        public final String wholeCallErrorJson;

        private ApplicationThingsListGateOutcome(List<String> canonicalThingNames, ArrayNode identityErrors,
                String wholeCallErrorJson) {
            this.canonicalThingNames = canonicalThingNames != null ? canonicalThingNames : List.of();
            this.identityErrors = identityErrors;
            this.wholeCallErrorJson = wholeCallErrorJson;
        }

        public boolean isWholeCallError() {
            return wholeCallErrorJson != null;
        }

        static ApplicationThingsListGateOutcome wholeCallError(String errorJson) {
            return new ApplicationThingsListGateOutcome(null, null, errorJson);
        }

        static ApplicationThingsListGateOutcome partialSuccess(List<String> names, ArrayNode identityErrors) {
            return new ApplicationThingsListGateOutcome(names, identityErrors, null);
        }
    }

    /**
     * Canonical ThingWorx name for a resolved visible {@link Thing}: non-blank {@link Thing#getName()} when
     * available, otherwise {@code trimmedFallback} (already trimmed).
     */
    public static String canonicalThingNameFromResolvedThing(Thing thing, String trimmedFallback) {
        if (trimmedFallback == null) {
            return "";
        }
        if (thing == null) {
            return trimmedFallback;
        }
        try {
            String tn = thing.getName();
            if (tn != null && !tn.isBlank()) {
                return tn.trim();
            }
        } catch (Exception ignored) {
            // keep fallback
        }
        return trimmedFallback;
    }

    static void clearModelGateOverrideForTests() {
        modelVisibleThingNamePredicateForTests = null;
        resolveVisibleThingOverrideForTests = null;
        resolveThingRecoverableOverrideForTests = null;
        gateCanonicalThingNameOverrideForTests = null;
    }

    /**
     * Whether {@code thingName} is accepted as a canonical, model-visible Thing name (no JSON allocation).
     */
    public static boolean isModelVisibleThing(String thingName) {
        if (thingName == null || thingName.isBlank()) {
            return false;
        }
        String trimmed = thingName.trim();
        Predicate<String> vis = modelVisibleThingNamePredicateForTests;
        if (vis != null) {
            return vis.test(trimmed);
        }
        return resolveVisibleApplicationThing(thingName) != null;
    }

    /**
     * Resolves a visible application {@link Thing} for property-style tools, or {@code null} when the name is not
     * an accessible Thing for the effective principal.
     */
    public static Thing resolveVisibleApplicationThing(String thingName) {
        if (thingName == null || thingName.isBlank()) {
            return null;
        }
        String trimmed = thingName.trim();
        Predicate<String> vis = modelVisibleThingNamePredicateForTests;
        if (vis != null) {
            if (!vis.test(trimmed)) {
                return null;
            }
            Function<String, Thing> overrideWhenPred = resolveVisibleThingOverrideForTests;
            if (overrideWhenPred != null) {
                return overrideWhenPred.apply(trimmed);
            }
            // Predicate-only test harness: visible name without a platform Thing handle — do not call findEntity.
            return null;
        }
        Function<String, Thing> override = resolveVisibleThingOverrideForTests;
        if (override != null) {
            return override.apply(trimmed);
        }
        try {
            RootEntity entity = EntityUtilities.findEntity(trimmed, RelationshipTypes.ThingworxRelationshipTypes.Thing);
            return entity instanceof Thing ? (Thing) entity : null;
        } catch (Exception e) {
            LOG.info("ScalarThingnamePreflight findEntity: {}", e.getMessage());
            return null;
        }
    }

    /**
     * {@code true} when {@code resolve_thing} can recover this turn (loaded semantic taxonomy with at least one v3
     * identity rule).
     */
    static boolean resolveThingRecoverableThisTurn() {
        Boolean override = resolveThingRecoverableOverrideForTests;
        if (override != null) {
            return override.booleanValue();
        }
        AgentThing agent = AgentToolContext.getAgentThing();
        if (agent == null) {
            return false;
        }
        PromptContextCacheSnapshot snap = agent.getPromptContextSnapshot();
        if (snap == null) {
            return false;
        }
        ApplicationSemanticTaxonomySnapshot sem = snap.getApplicationSemanticTaxonomy();
        if (sem == null || !sem.isLoaded()) {
            return false;
        }
        return !sem.thingIdentityRules().isEmpty();
    }

    public static String thingNameValueRequiredJson(String parameterName) {
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", "THINGNAME_VALUE_REQUIRED");
            o.put("message", "Parameter " + parameterName + " requires a non-blank canonical ThingWorx Thing name.");
            o.put("parameterName", parameterName);
            o.put("expectedBaseType", "THINGNAME");
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"THINGNAME_VALUE_REQUIRED\",\"message\":\"serialization failed\"}";
        }
    }

    public static String identityResolutionRequiredJson(String parameterName, String suppliedValue) {
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", "IDENTITY_RESOLUTION_REQUIRED");
            boolean allowRecoveryHint = resolveThingRecoverableThisTurn();
            if (allowRecoveryHint) {
                o.put("message",
                        "Parameter " + parameterName + " requires a canonical ThingName. Resolve the supplied value "
                                + "with resolve_thing (v3 identity taxonomy), then retry this tool with the canonical Thing name.");
                ObjectNode rh = MAPPER.createObjectNode();
                rh.put("tool", "resolve_thing");
                rh.put("argument", "text");
                String ak = AgentToolContext.getLastResolvedAssetTypeKey();
                if (ak != null && !ak.isBlank()) {
                    rh.put("assetTypeKey", ak);
                }
                o.set("recoveryHint", rh);
            } else {
                o.put("message",
                        "Parameter " + parameterName + " requires a canonical ThingName. resolve_thing is not "
                                + "available for this agent turn (semantic taxonomy missing, unavailable, or no v3 "
                                + "identity rules loaded); configure /taxonomies/identity-types.json (root JSON array) "
                                + "or supply an exact ThingWorx Thing name.");
            }
            o.put("parameterName", parameterName);
            o.put("expectedBaseType", "THINGNAME");
            o.put("suppliedValue", suppliedValue);
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"IDENTITY_RESOLUTION_REQUIRED\",\"message\":\"serialization failed\"}";
        }
    }
}
