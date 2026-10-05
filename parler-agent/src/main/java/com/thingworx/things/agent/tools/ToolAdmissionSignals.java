package com.thingworx.things.agent.tools;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The deterministic per-turn signals that drive {@code narrow} tool admission
 * (docs/operations/tool-schema-admission-control.md §2.4). Every field is an auditable fact read from host context,
 * loaded registries, or explicit invocation — there is no LLM classifier. Immutable value object (Java 11; no record).
 */
public final class ToolAdmissionSignals {

    private final String hostContextKey;
    private final boolean slashActive;
    private final boolean documentScopeActive;
    private final boolean loadedSkills;
    private final boolean loadedPlaybook;
    private final boolean taxonomyReady;
    private final Set<String> requiredTools;
    private final Set<ToolBucket> requiredBuckets;

    public ToolAdmissionSignals(
            String hostContextKey,
            boolean slashActive,
            boolean documentScopeActive,
            boolean loadedSkills,
            boolean loadedPlaybook,
            boolean taxonomyReady,
            Set<String> requiredTools,
            Set<ToolBucket> requiredBuckets) {
        this.hostContextKey = hostContextKey;
        this.slashActive = slashActive;
        this.documentScopeActive = documentScopeActive;
        this.loadedSkills = loadedSkills;
        this.loadedPlaybook = loadedPlaybook;
        this.taxonomyReady = taxonomyReady;
        this.requiredTools = requiredTools != null && !requiredTools.isEmpty()
                ? Collections.unmodifiableSet(new LinkedHashSet<>(requiredTools))
                : Collections.emptySet();
        this.requiredBuckets = requiredBuckets != null && !requiredBuckets.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.copyOf(requiredBuckets))
                : Collections.emptySet();
    }

    /** Host-context registry key for the embedding surface (e.g. a Mashup card), or {@code null} when absent. */
    public String hostContextKey() {
        return hostContextKey;
    }

    /** A {@code /skill} or {@code /playbook} slash command was used this turn. */
    public boolean slashActive() {
        return slashActive;
    }

    /** A document scope was injected this turn (document-knowledge turn). */
    public boolean documentScopeActive() {
        return documentScopeActive;
    }

    /** Model-facing skills are loaded (so {@code get_agent_skill} is part of the core entry set). */
    public boolean loadedSkills() {
        return loadedSkills;
    }

    /** A playbook registry is loaded (so {@code start_playbook} is part of the core entry set). */
    public boolean loadedPlaybook() {
        return loadedPlaybook;
    }

    /** Application taxonomy is configured/loaded (so {@code resolve_asset_type}/{@code list_asset_types} are core). */
    public boolean taxonomyReady() {
        return taxonomyReady;
    }

    /** Host-context-declared tools that must always be advertised, regardless of bucket selection (§2.5). */
    public Set<String> requiredTools() {
        return requiredTools;
    }

    /** Host-context-declared buckets that must always be admitted, regardless of signal gating (§2.5). */
    public Set<ToolBucket> requiredBuckets() {
        return requiredBuckets;
    }

    /** True when the host-context key contains {@code needle} (case-insensitive) — a table-listed lexical hint. */
    boolean hostKeyContains(String needle) {
        return hostContextKey != null
                && hostContextKey.toLowerCase(java.util.Locale.ROOT).contains(needle);
    }

    static Set<ToolBucket> parseRequiredBuckets(List<String> raw) {
        Set<ToolBucket> out = EnumSet.noneOf(ToolBucket.class);
        if (raw != null) {
            for (String s : raw) {
                ToolBucket b = ToolBuckets.parseBucket(s);
                if (b != null) {
                    out.add(b);
                }
            }
        }
        return out;
    }
}
