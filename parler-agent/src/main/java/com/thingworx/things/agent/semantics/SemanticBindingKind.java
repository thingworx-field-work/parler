package com.thingworx.things.agent.semantics;

/** Closed v1 binding kinds (SP3). */
public enum SemanticBindingKind {
    PROPERTY,
    SERVICE;

    public static SemanticBindingKind parse(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        for (SemanticBindingKind k : values()) {
            if (k.name().equals(t)) {
                return k;
            }
        }
        return null;
    }
}
