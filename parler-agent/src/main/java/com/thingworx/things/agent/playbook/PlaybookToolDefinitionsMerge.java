package com.thingworx.things.agent.playbook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.thingworx.things.agent.configrepo.ExtendedToolDefinition;
import com.thingworx.things.agent.configrepo.ExtendedToolRegistrySnapshot;
import com.thingworx.things.agent.configrepo.ServiceCapabilityRuntimePolicy;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.tools.ToolRegistry;

/** Merges built-in and extended {@link ToolDefinition} lists for playbook catalog validation. Includes extended tools even when {@code executorOnly} (those stay off the merged LLM list only). */
public final class PlaybookToolDefinitionsMerge {

    private PlaybookToolDefinitionsMerge() {}

    public static List<ToolDefinition> merge(ToolRegistry builtIn, ExtendedToolRegistrySnapshot ext) {
        List<ToolDefinition> out = new ArrayList<>(builtIn.getAllDefinitions());
        if (ext == null || ext.isFileInvalid()) {
            appendSyntheticExecutorAliasDefinitions(builtIn, out);
            return out;
        }
        for (ExtendedToolDefinition d : ext.allByName().values()) {
            // executorOnly affects merged LLM tool list only (see legacy-discovery-executor-only.md §7);
            // playbook catalog validation still needs ToolDefinitions for playbookSafe / static allowlists.
            // SPR-2: capability policy may force playbookSafe=false (DISABLED / MUTATING / DESTRUCTIVE / ADMIN).
            ToolDefinition td = d.toolDefinition();
            if (td != null && !ServiceCapabilityRuntimePolicy.isPlaybookEligible(d)) {
                td = new ToolDefinition(td.getName(), td.getDescription(), td.getParametersSchema(), false);
            }
            if (td != null) {
                out.add(td);
            }
        }
        appendSyntheticExecutorAliasDefinitions(builtIn, out);
        return out;
    }

    /**
     * Executor-only aliases (e.g. historic property-history names) have no {@link ToolDefinition} in
     * {@link ToolRegistry#getAllDefinitions()} but must validate in static Playbooks when their canonical tool is
     * {@linkplain ToolDefinition#isPlaybookSafe() playbook-safe}.
     */
    private static void appendSyntheticExecutorAliasDefinitions(ToolRegistry builtIn, List<ToolDefinition> out) {
        Map<String, ToolDefinition> byName = new LinkedHashMap<>();
        for (ToolDefinition d : out) {
            byName.put(d.getName(), d);
        }
        for (Map.Entry<String, String> e : builtIn.getExecutorAliasCanonicalTargets().entrySet()) {
            String alias = e.getKey();
            if (byName.containsKey(alias)) {
                continue;
            }
            ToolDefinition canon = byName.get(e.getValue());
            if (canon != null && canon.isPlaybookSafe()) {
                ToolDefinition shadow = new ToolDefinition(alias, canon.getDescription(), canon.getParametersSchema(),
                        true);
                out.add(shadow);
                byName.put(alias, shadow);
            }
        }
    }
}
