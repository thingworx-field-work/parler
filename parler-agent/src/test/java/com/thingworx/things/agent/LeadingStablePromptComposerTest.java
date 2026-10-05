package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;

import org.junit.jupiter.api.Test;

/** {@link LeadingStablePromptComposer#appendCachedStableSuffix} is offline-safe; {@link LeadingStablePromptComposer#assemble} pulls {@link com.thingworx.things.agent.llm.LlmRoutingGuide}. */
class LeadingStablePromptComposerTest {

    @Test
    void estimateTokenCountHeuristic_nonEmpty() {
        assertTrue(LeadingStablePromptComposer.estimateTokenCountHeuristic("abcd") >= 1);
    }

    @Test
    void appendCachedStableSuffix_ordersBlocksWithSeparators() {
        PromptContextCacheSnapshot snap = new PromptContextCacheSnapshot(
                "t-block",
                Collections.emptyList(),
                Collections.emptyList(),
                "gt-block",
                "al-block",
                Instant.parse("2026-01-01T00:00:00Z"));
        String out = LeadingStablePromptComposer.appendCachedStableSuffix(
                "base", snap, "full_table", "workflow-catalog");
        assertTrue(out.startsWith("base"));
        assertTrue(out.contains("t-block"));
        assertTrue(out.indexOf("t-block") < out.indexOf("gt-block"));
        assertTrue(out.indexOf("gt-block") < out.indexOf("al-block"));
        assertTrue(out.indexOf("al-block") < out.indexOf("workflow-catalog"));
        assertTrue(out.indexOf("workflow-catalog") < out.indexOf(ParlerTimeAnchor.STABLE_TIME_GUIDANCE));
        assertTrue(out.indexOf(ParlerTimeAnchor.STABLE_TIME_GUIDANCE)
                < out.indexOf(LeadingStablePromptComposer.FINAL_ANSWER_EVIDENCE_RULE));
    }

    @Test
    void appendCachedStableSuffix_nullSnapshot_stillCarriesStableTimeGuidance() {
        assertEquals("only\n\n---\n" + ParlerTimeAnchor.STABLE_TIME_GUIDANCE + "\n\n---\n"
                        + LeadingStablePromptComposer.FINAL_ANSWER_EVIDENCE_RULE,
                LeadingStablePromptComposer.appendCachedStableSuffix("only", null, "full_table", ""));
    }

    @Test
    void stableEvidenceRule_isPinnedAndComposerOwned() {
        assertEquals("Final-answer evidence rule:\n"
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
                + "- Do not upgrade associational or not-tested evidence into cause or ruled-out claims.",
                LeadingStablePromptComposer.FINAL_ANSWER_EVIDENCE_RULE);
        String out = LeadingStablePromptComposer.appendCachedStableSuffix("base", null, "full_table", "catalog");
        assertTrue(out.endsWith(LeadingStablePromptComposer.FINAL_ANSWER_EVIDENCE_RULE));
    }

    @Test
    void stableTimeGuidance_carriesPrecedenceAndZoneRules() {
        String g = ParlerTimeAnchor.STABLE_TIME_GUIDANCE;
        assertTrue(g.startsWith("## Time interpretation\n\n- "), g);
        // Zone and day-boundary rules.
        assertTrue(g.contains("user_timezone"), g);
        assertTrue(g.contains("actual offset"), g);
        assertTrue(g.contains("never substitute the UTC day"), g);
        // A supplied zone is authoritative: no alternative-zone probe, no clarification question.
        assertTrue(g.contains("A supplied user_timezone is the zone for all unqualified times"), g);
        assertTrue(g.contains("do not query or offer an alternative zone"), g);
        assertTrue(g.contains("do not ask which zone applies"), g);
        // Precedence: explicit / corrected > established same-topic period > supplied current time.
        assertTrue(g.contains("Explicit dates and user corrections take precedence"), g);
        assertTrue(g.contains("inherit the period established for the same topic or follow-up"), g);
        assertTrue(g.contains("supplied current-time context only for parts not established there"), g);
        // Relative arguments and ISO bounds.
        assertTrue(g.contains("calendarPhrase / relativeDuration"), g);
        assertTrue(g.contains("ISO-8601 UTC with Z"), g);
        // No per-turn values inside the stable block.
        assertFalse(g.contains("now_utc"), g);
        assertTrue(g.contains("do not apply one convention to every service"), g);
    }

    @Test
    void replayFormatRoutingGuide_resource_loadsWithoutThingWorxLogger() throws Exception {
        try (InputStream in = LeadingStablePromptComposerTest.class.getResourceAsStream(
                "/com/thingworx/things/agent/llm_replay_format_routing_guide.txt")) {
            assertNotNull(in, "replay routing guide must be on test classpath");
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(s.contains("parler.infotable.matrix.v1"));
            assertTrue(s.contains("parler.infotable.summary.v1"));
            assertTrue(s.contains("aggregate replay evidence"));
            assertTrue(s.contains("pointer"));
            assertTrue(s.contains("sampleOnly"));
            assertTrue(s.contains("parler.cohort.member.v1"));
        }
    }
}
