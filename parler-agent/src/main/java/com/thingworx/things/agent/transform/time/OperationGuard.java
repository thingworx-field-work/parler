package com.thingworx.things.agent.transform.time;

import java.util.function.LongSupplier;

import com.thingworx.things.agent.cache.PublicationGuard;
import com.thingworx.things.agent.execution.BudgetVector;
import com.thingworx.things.agent.execution.RunInvocationContext;
import com.thingworx.things.agent.tools.AgentToolContext;

/**
 * One deadline and one cancellation policy for a whole measurement operation: read, compute, publish. It
 * travels into the store path as a {@link PublicationGuard}, so output creation is part of the operation.
 */
final class OperationGuard implements PublicationGuard {

    static final String TIME_BUDGET_EXCEEDED = "TIME_BUDGET_EXCEEDED";
    static final String CANCELLED = "OPERATION_CANCELLED";

    private final BudgetVector budget;
    private final long maxWallNanos;
    private final long maxWallMillis;
    private final LongSupplier nanoClock;
    private final long startNanos;

    OperationGuard(BudgetVector budget, LongSupplier nanoClock) {
        this.budget = budget;
        this.maxWallMillis = budget.maxWallTimeMillis();
        this.maxWallNanos = maxWallMillis * 1_000_000L;
        this.nanoClock = nanoClock;
        this.startNanos = nanoClock.getAsLong();
    }

    /** Guard over the budget of the current tool invocation, or the tabular defaults outside one. */
    static OperationGuard forCurrentInvocation(LongSupplier nanoClock) {
        RunInvocationContext invocation = AgentToolContext.getRunInvocationContext();
        return new OperationGuard(invocation != null ? invocation.budget() : BudgetVector.defaultsForTabular(),
                nanoClock);
    }

    BudgetVector budget() {
        return budget;
    }

    /** Leaves the interrupt flag set: cancellation belongs to the caller. */
    @Override
    public void check() {
        if (Thread.currentThread().isInterrupted()) {
            throw new MeasurementException(CANCELLED, "the operation was cancelled before it finished");
        }
        if (nanoClock.getAsLong() - startNanos > maxWallNanos) {
            throw new MeasurementException(TIME_BUDGET_EXCEEDED,
                    "the operation exceeded its budget of " + maxWallMillis + " ms");
        }
    }

    @Override
    public long remainingWallTimeMillis() {
        return maxWallMillis - elapsedMillis();
    }

    long elapsedMillis() {
        return Math.max(0L, (nanoClock.getAsLong() - startNanos) / 1_000_000L);
    }
}
