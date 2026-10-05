package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.thingworx.relationships.RelationshipTypes;

/**
 * Root entity relationship types for service-target tools, derived from the platform
 * {@link RelationshipTypes.ThingworxEntityTypes} mapping ({@code getRelationshipType()}) — not a hand-maintained list.
 */
public final class ThingworxRootEntityTypes {

    private static final Set<RelationshipTypes.ThingworxRelationshipTypes> ROOT_RELS;
    private static final List<String> SORTED_ROOT_NAMES;

    static {
        LinkedHashSet<RelationshipTypes.ThingworxRelationshipTypes> rels = new LinkedHashSet<>();
        for (RelationshipTypes.ThingworxEntityTypes et : RelationshipTypes.ThingworxEntityTypes.values()) {
            if (et == RelationshipTypes.ThingworxEntityTypes.Unknown) {
                continue;
            }
            RelationshipTypes.ThingworxRelationshipTypes r = et.getRelationshipType();
            if (r != null) {
                rels.add(r);
            }
        }
        ROOT_RELS = Collections.unmodifiableSet(rels);
        ArrayList<String> names = new ArrayList<>();
        for (RelationshipTypes.ThingworxRelationshipTypes r : rels) {
            names.add(r.name());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        SORTED_ROOT_NAMES = Collections.unmodifiableList(names);
    }

    private ThingworxRootEntityTypes() {}

    /** Stable, de-duplicated root type names for tool JSON Schema {@code enum} values. */
    public static List<String> sortedRootEntityTypeNames() {
        return SORTED_ROOT_NAMES;
    }

    public static boolean isRootEntityType(RelationshipTypes.ThingworxRelationshipTypes rel) {
        return rel != null && ROOT_RELS.contains(rel);
    }

    /**
     * Parses a root service-target entity type (case-insensitive). Returns {@code null} if not a root type.
     */
    public static RelationshipTypes.ThingworxRelationshipTypes parseRootEntityType(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return null;
        }
        for (RelationshipTypes.ThingworxRelationshipTypes r : ROOT_RELS) {
            if (r.name().equalsIgnoreCase(t)) {
                return r;
            }
        }
        return null;
    }

    /** Comma-separated list for error messages (locale-neutral identifiers). */
    public static String formatAllowedRootsForMessage() {
        return String.join(", ", SORTED_ROOT_NAMES);
    }
}
