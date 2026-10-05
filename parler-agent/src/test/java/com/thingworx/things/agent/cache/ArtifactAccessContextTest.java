package com.thingworx.things.agent.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Fixtures for Core-created access context, opaque scope, injective namespace separation,
 * current-principal capture, and I/O limits.
 */
class ArtifactAccessContextTest {

    @AfterEach
    void resetPrincipalLookup() {
        ArtifactAccessContextFactory.resetCurrentPrincipalLookup();
    }

    @Test
    void samePrincipalAndScopeShareNamespace() {
        ArtifactAccessContext a = ArtifactAccessContextFactory.of("alice", "scope-1");
        ArtifactAccessContext b = ArtifactAccessContextFactory.of("alice", "scope-1");
        assertEquals(a.namespaceKey(), b.namespaceKey());
        assertTrue(a.sameNamespace(b));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentPrincipalsAreSeparated() {
        ArtifactAccessContext a = ArtifactAccessContextFactory.of("alice", "scope-1");
        ArtifactAccessContext b = ArtifactAccessContextFactory.of("bob", "scope-1");
        assertNotEquals(a.namespaceKey(), b.namespaceKey());
        assertFalse(a.sameNamespace(b));
        assertNotEquals(a, b);
    }

    @Test
    void differentScopesAreSeparated() {
        ArtifactAccessContext a = ArtifactAccessContextFactory.of("alice", "scope-1");
        ArtifactAccessContext b = ArtifactAccessContextFactory.of("alice", "scope-2");
        assertNotEquals(a.namespaceKey(), b.namespaceKey());
        assertFalse(a.sameNamespace(b));
    }

    @Test
    void principalIsCaseSensitive() {
        ArtifactAccessContext lower = ArtifactAccessContextFactory.of("alice", "scope-1");
        ArtifactAccessContext mixed = ArtifactAccessContextFactory.of("Alice", "scope-1");
        assertNotEquals(lower.namespaceKey(), mixed.namespaceKey());
        assertNotEquals(lower, mixed);
    }

    @Test
    void delimiterInComponentsDoesNotCollideNamespaces() {
        ArtifactAccessContext a = ArtifactAccessContextFactory.of("alice", "x\u0001y");
        ArtifactAccessContext b = ArtifactAccessContextFactory.of("alice\u0001x", "y");
        assertNotEquals(a.principalName(), b.principalName());
        assertNotEquals(a.opaqueScopeId(), b.opaqueScopeId());
        assertNotEquals(a.namespaceKey(), b.namespaceKey());
        assertFalse(a.sameNamespace(b));
        assertNotEquals(a, b);
    }

    @Test
    void blankPrincipalRejected() {
        assertThrows(IllegalArgumentException.class, () -> ArtifactAccessContextFactory.of(" ", "scope-1"));
        assertThrows(IllegalArgumentException.class, () -> ArtifactAccessContextFactory.of("", "scope-1"));
        assertThrows(IllegalArgumentException.class, () -> ArtifactAccessContextFactory.of(null, "scope-1"));
    }

    @Test
    void blankScopeRejected() {
        assertThrows(IllegalArgumentException.class, () -> ArtifactAccessContextFactory.of("alice", " "));
        assertThrows(IllegalArgumentException.class, () -> ArtifactAccessContextFactory.of("alice", ""));
        assertThrows(IllegalArgumentException.class, () -> ArtifactAccessContextFactory.of("alice", null));
    }

    @Test
    void newOpaqueScopeIdsAreDistinct() {
        String a = ArtifactAccessContextFactory.newOpaqueScopeId();
        String b = ArtifactAccessContextFactory.newOpaqueScopeId();
        assertNotEquals(a, b);
        assertFalse(a.isEmpty());
    }

    @Test
    void fromCurrentPrincipalRequiresLivePrincipal() {
        ArtifactAccessContextFactory.setCurrentPrincipalLookup(() -> null);
        assertThrows(IllegalStateException.class,
                () -> ArtifactAccessContextFactory.fromCurrentPrincipalWithNewScope());
    }

    @Test
    void fromCurrentPrincipalCapturesLivePrincipal() {
        ArtifactAccessContextFactory.setCurrentPrincipalLookup(() -> "alice-cache-h2");
        ArtifactAccessContext ctx = ArtifactAccessContextFactory.fromCurrentPrincipalWithNewScope();
        assertEquals("alice-cache-h2", ctx.principalName());
        assertFalse(ctx.opaqueScopeId().isEmpty());

        ArtifactAccessContext again = ArtifactAccessContextFactory.fromCurrentPrincipal(ctx.opaqueScopeId());
        assertTrue(ctx.sameNamespace(again));
        assertEquals(ctx, again);
    }

    @Test
    void productionLookupDefaultsToSecurityContextUtilAbsence() {
        // Default lookup is SecurityContextUtil; unit threads have no TWX SecurityContext.
        ArtifactAccessContextFactory.resetCurrentPrincipalLookup();
        assertThrows(IllegalStateException.class,
                () -> ArtifactAccessContextFactory.fromCurrentPrincipalWithNewScope());
    }
}
