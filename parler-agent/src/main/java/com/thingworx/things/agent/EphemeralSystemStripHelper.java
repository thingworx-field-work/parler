package com.thingworx.things.agent;

import java.util.Arrays;
import java.util.List;

import com.thingworx.things.agent.llm.ChatMessage;

/**
 * Removes per-turn Parler LLM system injections by message index (used for successful-turn cleanup and for
 * {@link com.thingworx.things.agent.tools.PendingApprovalRecord} snapshots). Lives outside {@link AgentThing} so JUnit
 * does not load ThingWorx {@code AgentBaseThing} / ESAPI transitive classes.
 */
public final class EphemeralSystemStripHelper {

    private EphemeralSystemStripHelper() {
    }

    /**
     * Removes ephemeral system rows in descending index order after sorting.
     *
     * @param ephemeralCatalogIdx catalog index or {@code -1}
     * @param ephemeralSlashIdx slash index or {@code -1}
     * @param ephemeralTimeAnchorIdx time-anchor index or {@code -1}
     * @param ephemeralTaxonomyIdx taxonomy index or {@code -1}
     * @param ephemeralAlertIdx alert index or {@code -1}
     * @param ephemeralHostScopeIdx host-scope meta index or {@code -1}
     */
    public static void stripEphemeralSystemInjectionsByIndices(List<ChatMessage> messages,
            ParlerEphemeralSystemIndices indices) {
        if (indices == null) {
            return;
        }
        stripEphemeralSystemInjectionsByIndices(messages,
                indices.catalogIdx(),
                indices.slashIdx(),
                indices.timeAnchorIdx(),
                indices.taxonomyIdx(),
                indices.alertIdx(),
                indices.hostScopeIdx(),
                indices.taskStateIdx());
    }

    public static void stripEphemeralSystemInjectionsByIndices(List<ChatMessage> messages,
            int ephemeralCatalogIdx,
            int ephemeralSlashIdx,
            int ephemeralTimeAnchorIdx,
            int ephemeralTaxonomyIdx,
            int ephemeralAlertIdx,
            int ephemeralHostScopeIdx,
            int ephemeralTaskStateIdx) {
        if (messages == null) {
            return;
        }
        int[] idx = {
                ephemeralHostScopeIdx,
                ephemeralAlertIdx,
                ephemeralTaxonomyIdx,
                ephemeralTimeAnchorIdx,
                ephemeralSlashIdx,
                ephemeralCatalogIdx,
                ephemeralTaskStateIdx
        };
        Arrays.sort(idx);
        for (int k = idx.length - 1; k >= 0; k--) {
            int i = idx[k];
            if (i >= 0 && i < messages.size()) {
                messages.remove(i);
            }
        }
    }
}
