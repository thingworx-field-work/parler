package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CachedTabularSortTopnDirectionTest {

    @Test
    void nullOrBlank_defaultsToDescending() {
        assertTrue(CachedTabularSortTopnDirection.isDescending(null));
        assertTrue(CachedTabularSortTopnDirection.isDescending(""));
        assertTrue(CachedTabularSortTopnDirection.isDescending("   "));
    }

    @Test
    void explicitDesc() {
        assertTrue(CachedTabularSortTopnDirection.isDescending("desc"));
        assertTrue(CachedTabularSortTopnDirection.isDescending("DESC"));
        assertTrue(CachedTabularSortTopnDirection.isDescending(" descending "));
    }

    @Test
    void explicitAsc() {
        assertFalse(CachedTabularSortTopnDirection.isDescending("asc"));
        assertFalse(CachedTabularSortTopnDirection.isDescending("ASCENDING"));
    }

    @Test
    void invalid_throws() {
        assertThrows(IllegalArgumentException.class, () -> CachedTabularSortTopnDirection.isDescending("sideways"));
    }
}
