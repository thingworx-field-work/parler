package com.thingworx.things.agent.execution;

/**
 * U2 execution-scope kind for {@link RunInvocationContext}. Durable Playbook/run ids are never a
 * cache namespace.
 */
public enum ExecutionScope {
    CONVERSATION,
    REQUEST,
    HEADLESS
}
