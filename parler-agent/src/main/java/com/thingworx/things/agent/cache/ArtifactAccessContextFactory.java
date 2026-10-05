package com.thingworx.things.agent.cache;

import java.util.UUID;
import java.util.Objects;

import com.thingworx.things.agent.SecurityContextUtil;

/**
 * Core factory for {@link ArtifactAccessContext}. The only public production entry captures the
 * current ThingWorx principal and generates a fresh opaque scope. Explicit principal/raw-scope
 * construction is package-private for Core and same-package fixtures — model/App code MUST NOT
 * supply namespace identities.
 */
public final class ArtifactAccessContextFactory {

    @FunctionalInterface
    interface CurrentPrincipalLookup {
        /** @return current principal name, or {@code null}/empty when absent */
        String currentPrincipalName();
    }

    private static final CurrentPrincipalLookup DEFAULT_LOOKUP = SecurityContextUtil::currentPrincipalName;

    private static volatile CurrentPrincipalLookup principalLookup = DEFAULT_LOOKUP;

    private ArtifactAccessContextFactory() {}

    /** Fresh opaque caller-scope identity (UUID text). Package-private Core/test seam. */
    static String newOpaqueScopeId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Build a context from an explicitly supplied principal and opaque scope. Package-private
     * Core/test seam only — not a public App/model API.
     *
     * @throws IllegalArgumentException if either argument is null or blank
     */
    static ArtifactAccessContext of(String principalName, String opaqueScopeId) {
        String principal = requireNonBlank(principalName, "principalName");
        String scope = requireNonBlank(opaqueScopeId, "opaqueScopeId");
        return new ArtifactAccessContext(principal, scope);
    }

    /**
     * Capture the current principal via the active {@link CurrentPrincipalLookup} and pair it
     * with a Core-supplied opaque scope. Package-private Core seam for reusing an already-issued
     * scope id.
     *
     * @throws IllegalStateException if no current principal is available
     * @throws IllegalArgumentException if {@code opaqueScopeId} is null or blank
     */
    static ArtifactAccessContext fromCurrentPrincipal(String opaqueScopeId) {
        String principal = principalLookup.currentPrincipalName();
        if (principal == null || principal.isEmpty()) {
            throw new IllegalStateException("ArtifactAccessContext requires a current ThingWorx principal");
        }
        return of(principal, opaqueScopeId);
    }

    /**
     * Public production path: capture the current ThingWorx principal and generate a fresh opaque
     * scope. Model/App inputs cannot supply either identity.
     *
     * @throws IllegalStateException if no current principal is available
     */
    public static ArtifactAccessContext fromCurrentPrincipalWithNewScope() {
        return fromCurrentPrincipal(newOpaqueScopeId());
    }

    /**
     * Public Core/U2 adapter path: capture the current ThingWorx principal and reuse a
     * Core-minted opaque scope from {@code RunInvocationContext}. Model/App code MUST NOT invent
     * scope ids; adapters pass only the scope already issued by Core.
     *
     * @throws IllegalStateException if no current principal is available
     * @throws IllegalArgumentException if {@code opaqueScopeId} is null or blank
     */
    public static ArtifactAccessContext fromCurrentPrincipalWithExistingScope(String opaqueScopeId) {
        return fromCurrentPrincipal(opaqueScopeId);
    }

    /**
     * Package-private test/Core seam that replaces the principal lookup. Production MUST leave the
     * default ({@link SecurityContextUtil#currentPrincipalName()}). Pass {@code null} to reset.
     */
    static void setCurrentPrincipalLookup(CurrentPrincipalLookup lookup) {
        principalLookup = lookup == null ? DEFAULT_LOOKUP : Objects.requireNonNull(lookup);
    }

    /** Restore the production {@link SecurityContextUtil} principal lookup. */
    static void resetCurrentPrincipalLookup() {
        principalLookup = DEFAULT_LOOKUP;
    }

    private static String requireNonBlank(String value, String label) {
        if (value == null || value.isEmpty() || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must be non-blank");
        }
        return value;
    }
}
