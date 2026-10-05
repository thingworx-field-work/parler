package com.thingworx.things.agent.execution;

import java.util.Objects;
import java.util.UUID;

/**
 * Core-created U2 invocation context. Carries invocation id, {@link ExecutionScope}, and a
 * Core-generated opaque scope id for ArtifactCache namespace binding. {@code retryBudgetKey} is
 * populated by U3/G18 recovery when a mapped recovery needs a ledger key; otherwise null.
 */
public final class RunInvocationContext {

    private final String invocationId;
    private final ExecutionScope scopeKind;
    private final String opaqueScopeId;
    private final BudgetVector budget;
    private final String retryBudgetKey;

    private RunInvocationContext(String invocationId, ExecutionScope scopeKind, String opaqueScopeId,
            BudgetVector budget, String retryBudgetKey) {
        this.invocationId = Objects.requireNonNull(invocationId, "invocationId");
        this.scopeKind = Objects.requireNonNull(scopeKind, "scopeKind");
        this.opaqueScopeId = Objects.requireNonNull(opaqueScopeId, "opaqueScopeId");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.retryBudgetKey = retryBudgetKey == null || retryBudgetKey.isBlank() ? null : retryBudgetKey.trim();
        if (invocationId.isEmpty() || opaqueScopeId.isEmpty()) {
            throw new IllegalArgumentException("invocationId and opaqueScopeId must be non-empty");
        }
    }

    public static RunInvocationContext create(ExecutionScope scopeKind, BudgetVector budget) {
        return new RunInvocationContext(UUID.randomUUID().toString(), scopeKind,
                UUID.randomUUID().toString(), budget == null ? BudgetVector.defaultsForTabular() : budget,
                null);
    }

    /** Test/Core seam with explicit ids (no retry budget key). */
    public static RunInvocationContext of(String invocationId, ExecutionScope scopeKind,
            String opaqueScopeId, BudgetVector budget) {
        return of(invocationId, scopeKind, opaqueScopeId, budget, null);
    }

    public static RunInvocationContext of(String invocationId, ExecutionScope scopeKind,
            String opaqueScopeId, BudgetVector budget, String retryBudgetKey) {
        return new RunInvocationContext(invocationId, scopeKind, opaqueScopeId,
                budget == null ? BudgetVector.defaultsForTabular() : budget, retryBudgetKey);
    }

    public String invocationId() {
        return invocationId;
    }

    public ExecutionScope scopeKind() {
        return scopeKind;
    }

    public String opaqueScopeId() {
        return opaqueScopeId;
    }

    public BudgetVector budget() {
        return budget;
    }

    /**
     * G18 retry ledger budget key when a mapped recovery needs one; otherwise {@code null}.
     */
    public String retryBudgetKey() {
        return retryBudgetKey;
    }

    /** Immutable copy with a ledger budget key (U3 recovery). */
    public RunInvocationContext withRetryBudgetKey(String key) {
        return new RunInvocationContext(invocationId, scopeKind, opaqueScopeId, budget, key);
    }
}
