package com.thingworx.things.agent.taxonomy;

import java.text.Normalizer;
import java.util.Locale;

/**
 * v3 normalization pipeline per {@code docs/agent/taxonomy.md}: trim → NFKC → {@link Locale#ROOT} lowercase →
 * collapse ASCII whitespace runs to one space.
 */
public final class TaxonomyV3Normalize {

    private TaxonomyV3Normalize() {}

    public static String normalize(String input) {
        if (input == null) {
            return "";
        }
        String t = input.trim();
        if (t.isEmpty()) {
            return "";
        }
        String nfkc = Normalizer.normalize(t, Normalizer.Form.NFKC);
        String lower = nfkc.toLowerCase(Locale.ROOT);
        return collapseAsciiWhitespace(lower);
    }

    private static String collapseAsciiWhitespace(String s) {
        StringBuilder b = new StringBuilder(s.length());
        boolean prevSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
                if (!prevSpace) {
                    b.append(' ');
                    prevSpace = true;
                }
            } else {
                b.append(c);
                prevSpace = false;
            }
        }
        return b.toString().trim();
    }
}
