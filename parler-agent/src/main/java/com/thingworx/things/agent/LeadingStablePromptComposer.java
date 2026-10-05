package com.thingworx.things.agent;

import com.thingworx.things.agent.llm.LlmRoutingGuide;
import com.thingworx.things.agent.taxonomy.TaxonomyPromptInjection;

/**
 * Assembles the leading stable system prompt body per {@code docs/agent/system-prompt-cache.md} §Stable Prompt Ordering.
 */
public final class LeadingStablePromptComposer {

    /** Stable answer-grounding rules shared by every agent round. */
    public static final String FINAL_ANSWER_EVIDENCE_RULE = "Final-answer evidence rule:\n"
            + "- Treat Recent Tool Evidence as authoritative for this turn.\n"
            + "- status=ok with 0 rows means no matching rows/data were returned, not that the entity is missing.\n"
            + "- NO_FINDING requires completed sufficient evidence; INSUFFICIENT_EVIDENCE must not be narrated as "
            + "no finding; ERROR is not partial success.\n"
            + "- sampleOnly=true cannot support full-table claims unless a live cacheId was used by a cache-aware "
            + "tool.\n"
            + "- CACHE_MISS means cached data is not available; do not say more cached rows can be loaded or that "
            + "cached data supports the answer.\n"
            + "- Preserve structured error codes; do not rewrite one failure class into another.\n"
            + "- Do not contradict emitted table/chart metadata.\n"
            + "- Do not upgrade associational or not-tested evidence into cause or ruled-out claims.";

    private LeadingStablePromptComposer() {
    }

    /**
     * @param firstSegment       base {@code AgentSettings.systemPrompt} or per-call override (override replaces only this
     *                           segment)
     * @param appendRoutingGuide when {@code true}, append bundled routing guide after the first segment
     * @param snapshot           cached suffix blocks; may be {@code null} when cache failed
     */
    public static String assemble(String firstSegment, boolean appendRoutingGuide, PromptContextCacheSnapshot snapshot,
            String taxonomyPromptInjectionEffective, String workflowCatalog) {
        String base = LlmRoutingGuide.composeSystemPrompt(firstSegment != null ? firstSegment : "", appendRoutingGuide);
        if (appendRoutingGuide) {
            base = LlmRoutingGuide.appendReplayFormatRoutingGuide(base);
        }
        return appendCachedStableSuffix(base, snapshot, taxonomyPromptInjectionEffective, workflowCatalog);
    }

    /**
     * Appends taxonomy, GenericThing catalog, and alert blocks from {@code snapshot} after {@code base}. Pure except for
     * {@code StringBuilder}; safe for offline tests without {@code LogUtilities} (unlike {@link #assemble} which pulls
     * {@link LlmRoutingGuide}).
     */
    public static String appendCachedStableSuffix(String base, PromptContextCacheSnapshot snapshot,
            String taxonomyPromptInjectionEffective, String workflowCatalog) {
        String mode = taxonomyPromptInjectionEffective != null ? taxonomyPromptInjectionEffective.trim()
                : TaxonomyPromptInjection.FULL_TABLE;
        StringBuilder sb = new StringBuilder(base != null ? base : "");
        if (snapshot != null) {
            if (TaxonomyPromptInjection.RESOLVER_GUIDANCE_ONLY.equals(mode)) {
                appendOptionalBlock(sb, TaxonomyPromptInjection.RESOLVER_GUIDANCE_TEXT);
            } else if (TaxonomyPromptInjection.FULL_TABLE.equals(mode)) {
                appendOptionalBlock(sb, snapshot.getTaxonomySystemBlock());
            }
            appendOptionalBlock(sb, snapshot.getGenericThingTemplateNamesBlock());
            appendOptionalBlock(sb, snapshot.getAlertPromptBlock());
        }
        appendOptionalBlock(sb, workflowCatalog);
        appendOptionalBlock(sb, ParlerTimeAnchor.STABLE_TIME_GUIDANCE);
        appendOptionalBlock(sb, FINAL_ANSWER_EVIDENCE_RULE);
        return sb.toString();
    }

    private static void appendOptionalBlock(StringBuilder sb, String block) {
        if (block == null || block.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("\n\n---\n");
        }
        sb.append(block);
    }

    /** Rough token estimate for operational logging (English-heavy text ~4 chars/token). */
    public static int estimateTokenCountHeuristic(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }
}
