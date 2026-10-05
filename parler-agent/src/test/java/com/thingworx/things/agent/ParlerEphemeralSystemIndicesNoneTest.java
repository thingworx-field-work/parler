package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ParlerEphemeralSystemIndicesNoneTest {

    @Test
    void none_is_all_sentinel_indices() {
        ParlerEphemeralSystemIndices n = ParlerEphemeralSystemIndices.NONE;
        assertEquals(-1, n.catalogIdx());
        assertEquals(-1, n.slashIdx());
        assertEquals(-1, n.timeAnchorIdx());
        assertEquals(-1, n.taxonomyIdx());
        assertEquals(-1, n.alertIdx());
        assertEquals(-1, n.hostScopeIdx());
    }

    @Test
    void none_equals_explicit_all_minus_one() {
        assertEquals(new ParlerEphemeralSystemIndices(-1, -1, -1, -1, -1, -1, -1), ParlerEphemeralSystemIndices.NONE);
    }
}
