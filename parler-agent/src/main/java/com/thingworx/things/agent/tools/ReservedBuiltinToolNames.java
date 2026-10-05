package com.thingworx.things.agent.tools;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import com.thingworx.things.agent.llm.ToolDefinition;

/**
 * Sole production authority for built-in reserved tool names used in admission / authoring /
 * Playbook document validation (U1B E2).
 *
 * <p>The set is registry definitions + executor-only aliases + always-reserved dynamic names.
 * Dynamic names such as {@link #START_PLAYBOOK} are reserved even when their optional provider
 * is disabled or absent. Comparison is exact case-sensitive {@link Set#contains} equality.
 */
public final class ReservedBuiltinToolNames {

    /** Dynamically supplied playbook entry point — always reserved. */
    public static final String START_PLAYBOOK = "start_playbook";

    private ReservedBuiltinToolNames() {}

    /**
     * Dynamic names that must be reserved whether or not the optional provider is loaded during
     * validation.
     */
    public static Set<String> alwaysReservedDynamicNames() {
        return Set.of(START_PLAYBOOK);
    }

    /**
     * Full reserved-name set for the given registry: definitions, executor-only aliases, and
     * {@link #alwaysReservedDynamicNames()}.
     */
    public static Set<String> fromRegistry(ToolRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        Set<String> names = new HashSet<>();
        for (ToolDefinition td : registry.getAllDefinitions()) {
            names.add(td.getName());
        }
        names.addAll(registry.getExecutorOnlyAliases());
        names.addAll(alwaysReservedDynamicNames());
        return Collections.unmodifiableSet(names);
    }
}
