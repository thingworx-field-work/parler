package com.thingworx.things.agent.skillregistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Markdown skill catalog for the per-turn system prompt (metadata only).
 */
public final class SkillRegistryCatalogFormatter {

    private SkillRegistryCatalogFormatter() {}

    public static String format(List<SkillRegistryDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return "";
        }
        List<SkillRegistryDescriptor> sorted = new ArrayList<>(descriptors);
        sorted.sort(Comparator.comparing(SkillRegistryDescriptor::shortId));
        StringBuilder sb = new StringBuilder();
        sb.append("## Agent skills (metadata only — full text via /SkillName or get_agent_skill)\n\n");
        for (SkillRegistryDescriptor d : sorted) {
            String src = d.sourceKind() == SkillSourceKind.REPOSITORY ? "repository" : "service";
            sb.append("- **").append(d.title()).append("** (`").append(d.shortId()).append("`, source `").append(src)
                    .append("`)\n");
            if (d.whenToUse() != null && !d.whenToUse().isEmpty()) {
                sb.append("  - When: ").append(d.whenToUse()).append('\n');
            }
        }
        sb.append("\nTo load full instructions for a skill, call built-in tool **get_agent_skill** with JSON ")
                .append("{\"skill_name\":\"<id>\"} where `<id>` is the short id")
                .append(" (same id as `/SkillName`).\n");
        return sb.toString().trim();
    }
}
