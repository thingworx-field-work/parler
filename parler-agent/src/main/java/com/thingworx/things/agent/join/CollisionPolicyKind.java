package com.thingworx.things.agent.join;

/**
 * V1 column-collision policies. Silent last-write-wins is forbidden. Explicit rename maps are
 * represented separately on the join request, not as an enum constant.
 */
public enum CollisionPolicyKind {
    ERROR,
    PREFIX_LEFT_RIGHT,
    EXPLICIT_RENAME_MAP
}
