package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * A bare date like {@code 2025-09-10} carries no zone. The prompt guidance used to cover only relative
 * phrases (today, yesterday, 8am) while also asking for UTC output, so the same date was sent once as a
 * zone-local window and once as a UTC-day window — four hours apart, with different numbers.
 *
 * <p>The rule now lives in two places that must agree: the model-facing
 * {@link ParlerTimeAnchor#STABLE_TIME_GUIDANCE} and the canonical {@code docs/agent/time-interpretation.md}.
 * These assertions prove the rule is published in both; whether a live model follows it is live acceptance.
 */
class ZonelessDateTimeGuidanceTest {

    /** Markdown wraps and emphasises freely; assert on prose, not on line breaks or asterisks. */
    private static String canonicalDoc() throws Exception {
        Path p = Path.of("..", "docs", "agent", "time-interpretation.md");
        assertTrue(Files.exists(p), () -> "canonical time doc missing at " + p.toAbsolutePath());
        return Files.readString(p).replace("*", "").replaceAll("\\s+", " ");
    }

    @Test
    void promptGuidanceCoversZonelessAbsoluteDates() {
        String g = ParlerTimeAnchor.STABLE_TIME_GUIDANCE;

        // The ambiguity class is named, not just relative phrases.
        assertTrue(g.contains("no zone"), g);
        assertTrue(g.contains("bare dates"), g);

        // Interpret first, convert second — the failure was converting the expression and moving the day.
        assertTrue(g.contains("user_timezone"), g);
        assertTrue(g.contains("then convert to UTC"), g);
        assertTrue(g.contains("day boundary"), g);

        // Real offset for that date, never a hard-coded one.
        assertTrue(g.contains("actual offset"), g);
        assertTrue(g.contains("daylight-saving"), g);
        assertTrue(g.contains("never assume a fixed offset"), g);
        assertTrue(g.contains("never substitute the UTC day"), g);

        // Precedence: explicit dates and corrections, then the established same-topic period, then current time.
        assertTrue(g.contains("Explicit dates and user corrections take precedence"), g);
        assertTrue(g.contains("inherit the period established for the same topic"), g);
        assertTrue(g.contains("supplied current-time context only for parts not established there"), g);
        assertTrue(g.contains("Keep the resolved window consistent across related queries"), g);
        // Ask when the zone is unknown.
        assertTrue(g.contains("A supplied user_timezone is the zone for all unqualified times"), g);
        assertTrue(g.contains("do not query or offer an alternative zone"), g);
        assertTrue(g.contains("do not ask which zone applies"), g);
        assertTrue(g.contains("user_timezone is absent"), g);
        assertTrue(g.contains("or ask"), g);

        // End-bound convention stays per-tool.
        assertTrue(g.contains("follows the target "), g);
        assertTrue(g.contains("do not apply one convention to every service"), g);
    }

    @Test
    void canonicalDocCarriesTheSameRule() throws Exception {
        String d = canonicalDoc();

        assertTrue(d.contains("Zone-less dates and times"), "section missing");
        assertTrue(d.contains("`user_timezone`"), "user_timezone rule missing");
        assertTrue(d.contains("that date's actual offset"), "per-date offset rule missing");
        assertTrue(d.contains("daylight-saving"), "DST rule missing");
        assertTrue(d.contains("never substitute the UTC day"), "UTC-day substitution rule missing");
        assertTrue(d.contains("Explicit dates and user corrections take precedence"), "precedence rule missing");
        assertTrue(d.contains("inherit the period established for the same topic"), "same-topic inheritance missing");
        assertTrue(d.contains("supplied current-time context only for parts not established there"),
                "current-time fallback missing");
        assertTrue(d.contains("A new topic has no inherited window"), "topic-change rule missing");
        assertTrue(d.contains("update the affected queries"), "correction propagation missing");
        assertTrue(d.contains("stay consistent"), "cross-query consistency rule missing");
        assertTrue(d.contains("is the zone for all unqualified times"), "supplied-zone authority rule missing");
        assertTrue(d.contains("do not query or offer an alternative zone"), "alternative-zone probe rule missing");
        assertTrue(d.contains("do not ask which zone applies"), "no-clarification rule missing");
        assertTrue(d.contains("state the zone used or ask"), "missing-timezone rule missing");
        assertTrue(d.contains("InvokeServiceArgumentCoercion"),
                "must record that an explicit zoned literal is never silently re-offset");
    }
}
