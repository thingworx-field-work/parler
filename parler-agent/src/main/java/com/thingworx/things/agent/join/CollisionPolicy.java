package com.thingworx.things.agent.join;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Validated column-collision policy. Silent last-write-wins is forbidden. For
 * {@link CollisionPolicyKind#EXPLICIT_RENAME_MAP}, colliding columns must supply both
 * {@code left:name} and {@code right:name} map entries with unique output names.
 */
public final class CollisionPolicy {

    private final CollisionPolicyKind kind;
    private final Map<String, String> renameMap;

    private CollisionPolicy(CollisionPolicyKind kind, Map<String, String> renameMap) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.renameMap = Map.copyOf(renameMap == null ? Map.of() : renameMap);
    }

    public static CollisionPolicy error() {
        return new CollisionPolicy(CollisionPolicyKind.ERROR, Map.of());
    }

    public static CollisionPolicy prefixLeftRight() {
        return new CollisionPolicy(CollisionPolicyKind.PREFIX_LEFT_RIGHT, Map.of());
    }

    public static CollisionPolicy explicitRenameMap(Map<String, String> renameMap) {
        if (renameMap == null || renameMap.isEmpty()) {
            throw new IllegalArgumentException("explicit rename map required and non-empty");
        }
        Map<String, String> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : renameMap.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) {
                throw new IllegalArgumentException("rename map key required");
            }
            if (e.getValue() == null || e.getValue().isBlank()) {
                throw new IllegalArgumentException("rename map value required");
            }
            String from = e.getKey().trim();
            String to = e.getValue().trim();
            if (cleaned.containsKey(from)) {
                throw new IllegalArgumentException("duplicate rename map key: " + from);
            }
            cleaned.put(from, to);
        }
        return new CollisionPolicy(CollisionPolicyKind.EXPLICIT_RENAME_MAP, cleaned);
    }

    public CollisionPolicyKind kind() {
        return kind;
    }

    public Map<String, String> renameMap() {
        return renameMap;
    }
}
