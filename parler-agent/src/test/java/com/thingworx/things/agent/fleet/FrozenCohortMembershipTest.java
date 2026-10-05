package com.thingworx.things.agent.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.analysis.HalfOpenWindow;

class FrozenCohortMembershipTest {

    private static final HalfOpenWindow WINDOW = HalfOpenWindow.of(
            Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-02T00:00:00Z"));

    @Test
    void freezeIsOrderIndependentAndDedupes() {
        FrozenCohortMembership a = FrozenCohortMembership.freeze(
                "peer-v1", "metric-v1", WINDOW, List.of("b", "a", "a"));
        FrozenCohortMembership b = FrozenCohortMembership.freeze(
                "peer-v1", "metric-v1", WINDOW, List.of("a", "b"));
        assertEquals(a.membershipDigest(), b.membershipDigest());
        assertEquals(List.of("a", "b"), a.authorizedSemanticAssetIds());
        assertEquals(2, a.authorizedN());
        assertTrue(a.contains("a"));
    }

    @Test
    void freezeChangesWhenAuthorizedSetChanges() {
        FrozenCohortMembership a = FrozenCohortMembership.freeze(
                "peer-v1", "metric-v1", WINDOW, List.of("a", "b"));
        FrozenCohortMembership b = FrozenCohortMembership.freeze(
                "peer-v1", "metric-v1", WINDOW, List.of("a", "c"));
        assertNotEquals(a.membershipDigest(), b.membershipDigest());
    }
}
