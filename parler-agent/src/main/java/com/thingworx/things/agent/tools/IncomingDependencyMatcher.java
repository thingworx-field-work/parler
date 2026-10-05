package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import com.thingworx.relationships.RelationshipTypes;

/**
 * Exact-name matching against GenericThing {@code GetIncomingDependencies} rows (EntityDescriptor-derived). Pure logic for
 * unit tests — no ThingWorx services.
 */
final class IncomingDependencyMatcher {

    static final class Row {
        final String name;
        final String type;
        final String description;

        Row(String name, String type, String description) {
            this.name = name;
            this.type = type;
            this.description = description;
        }
    }

    /**
     * Outcome when resolving {@code query_entities} parent-style keys via dependency rows only.
     */
    static final class MatchOutcome {
        enum Kind {
            MISS,
            PARENT_HIT,
            AMBIGUOUS
        }

        final Kind kind;
        final RelationshipTypes.ThingworxRelationshipTypes resolvedRel;
        final String resolvedName;
        final String description;
        final String ambiguityDetail;

        private MatchOutcome(Kind kind, RelationshipTypes.ThingworxRelationshipTypes resolvedRel, String resolvedName,
                String description, String ambiguityDetail) {
            this.kind = kind;
            this.resolvedRel = resolvedRel;
            this.resolvedName = resolvedName;
            this.description = description;
            this.ambiguityDetail = ambiguityDetail;
        }

        static MatchOutcome miss() {
            return new MatchOutcome(Kind.MISS, null, null, null, null);
        }

        static MatchOutcome parentHit(RelationshipTypes.ThingworxRelationshipTypes rel, String resolvedName,
                String description) {
            return new MatchOutcome(Kind.PARENT_HIT, rel, resolvedName, description, null);
        }

        static MatchOutcome ambiguous(String detail) {
            return new MatchOutcome(Kind.AMBIGUOUS, null, null, null, detail);
        }
    }

    private IncomingDependencyMatcher() {}

    /**
     * Phase 0.5 dependency match: iterate candidate names (trimmed raw, canonical hint, capitalize-first normalized), exact
     * {@link String#equals} on row {@link Row#name}, prefer ThingTemplate over ThingShape, skip Thing-only rows and other
     * kinds.
     */
    static MatchOutcome matchParent(List<Row> rows, String trimmedRaw, String normalizedUser) {
        if (rows == null || trimmedRaw == null || trimmedRaw.isEmpty()) {
            return MatchOutcome.miss();
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(trimmedRaw.trim());
        String nu = normalizedUser != null ? normalizedUser : "";
        String canon = ModelKeyResolutionNormalize.canonicalTemplateHint(nu);
        if (canon != null) {
            candidates.add(canon);
        }
        if (!nu.isEmpty()) {
            candidates.add(Character.toUpperCase(nu.charAt(0)) + nu.substring(1));
        }

        for (String cand : candidates) {
            MatchOutcome slice = sliceForCandidate(rows, cand);
            if (slice != null) {
                return slice;
            }
        }
        return MatchOutcome.miss();
    }

    /** EntityServices repair: user's {@code entityCollectionType} string is misused as collection type alias. */
    static MatchOutcome matchEntityCollectionMisuse(List<Row> rows, String collectionTypeTrimmed) {
        String trimmed = collectionTypeTrimmed != null ? collectionTypeTrimmed.trim() : "";
        if (trimmed.isEmpty()) {
            return MatchOutcome.miss();
        }
        String nu = ModelKeyResolutionNormalize.normalizePhase0(trimmed);
        return matchParent(rows, trimmed, nu);
    }

    /**
     * Sorted unique ThingTemplate {@code name} values from live dependency rows (Phase 0.5 cache).
     */
    static List<String> sortedUniqueThingTemplateNames(List<Row> rows) {
        TreeSet<String> ts = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (rows != null) {
            for (Row r : rows) {
                if (r != null && r.name != null && ParsedKind.forTypeCell(r.type) == ParsedKind.THING_TEMPLATE) {
                    ts.add(r.name);
                }
            }
        }
        return new ArrayList<>(ts);
    }

    /** Synthetic rows for cached ThingTemplate names only (v1 narrowed Phase 0.5). */
    static List<Row> rowsFromCachedThingTemplateNames(List<String> names) {
        List<Row> out = new ArrayList<>();
        if (names == null) {
            return out;
        }
        for (String n : names) {
            if (n != null && !n.isEmpty()) {
                out.add(new Row(n, "ThingTemplate", null));
            }
        }
        return out;
    }

    private static MatchOutcome sliceForCandidate(List<Row> rows, String candidate) {
        List<Row> matches = new ArrayList<>();
        for (Row r : rows) {
            if (r == null || r.name == null) {
                continue;
            }
            if (candidate.equals(r.name)) {
                matches.add(r);
            }
        }
        if (matches.isEmpty()) {
            return null;
        }

        List<String> tierNamesThingTemplate = new ArrayList<>();
        List<String> tierNamesThingShape = new ArrayList<>();

        for (Row r : matches) {
            ParsedKind k = ParsedKind.forTypeCell(r.type);
            if (k == ParsedKind.THING_TEMPLATE) {
                tierNamesThingTemplate.add(r.name);
            } else if (k == ParsedKind.THING_SHAPE) {
                tierNamesThingShape.add(r.name);
            }
            // Thing rows and non-parent kinds: ignored for Phase 0.5 parent pick (same as Thing-only semantics).
        }

        if (!tierNamesThingTemplate.isEmpty()) {
            if (tierNamesConflict(tierNamesThingTemplate)) {
                return MatchOutcome.ambiguous("Multiple conflicting ThingTemplate rows for \"" + candidate
                        + "\" in GenericThing dependencies.");
            }
            Row pick = pickRow(matches, ParsedKind.THING_TEMPLATE);
            String name = pick != null ? pick.name : tierNamesThingTemplate.get(0);
            return MatchOutcome.parentHit(RelationshipTypes.ThingworxRelationshipTypes.ThingTemplate, name,
                    pick != null ? pick.description : null);
        }
        if (!tierNamesThingShape.isEmpty()) {
            if (tierNamesConflict(tierNamesThingShape)) {
                return MatchOutcome
                        .ambiguous("Multiple conflicting ThingShape rows for \"" + candidate + "\" in GenericThing dependencies.");
            }
            Row pick = pickRow(matches, ParsedKind.THING_SHAPE);
            String name = pick != null ? pick.name : tierNamesThingShape.get(0);
            return MatchOutcome.parentHit(RelationshipTypes.ThingworxRelationshipTypes.ThingShape, name,
                    pick != null ? pick.description : null);
        }

        return null;
    }

    private static Row pickRow(List<Row> matches, ParsedKind want) {
        for (Row r : matches) {
            if (want == ParsedKind.forTypeCell(r.type)) {
                return r;
            }
        }
        return null;
    }

    private static boolean tierNamesConflict(List<String> tierNamesThingTemplate) {
        Set<String> distinct = new LinkedHashSet<>(tierNamesThingTemplate);
        return distinct.size() > 1;
    }

    private enum ParsedKind {
        NONE,
        THING_ROW,
        THING_TEMPLATE,
        THING_SHAPE,
        OTHER;

        static ParsedKind forTypeCell(String raw) {
            if (raw == null || raw.isBlank()) {
                return NONE;
            }
            String u = raw.trim().toUpperCase(Locale.ROOT);
            if ("THINGTEMPLATE".equals(u)) {
                return THING_TEMPLATE;
            }
            if ("THINGSHAPE".equals(u)) {
                return THING_SHAPE;
            }
            if ("THING".equals(u)) {
                return THING_ROW;
            }
            return OTHER;
        }
    }
}
