package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;

/**
 * Executor for the {@code load_tool_schemas(names[])} meta-tool used by {@code lazy} admission
 * (docs/operations/tool-schema-admission-control.md M3). Returns the full input schemas for the requested tools and
 * registers their names so subsequent rounds advertise them natively. Registered as executor-only (dispatchable but
 * not advertised) — the {@code lazy} round filter advertises the meta-tool with its per-turn catalog.
 */
public final class LoadToolSchemasExecutor {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** B9: success note steers to the current turn, not a later user turn. */
    static final String SUCCESS_NOTE =
            "These tools are now available — call them directly now / in the next step of this turn.";

    private LoadToolSchemasExecutor() {}

    /**
     * B9: description prefix for the lazy-admission {@code load_tool_schemas} meta-tool
     * (catalog text is appended by the caller).
     */
    public static String metaToolDescriptionPrefix() {
        return "Load the full input schemas for additional tools you need this turn. Only the core "
                + "tools are attached right now; the tools listed below are available on demand. Call "
                + "load_tool_schemas with the exact names you need, then call those tools now / in the next step of "
                + "this turn.\n\n"
                + "Available tools:\n";
    }

    public static String execute(ToolCall toolCall) throws Exception {
        AgentThing agent = AgentToolContext.getAgentThing();
        if (agent == null) {
            return errorEnvelope("no_agent_context", "load_tool_schemas requires an active agent turn");
        }
        List<String> requested = parseNames(toolCall != null ? toolCall.getArguments() : null);
        if (requested.isEmpty()) {
            return errorEnvelope("no_names", "Provide names[]: the exact tool names to load from the catalog");
        }
        Map<String, ToolDefinition> byName = indexByName(agent.snapshotMergedModelFacingTools());
        EnvelopeResult result = buildEnvelope(requested, byName);
        if (!result.resolvedNames.isEmpty()) {
            String turnKey = FetchCachedReplayGuard.resolveCurrentTurnKey();
            Set<String> registered = LazyToolRegistrationRegistry.acquireForTurn(turnKey);
            registered.addAll(result.resolvedNames);
        }
        return result.json;
    }

    /** @visibleForTesting Pure envelope builder: resolves requested names against the available tools. */
    public static EnvelopeResult buildEnvelope(List<String> requested, Map<String, ToolDefinition> byName) {
        ObjectNode root = JSON.createObjectNode();
        root.put("status", "ok");
        ArrayNode loaded = root.putArray("loaded");
        ArrayNode notFound = root.putArray("not_found");
        List<String> resolved = new ArrayList<>();
        for (String name : requested) {
            ToolDefinition def = name != null ? byName.get(name) : null;
            if (def == null) {
                notFound.add(name != null ? name : "");
                continue;
            }
            ObjectNode entry = loaded.addObject();
            entry.put("name", def.getName());
            entry.put("description", def.getDescription() != null ? def.getDescription() : "");
            Map<String, Object> schema = def.getParametersSchema();
            entry.set("input_schema", JSON.valueToTree(schema != null ? schema : new LinkedHashMap<>()));
            resolved.add(def.getName());
        }
        root.put("note", SUCCESS_NOTE);
        return new EnvelopeResult(root.toString(), resolved);
    }

    static List<String> parseNames(String argumentsJson) {
        List<String> out = new ArrayList<>();
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return out;
        }
        try {
            JsonNode root = JSON.readTree(argumentsJson);
            JsonNode names = root.get("names");
            if (names != null && names.isArray()) {
                for (JsonNode n : names) {
                    String s = n.asText("").trim();
                    if (!s.isEmpty()) {
                        out.add(s);
                    }
                }
            }
        } catch (Exception e) {
            // Malformed arguments -> treated as empty request (caller returns a clear error envelope).
        }
        return out;
    }

    private static Map<String, ToolDefinition> indexByName(List<ToolDefinition> defs) {
        Map<String, ToolDefinition> m = new LinkedHashMap<>();
        if (defs != null) {
            for (ToolDefinition d : defs) {
                if (d != null && d.getName() != null) {
                    m.put(d.getName(), d);
                }
            }
        }
        return m;
    }

    private static String errorEnvelope(String code, String message) {
        ObjectNode root = JSON.createObjectNode();
        root.put("status", "error");
        root.put("code", code);
        root.put("message", message);
        return root.toString();
    }

    /** @visibleForTesting */
    public static final class EnvelopeResult {
        public final String json;
        public final List<String> resolvedNames;

        EnvelopeResult(String json, List<String> resolvedNames) {
            this.json = json;
            this.resolvedNames = resolvedNames;
        }
    }
}
