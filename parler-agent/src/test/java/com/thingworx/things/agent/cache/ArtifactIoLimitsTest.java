package com.thingworx.things.agent.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Fixtures for cache-operation I/O bounds. */
class ArtifactIoLimitsTest {

    @Test
    void positiveBoundsAccepted() {
        ArtifactIoLimits limits = ArtifactIoLimits.of(1024, 100, 5_000, 8_192);
        assertEquals(1024, limits.maxBytes());
        assertEquals(100, limits.maxItems());
        assertEquals(5_000, limits.maxWallTimeMillis());
        assertEquals(8_192, limits.maxInternalBufferBytes());
    }

    @Test
    void nonPositiveAndOverCeilingRejected() {
        assertThrows(IllegalArgumentException.class, () -> ArtifactIoLimits.of(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> ArtifactIoLimits.of(1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> ArtifactIoLimits.of(1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> ArtifactIoLimits.of(1, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> ArtifactIoLimits.of(-1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> ArtifactIoLimits.of(1, 1, 1, ArtifactIoLimits.MAX_U1A_INTERNAL_BUFFER_BYTES + 1));
    }

    @Test
    void exceedChecksAreStrictlyGreaterThan() {
        ArtifactIoLimits limits = ArtifactIoLimits.of(10, 10, 10, 10);
        assertFalse(limits.exceedsBytes(10));
        assertTrue(limits.exceedsBytes(11));
        assertFalse(limits.exceedsItems(10));
        assertTrue(limits.exceedsItems(11));
        assertFalse(limits.exceedsWallTime(10));
        assertTrue(limits.exceedsWallTime(11));
    }
}
