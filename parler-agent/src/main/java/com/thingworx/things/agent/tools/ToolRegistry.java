package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;

import org.slf4j.Logger;
import com.thingworx.logging.LogUtilities;

/**
 * Registry that maps tool names to their definitions and executors.
 * The agent loop queries this registry to:
 *   1. Get tool definitions to send to the LLM
 *   2. Execute tool calls returned by the LLM
 */
public class ToolRegistry {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(ToolRegistry.class);

    private final Map<String, ToolDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, ToolExecutor> executors = new LinkedHashMap<>();
    /** Executor-only names (no {@link ToolDefinition}) mapped for replay / tests — not advertised to the LLM. */
    private final Set<String> executorOnlyAliases = new LinkedHashSet<>();
    /** Parallel map: executor alias name → canonical registered tool name (for operator diagnostics). */
    private final Map<String, String> executorAliasToCanonical = new LinkedHashMap<>();

    public void register(ToolDefinition definition, ToolExecutor executor) {
        String n = definition.getName();
        if (executorOnlyAliases.contains(n) && !definitions.containsKey(n)) {
            throw new IllegalStateException(
                    "Name is registered as executor-only; unregister before adding a ToolDefinition: " + n);
        }
        definitions.put(n, definition);
        executors.put(n, executor);
        LOG.info("ToolRegistry registered: {}", n);
    }

    /**
     * Registers an additional tool name that executes the same handler as an existing built-in without adding a
     * second {@link ToolDefinition} (so the name is not merged into the LLM tool list).
     *
     * @throws IllegalStateException when {@code canonicalToolName} is not registered
     */
    public void registerExecutorAlias(String alias, String canonicalToolName) {
        ToolExecutor canonical = executors.get(canonicalToolName);
        if (canonical == null) {
            throw new IllegalStateException("Unknown canonical tool for executor alias: " + canonicalToolName);
        }
        executors.put(alias, canonical);
        executorOnlyAliases.add(alias);
        executorAliasToCanonical.put(alias, canonicalToolName);
        LOG.info("ToolRegistry registered executor alias: {} -> {}", alias, canonicalToolName);
    }

    /**
     * Registers a tool name that is executable via {@link #executeTool} but has no {@link ToolDefinition} — the name is
     * omitted from {@link #getAllDefinitions()} (not merged into the LLM tool list). Intended for persisted replay /
     * HITL rows that still call a historic function name after that name is removed from the normal schema surface.
     * <p>
     * Unlike {@link #registerExecutorAlias}, this does not require a separate canonical registered tool: the executor
     * is bound directly to {@code toolName}.
     *
     * @throws IllegalStateException when {@code toolName} is already registered (definition or executor)
     */
    public void registerExecutorOnly(String toolName, ToolExecutor executor) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must be non-blank");
        }
        if (definitions.containsKey(toolName)) {
            throw new IllegalStateException("Tool already has a definition (use register / unregister first): "
                    + toolName);
        }
        if (executors.containsKey(toolName)) {
            throw new IllegalStateException("Tool name already registered for execution: " + toolName);
        }
        executors.put(toolName, executor);
        executorOnlyAliases.add(toolName);
        LOG.info("ToolRegistry registered executor-only tool: {}", toolName);
    }

    public Set<String> getExecutorOnlyAliases() {
        return Collections.unmodifiableSet(executorOnlyAliases);
    }

    /**
     * Executor-only tool names mapped to their canonical built-in name (replay / historic rows). Keys are not LLM
     * schema tools.
     */
    public Map<String, String> getExecutorAliasCanonicalTargets() {
        return Collections.unmodifiableMap(new TreeMap<>(executorAliasToCanonical));
    }

    /**
     * {@linkplain #registerExecutorOnly Direct executor-only} tool names: in {@link #getExecutorOnlyAliases()} but
     * not keys of {@link #getExecutorAliasCanonicalTargets()} (aliases stay in {@code executorAliases} only). Sorted
     * for stable diagnostics (e.g. {@code GetAgentRuntimeSnapshot}).
     */
    public List<String> getDirectExecutorOnlyToolNames() {
        Set<String> aliasKeys = executorAliasToCanonical.keySet();
        return executorOnlyAliases.stream()
                .filter(n -> !aliasKeys.contains(n))
                .sorted()
                .toList();
    }

    public void unregister(String toolName) {
        definitions.remove(toolName);
        executors.remove(toolName);
        executorOnlyAliases.remove(toolName);
        executorAliasToCanonical.remove(toolName);
    }

    public List<ToolDefinition> getAllDefinitions() {
        return new ArrayList<>(definitions.values());
    }

    public String executeTool(ToolCall toolCall) throws Exception {
        ToolExecutor executor = executors.get(toolCall.getFunctionName());
        if (executor == null) {
            throw new IllegalArgumentException("Unknown tool: " + toolCall.getFunctionName());
        }
        String argsPreview = ProtectedValuePolicy.redactPersistedToolArgumentsJson(toolCall.getArguments(),
                toolCall.getFunctionName());
        if (argsPreview != null && argsPreview.length() > 400) {
            argsPreview = argsPreview.substring(0, 400) + "...";
        }
        LOG.info("Executing tool: {} (id={}) argsPreview={}", toolCall.getFunctionName(), toolCall.getId(), argsPreview);
        long t0 = System.currentTimeMillis();
        try {
            String out = executor.execute(toolCall);
            LOG.info("Tool finished: {} in {}ms (outputLength={})",
                    toolCall.getFunctionName(), System.currentTimeMillis() - t0,
                    out != null ? out.length() : 0);
            return out;
        } catch (Exception e) {
            LOG.warn("Tool failed: {} after {}ms — {}", toolCall.getFunctionName(),
                    System.currentTimeMillis() - t0, e.getMessage());
            throw e;
        }
    }

    public boolean hasTool(String name) {
        return definitions.containsKey(name);
    }

    public int size() {
        return definitions.size();
    }
}
