package com.thingworx.things.agent.analysis.relationship;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Compact G2 summary strings. Uses association / relationship language only — never causal claims
 * (§3 / D5). Final-answer guards remain authoritative at DIK-5 packaging.
 */
public final class RelationshipWording {

    /** Matches the U3 typed-answer causal overclaim patterns (narrow). */
    private static final Pattern CAUSAL_PROSE = Pattern.compile(
            "(?i)\\b(?:root\\s+cause|caused\\s+by|because\\s+of|due\\s+to|is\\s+the\\s+cause)\\b");

    private RelationshipWording() {}

    public static String associationSummary(String methodId, double association, long supportN) {
        String s = String.format(Locale.ROOT,
                "Relationship evidence (%s): association=%.6f over %d aligned pairs.",
                methodId, association, supportN);
        assertNonCausal(s);
        return s;
    }

    public static String lagScanSummary(int bestLag, double bestAbsR, long supportN, int attempted) {
        String s = String.format(Locale.ROOT,
                "Lag-scan relationship evidence: bestLag=%d associationAbs=%.6f support=%d attempted=%d; "
                        + "selecting a lag increases false-discovery risk.",
                bestLag, bestAbsR, supportN, attempted);
        assertNonCausal(s);
        return s;
    }

    public static String insufficientSummary(String outcome) {
        String s = "Insufficient relationship evidence (" + outcome + "); no association metric emitted.";
        assertNonCausal(s);
        return s;
    }

    public static boolean isCausalProse(String text) {
        return text != null && CAUSAL_PROSE.matcher(text).find();
    }

    private static void assertNonCausal(String text) {
        if (isCausalProse(text)) {
            throw new IllegalStateException("G2 summary must not use causal wording");
        }
    }
}
