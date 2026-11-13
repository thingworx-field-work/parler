package com.thingworx.things.agent;

/**
 * Resolves the {@link ResultBudget} of a tool call when it starts executing (topic round 6, R24). Production resolves
 * from the agent's live settings and the extended-tool registry; tests supply a lambda.
 */
@FunctionalInterface
public interface ResultBudgetResolver {

    ResultBudget resolve(String toolName);
}
