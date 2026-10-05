package com.thingworx.things.agent.join;

/**
 * Declared join cardinality enforced before publish. {@code N_N} is rejected in v1 (B5).
 */
public enum JoinCardinality {
    ONE_TO_ONE,
    ONE_TO_N,
    N_TO_ONE
}
