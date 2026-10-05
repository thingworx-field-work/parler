package com.thingworx.things.agent.semantics;

/** Internal resolver statuses (SP6). Not a public wire shape by itself. */
public enum SemanticResolveStatus {
    RESOLVED,
    AMBIGUOUS,
    NOT_FOUND,
    UNAVAILABLE
}
