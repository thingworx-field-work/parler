package com.thingworx.things.agent.analysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class HalfOpenWindowTest {

    @Test
    void contains_halfOpen() {
        HalfOpenWindow w = HalfOpenWindow.of(Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"));
        assertTrue(w.contains(Instant.parse("2026-01-01T00:00:00Z")));
        assertTrue(w.contains(Instant.parse("2026-01-01T12:00:00Z")));
        assertFalse(w.contains(Instant.parse("2026-01-02T00:00:00Z")));
    }

    @Test
    void rejectsNonPositiveSpan() {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> HalfOpenWindow.of(t, t));
    }
}
