package com.thingworx.things.agent.tools;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.logging.LogUtilities;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.AgentToolContext.DocumentScopeOrigin;

/**
 * Derives the default document scope from the unit a search names in {@code assetContext.thingName}, for the current
 * execution segment only. Nothing is stored and everything fails open: a rejected name leaves the search as it was,
 * apart from one warning whose wording follows the validation stage that failed.
 */
final class SearchUnitScope {

    private static final String WARNING_CODE = "UNIT_SCOPE_NOT_ESTABLISHED";
    private static final String NAME_PARAMETER = "assetContext.thingName";
    private static final int MAX_NAME_CHARS = 256;
    private static final int MAX_ECHOED_CHARS = 80;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(SearchUnitScope.class);

    private SearchUnitScope() {}

    /** The warning for a rejected name, if any. An established scope lives in {@link AgentToolContext}. */
    static final class Outcome {
        private static final Outcome SILENT = new Outcome(null);

        private final String warningMessage;

        private Outcome(String warningMessage) {
            this.warningMessage = warningMessage;
        }

        Optional<String> warningMessage() {
            return Optional.ofNullable(warningMessage);
        }
    }

    static Outcome derive(ToolCall call, Function<String, Optional<String>> canonicalName,
            Function<String, Optional<ResolvedDocumentSet>> resolver) {
        try {
            return eligibleName(call).map(name -> validate(name, canonicalName, resolver)).orElse(Outcome.SILENT);
        } catch (RuntimeException e) {
            LOG.warn("Search unit document scope skipped: {}", e.getMessage());
            return Outcome.SILENT;
        }
    }

    /** The trimmed name of an eligible search: no {@code documentIds} field, no injected scope, a usable text name. */
    private static Optional<String> eligibleName(ToolCall call) {
        List<String> injected = AgentToolContext.getInjectedDocumentScopeIds();
        if (injected != null && !injected.isEmpty()) {
            return Optional.empty();
        }
        JsonNode args = parsedArguments(call.getArguments());
        if (args == null || !args.isObject() || args.has("documentIds")) {
            return Optional.empty();
        }
        JsonNode name = args.path("assetContext").path("thingName");
        return name.isTextual() ? Optional.of(name.asText().trim()).filter(text -> !text.isEmpty()) : Optional.empty();
    }

    private static JsonNode parsedArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(arguments);
        } catch (Exception unparsable) {
            return null;
        }
    }

    private static Outcome validate(String name, Function<String, Optional<String>> canonicalName,
            Function<String, Optional<ResolvedDocumentSet>> resolver) {
        String echoed = echoed(name);
        Optional<String> canonical = hasNameShape(name)
                ? decided(() -> canonicalName.apply(name))
                : Optional.empty();
        if (canonical.isEmpty()) {
            LOG.info("Search unit document scope: thing={} rejected at the identity stage", echoed);
            return new Outcome(identityStageMessage(echoed));
        }
        Optional<ResolvedDocumentSet> resolved = decided(() -> resolver.apply(canonical.get()))
                .filter(set -> !set.documentIds().isEmpty());
        if (resolved.isEmpty()) {
            LOG.info("Search unit document scope: thing={} documents=0 source=empty", echoed);
            return new Outcome(documentStageMessage(echoed));
        }
        ResolvedDocumentSet scope = resolved.get();
        AgentToolContext.setInjectedDocumentScope(scope.documentIds(), scope.source().wire(),
                DocumentScopeOrigin.SEARCH_ASSET_CONTEXT);
        LOG.info("Search unit document scope: thing={} documents={} source={}", echoed, scope.documentIds().size(),
                scope.source().wire());
        return Outcome.SILENT;
    }

    private static boolean hasNameShape(String name) {
        return name.length() <= MAX_NAME_CHARS && name.chars().noneMatch(Character::isISOControl);
    }

    private static <T> Optional<T> decided(Supplier<Optional<T>> decision) {
        try {
            return decision.get();
        } catch (RuntimeException e) {
            LOG.warn("Search unit document scope decision failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String identityStageMessage(String echoed) {
        String recovery = ScalarThingnamePreflight.resolveThingRecoverableThisTurn()
                ? "If the user named a unit, call resolve_thing with the user's text and search again; otherwise ignore this."
                : "Use the exact Thing name if the user named a unit; otherwise ignore this.";
        return NAME_PARAMETER + " '" + echoed + "' did not establish a document scope: it is not the exact name of a "
                + "visible unit. " + recovery;
    }

    private static String documentStageMessage(String echoed) {
        return NAME_PARAMETER + " '" + echoed + "' is a visible unit, but no document scope is available for it (no "
                + "documents for it, or document knowledge unavailable). This search is not limited to that unit's "
                + "documents. Looking the unit up again will not change this.";
    }

    private static String echoed(String name) {
        StringBuilder text = new StringBuilder(name);
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) {
                text.setCharAt(i, ' ');
            }
        }
        if (text.length() <= MAX_ECHOED_CHARS) {
            return text.toString();
        }
        boolean splitsPair = Character.isHighSurrogate(text.charAt(MAX_ECHOED_CHARS - 1));
        return text.substring(0, splitsPair ? MAX_ECHOED_CHARS - 1 : MAX_ECHOED_CHARS);
    }

    /** Adds the outcome's warning once; any result this cannot read is returned as it came. */
    static String appendWarning(String resultJson, Outcome outcome) {
        if (outcome.warningMessage().isEmpty()) {
            return resultJson;
        }
        try {
            JsonNode root = MAPPER.readTree(resultJson);
            if (root == null || !root.isObject()) {
                return resultJson;
            }
            JsonNode warnings = root.get("warnings");
            ArrayNode entries = warnings == null ? ((ObjectNode) root).putArray("warnings")
                    : warnings.isArray() ? (ArrayNode) warnings : null;
            if (entries == null || hasWarning(entries)) {
                return resultJson;
            }
            entries.addObject().put("code", WARNING_CODE).put("message", outcome.warningMessage().get());
            return MAPPER.writeValueAsString(root);
        } catch (Exception unreadable) {
            return resultJson;
        }
    }

    private static boolean hasWarning(ArrayNode entries) {
        for (JsonNode entry : entries) {
            if (WARNING_CODE.equals(entry.path("code").asText())) {
                return true;
            }
        }
        return false;
    }

    /** The visibility gate of the scalar Thing tools as a decision: the canonical Thing name, or empty. */
    static Optional<String> visibleCanonicalName(String name) {
        ScalarThingnamePreflight.ApplicationThingGateOutcome gate =
                ScalarThingnamePreflight.gateApplicationThing(NAME_PARAMETER, name);
        return gate.isError() ? Optional.empty() : Optional.of(gate.canonicalThingName);
    }
}
