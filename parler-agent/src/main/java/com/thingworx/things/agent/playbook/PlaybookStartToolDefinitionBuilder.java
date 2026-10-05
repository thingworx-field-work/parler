package com.thingworx.things.agent.playbook;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.thingworx.things.agent.llm.ToolDefinition;

/**
 * Registry-driven {@code start_playbook} tool definition for the merged LLM tool list.
 */
public final class PlaybookStartToolDefinitionBuilder {

    static final int MAX_WHEN_TO_USE_CHARS_PER_LINE = 240;
    static final int MAX_TOP_LEVEL_DESCRIPTION_CHARS = 4000;

    private PlaybookStartToolDefinitionBuilder() {}

    /**
     * @return tool definition when registry is loaded with at least one playbook; otherwise {@code null}
     */
    public static ToolDefinition build(PlaybookRegistrySnapshot registry) {
        if (registry == null || !registry.isLoaded() || registry.catalogById().isEmpty()) {
            return null;
        }
        List<PlaybookCatalogEntry> sorted = registry.catalogById().values().stream()
                .sorted(Comparator.comparing(PlaybookCatalogEntry::id))
                .toList();
        String registeredIds = sorted.stream().map(PlaybookCatalogEntry::id).collect(Collectors.joining(", "));
        String description = buildTopLevelDescription(sorted);
        Map<String, Object> playbookId = Map.of(
                "type", "string",
                "description", "Registered playbook id. Loaded ids: " + registeredIds + ".");
        Map<String, Object> params = Map.of(
                "type", "object",
                "description",
                "Inputs for the selected playbook_id per that playbook's inputSchema; invalid params fail at start_playbook.",
                "additionalProperties", true);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("playbook_id", playbookId);
        properties.put("params", params);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new String[] {"playbook_id", "params"});
        return new ToolDefinition("start_playbook", description, schema);
    }

    static String buildTopLevelDescription(List<PlaybookCatalogEntry> sorted) {
        StringBuilder sb = new StringBuilder(
                "Start a registered Playbook workflow with structured parameters. "
                        + "See the per-turn Agent playbooks catalog for titles and routing hints.\n");
        for (PlaybookCatalogEntry e : sorted) {
            String when = e.whenToUse() != null ? e.whenToUse() : "";
            if (when.length() > MAX_WHEN_TO_USE_CHARS_PER_LINE) {
                when = when.substring(0, MAX_WHEN_TO_USE_CHARS_PER_LINE) + "…";
            }
            String line = "• " + e.id() + ": " + when + "\n";
            if (sb.length() + line.length() > MAX_TOP_LEVEL_DESCRIPTION_CHARS) {
                sb.append("… (see per-turn Agent playbooks catalog for remaining entries)\n");
                break;
            }
            sb.append(line);
        }
        return sb.toString().trim();
    }
}
