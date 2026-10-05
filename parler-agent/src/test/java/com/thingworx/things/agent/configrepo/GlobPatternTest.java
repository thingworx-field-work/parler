package com.thingworx.things.agent.configrepo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GlobPatternTest {

    @Test
    void literal_equals() {
        assertTrue(GlobPattern.matches("Thing", "Thing"));
        assertFalse(GlobPattern.matches("Thing", "thing"));
    }

    @Test
    void prefix_star() {
        assertTrue(GlobPattern.matches("Get*", "GetData"));
        assertFalse(GlobPattern.matches("Get*", "SetData"));
    }

    @Test
    void suffix_star() {
        assertTrue(GlobPattern.matches("*Data", "FooData"));
        assertFalse(GlobPattern.matches("*Data", "FooD"));
    }

    @Test
    void interior_star() {
        assertTrue(GlobPattern.matches("G*t", "Get"));
        assertTrue(GlobPattern.matches("G*t", "Gxt"));
        assertFalse(GlobPattern.matches("G*t", "G"));
    }

    @Test
    void trailing_star_allows_suffix() {
        assertTrue(GlobPattern.matches("abc*", "abcdef"));
    }

    @Test
    void empty_pattern_never_matches() {
        assertFalse(GlobPattern.matches("", "x"));
    }

    @Test
    void null_args() {
        assertFalse(GlobPattern.matches(null, "a"));
        assertFalse(GlobPattern.matches("a", null));
    }
}
