package com.thingworx.things.agent.tools;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Optional **expand ∩ query** name intersection for {@code query_entities} and {@code query_entities_by_taxonomy}
 * (see {@code docs/architecture/entity-hierarchy.md} §6). Thing names match with {@link String#equals(Object)} on the
 * platform row name (no case folding).
 */
public final class EntityHierarchyIntersectHelper {

    /**
     * Hard cap on {@code intersectThingNames} array length (LLM-supplied expand set). **Independent** of the taxonomy
     * tool's internal QIT page size constant; values match **5000** in v1 only by coincidence — do not couple them in code.
     */
    public static final int MAX_INTERSECT_NAMES = 5000;

    private EntityHierarchyIntersectHelper() {
    }

    /** Result of parsing {@code intersectThingNames}; {@link #namesOrNull()} is {@code null} when intersection is off. */
    public static final class IntersectThingNamesParse {
        private final Set<String> namesOrNull;
        private final int droppedEmptyOrNonTextCount;

        public IntersectThingNamesParse(Set<String> namesOrNull, int droppedEmptyOrNonTextCount) {
            this.namesOrNull = namesOrNull;
            this.droppedEmptyOrNonTextCount = droppedEmptyOrNonTextCount;
        }

        /** Immutable non-empty set, or {@code null} when the tool should skip intersection. */
        public Set<String> namesOrNull() {
            return namesOrNull;
        }

        /** Entries skipped (non-textual, empty string, or null JSON elements). */
        public int droppedEmptyOrNonTextCount() {
            return droppedEmptyOrNonTextCount;
        }
    }

    /**
     * @return immutable non-empty set, or {@code null} when the tool should skip intersection
     */
    public static Set<String> parseIntersectThingNames(JsonNode root) {
        return parseIntersectThingNamesDetailed(root).namesOrNull();
    }

    public static IntersectThingNamesParse parseIntersectThingNamesDetailed(JsonNode root) {
        if (root == null || !root.has("intersectThingNames")) {
            return new IntersectThingNamesParse(null, 0);
        }
        JsonNode arr = root.get("intersectThingNames");
        if (arr == null || !arr.isArray() || arr.size() == 0) {
            return new IntersectThingNamesParse(null, 0);
        }
        if (arr.size() > MAX_INTERSECT_NAMES) {
            throw new IllegalArgumentException(
                    "intersectThingNames exceeds cap " + MAX_INTERSECT_NAMES + " (got " + arr.size() + ").");
        }
        int dropped = 0;
        Set<String> out = new HashSet<>();
        for (JsonNode el : arr) {
            if (el == null || el.isNull() || !el.isTextual()) {
                dropped++;
                continue;
            }
            String s = el.asText();
            if (s.isEmpty()) {
                dropped++;
                continue;
            }
            out.add(s);
        }
        Set<String> frozen = out.isEmpty() ? null : Collections.unmodifiableSet(out);
        return new IntersectThingNamesParse(frozen, dropped);
    }

    public static boolean parseIntersectExpandHasMore(JsonNode root) {
        return root != null && root.has("intersectExpandHasMore") && root.get("intersectExpandHasMore").asBoolean(false);
    }

    /**
     * Query-side continuation for a single QIT page: must use the **pre-intersect** row count (API_CONTRACT 2.4.11).
     * Lives here (not on {@link QueryEntitiesExecutor}) so unit tests avoid loading executor static log init.
     */
    public static boolean computeQuerySideHasMore(int offset, int returnedPostQit, Long platformTotal, int maxItems) {
        if (platformTotal != null) {
            return (long) offset + returnedPostQit < platformTotal;
        }
        return returnedPostQit >= maxItems && maxItems > 0;
    }

    /**
     * Writes the five intersect success fields when {@code intersectActive}. Keeps {@code query_entities*} consistent
     * and stays free of ThingWorx {@code LogUtilities} so tests can call it without JVM agent init.
     */
    public static void writeIntersectSuccessFields(
            ObjectNode out,
            boolean intersectActive,
            int preIntersectMatchCount,
            int intersectedRowCount,
            boolean queryHasMore,
            boolean expandHasMore) {
        writeIntersectSuccessFields(
                out, intersectActive, preIntersectMatchCount, intersectedRowCount, queryHasMore, expandHasMore, false);
    }

    /**
     * U1B E1: taxonomy intersect {@code hasMore} also ORs {@code completenessUnknown}
     * (listed-page / overall-intersect unknown).
     */
    public static void writeIntersectSuccessFields(
            ObjectNode out,
            boolean intersectActive,
            int preIntersectMatchCount,
            int intersectedRowCount,
            boolean queryHasMore,
            boolean expandHasMore,
            boolean completenessUnknown) {
        if (!intersectActive) {
            return;
        }
        out.put("preIntersectMatchCount", preIntersectMatchCount);
        out.put("intersectedRowCount", intersectedRowCount);
        out.put("queryHasMore", queryHasMore);
        out.put("expandHasMore", expandHasMore);
        out.put("hasMore", queryHasMore || expandHasMore || completenessUnknown);
    }
}
