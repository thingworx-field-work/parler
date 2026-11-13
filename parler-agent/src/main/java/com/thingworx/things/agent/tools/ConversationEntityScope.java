package com.thingworx.things.agent.tools;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.logging.LogUtilities;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.AgentToolContext.DocumentScopeOrigin;

/**
 * Derives the default document scope from the unit the conversation resolved. Nothing is stored: the newest
 * {@code resolve_thing} row decides on every turn, with no fallback to an older row. Everything fails open, so a
 * problem here leaves the turn unscoped and never changes a tool result.
 */
public final class ConversationEntityScope {

    private static final String RESOLVE_THING = "resolve_thing";
    private static final String SINGLE_MATCH_KIND = "THING_RESOLVED_INLINE";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(ConversationEntityScope.class);

    private ConversationEntityScope() {}

    /** Turn start and approval continuation: applies only when no scope was injected for this turn. */
    public static void injectFromHistory(List<ChatMessage> messages,
            Function<String, Optional<ResolvedDocumentSet>> resolver) {
        try {
            List<String> injected = AgentToolContext.getInjectedDocumentScopeIds();
            if (injected != null && !injected.isEmpty()) {
                return;
            }
            entityFromHistory(messages).flatMap(name -> resolve(name, resolver)).ifPresent(
                    ConversationEntityScope::storeConversationScope);
        } catch (RuntimeException e) {
            LOG.warn("Conversation entity document scope skipped: {}", e.getMessage());
        }
    }

    /** In-turn update after an executed Java built-in {@code resolve_thing}; a host-origin scope is never touched. */
    public static void afterResolveThing(ToolCall call, String result,
            Function<String, Optional<ResolvedDocumentSet>> resolver) {
        if (call == null || !RESOLVE_THING.equals(call.getFunctionName())
                || AgentToolContext.getInjectedDocumentScopeOrigin() == DocumentScopeOrigin.HOST_CONTEXT) {
            return;
        }
        Optional<ResolvedDocumentSet> scope = Optional.empty();
        try {
            scope = entityFromResult(result).flatMap(name -> resolve(name, resolver));
        } catch (RuntimeException e) {
            LOG.warn("Conversation entity document scope cleared: {}", e.getMessage());
        }
        if (scope.isPresent()) {
            storeConversationScope(scope.get());
        } else {
            AgentToolContext.setInjectedDocumentScope(null, null);
        }
    }

    /** Selects the newest {@code resolve_thing} row, then validates that row alone. */
    static Optional<String> entityFromHistory(List<ChatMessage> messages) {
        if (messages == null) {
            return Optional.empty();
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message != null && message.getRole() == ChatMessage.Role.TOOL
                    && RESOLVE_THING.equals(message.getExecutedToolName())) {
                return entityFromResult(message.getContent());
            }
        }
        return Optional.empty();
    }

    /** The Thing name of a single-match success result, or empty for any other result. */
    static Optional<String> entityFromResult(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(resultJson);
        } catch (Exception unparsable) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()
                || !"success".equals(root.path("status").asText())
                || !SINGLE_MATCH_KIND.equals(root.path("resultKind").asText())
                || !root.path("totalCount").isIntegralNumber() || root.path("totalCount").asInt() != 1) {
            return Optional.empty();
        }
        JsonNode matches = root.path("matches");
        if (!matches.isArray() || matches.size() != 1) {
            return Optional.empty();
        }
        JsonNode name = matches.get(0).path("name");
        return name.isTextual() && !name.asText().isBlank() ? Optional.of(name.asText()) : Optional.empty();
    }

    private static Optional<ResolvedDocumentSet> resolve(String name,
            Function<String, Optional<ResolvedDocumentSet>> resolver) {
        Optional<ResolvedDocumentSet> resolved = resolver.apply(name)
                .filter(set -> !set.documentIds().isEmpty());
        LOG.info("Conversation entity document scope: thing={} documents={} source={}", name,
                resolved.map(set -> set.documentIds().size()).orElse(0),
                resolved.map(set -> set.source().wire()).orElse("empty"));
        return resolved;
    }

    private static void storeConversationScope(ResolvedDocumentSet scope) {
        AgentToolContext.setInjectedDocumentScope(scope.documentIds(), scope.source().wire(),
                DocumentScopeOrigin.CONVERSATION);
    }
}
