package com.thingworx.things.agent.playbook;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Markdown playbook catalog for the per-turn system prompt (metadata only).
 */
public final class PlaybookCatalogFormatter {

    private PlaybookCatalogFormatter() {}

    public static String format(List<PlaybookCatalogEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        List<PlaybookCatalogEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(PlaybookCatalogEntry::id));
        StringBuilder sb = new StringBuilder();
        sb.append("## Agent playbooks (metadata only — start via start_playbook or structured slash)\n\n");
        for (PlaybookCatalogEntry e : sorted) {
            sb.append("- **").append(e.title()).append("** (`").append(e.id()).append("`)\n");
            if (e.whenToUse() != null && !e.whenToUse().isEmpty()) {
                sb.append("  - When: ").append(e.whenToUse()).append('\n');
            }
            sb.append("  - Start: **start_playbook** `{\"playbook_id\":\"").append(e.id())
                    .append("\",\"params\":{…}}` or slash `/").append(e.id()).append(" {\"…\"}`.\n");
        }
        sb.append("\nCall **start_playbook** once per turn to run a playbook. Playbook steps are executed by the runtime, ")
                .append("not by loading skill text.\n");
        return sb.toString().trim();
    }
}
