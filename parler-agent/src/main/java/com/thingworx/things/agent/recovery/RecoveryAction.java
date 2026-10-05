package com.thingworx.things.agent.recovery;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One closed EG2 recovery action with an optional server-authored argument patch. */
public final class RecoveryAction {

    private final RecoveryActionType type;
    private final Map<String, String> argumentPatch;

    private RecoveryAction(RecoveryActionType type, Map<String, String> argumentPatch) {
        this.type = Objects.requireNonNull(type, "type");
        this.argumentPatch = argumentPatch == null || argumentPatch.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(argumentPatch));
    }

    public static RecoveryAction of(RecoveryActionType type) {
        return new RecoveryAction(type, Map.of());
    }

    public static RecoveryAction of(RecoveryActionType type, Map<String, String> argumentPatch) {
        return new RecoveryAction(type, argumentPatch);
    }

    public RecoveryActionType type() {
        return type;
    }

    public Map<String, String> argumentPatch() {
        return argumentPatch;
    }
}
