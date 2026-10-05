package com.thingworx.things.agent.tools;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.thingworx.relationships.RelationshipTypes;

/** Offline Phase 0.5 matching (GenericThing incoming dependency rows). */
class IncomingDependencyMatcherTest {

    @Test
    void stream_exact_thing_template_wins() {
        List<IncomingDependencyMatcher.Row> rows =
                Collections.singletonList(new IncomingDependencyMatcher.Row("Stream", "ThingTemplate", "base stream"));
        IncomingDependencyMatcher.MatchOutcome mo =
                IncomingDependencyMatcher.matchParent(rows, "Stream", ModelKeyResolutionNormalize.normalizePhase0("Stream"));
        Assertions.assertSame(IncomingDependencyMatcher.MatchOutcome.Kind.PARENT_HIT, mo.kind);
        Assertions.assertSame(RelationshipTypes.ThingworxRelationshipTypes.ThingTemplate, mo.resolvedRel);
        Assertions.assertEquals("Stream", mo.resolvedName);
    }

    @Test
    void data_table_uses_hint_candidate_data_table_row() {
        List<IncomingDependencyMatcher.Row> rows =
                Collections.singletonList(new IncomingDependencyMatcher.Row("DataTable", "ThingTemplate", ""));
        IncomingDependencyMatcher.MatchOutcome mo = IncomingDependencyMatcher.matchParent(rows, "Data Table",
                ModelKeyResolutionNormalize.normalizePhase0("Data Table"));
        Assertions.assertSame(IncomingDependencyMatcher.MatchOutcome.Kind.PARENT_HIT, mo.kind);
        Assertions.assertEquals("DataTable", mo.resolvedName);
    }

    @Test
    void thing_row_only_skipped_not_parent_hit() {
        List<IncomingDependencyMatcher.Row> rows =
                Arrays.asList(new IncomingDependencyMatcher.Row("Stream", "Thing", "something"));
        IncomingDependencyMatcher.MatchOutcome mo =
                IncomingDependencyMatcher.matchParent(rows, "Stream", ModelKeyResolutionNormalize.normalizePhase0("Stream"));
        Assertions.assertSame(IncomingDependencyMatcher.MatchOutcome.Kind.MISS, mo.kind);
    }

    @Test
    void thing_template_prefers_over_thing_shape_same_name() {
        List<IncomingDependencyMatcher.Row> rows = Arrays.asList(
                new IncomingDependencyMatcher.Row("Stream", "ThingShape", "shape"),
                new IncomingDependencyMatcher.Row("Stream", "ThingTemplate", "tmpl"));
        IncomingDependencyMatcher.MatchOutcome mo =
                IncomingDependencyMatcher.matchParent(rows, "Stream", ModelKeyResolutionNormalize.normalizePhase0("Stream"));
        Assertions.assertSame(RelationshipTypes.ThingworxRelationshipTypes.ThingTemplate, mo.resolvedRel);
        Assertions.assertEquals("Stream", mo.resolvedName);
    }

    @Test
    void list_entities_misuse_path_matches_collection_string() {
        List<IncomingDependencyMatcher.Row> rows =
                Collections.singletonList(new IncomingDependencyMatcher.Row("Stream", "ThingTemplate", "x"));
        IncomingDependencyMatcher.MatchOutcome mo =
                IncomingDependencyMatcher.matchEntityCollectionMisuse(rows, "Stream");
        Assertions.assertSame(IncomingDependencyMatcher.MatchOutcome.Kind.PARENT_HIT, mo.kind);
        Assertions.assertSame(RelationshipTypes.ThingworxRelationshipTypes.ThingTemplate, mo.resolvedRel);
    }

    /** Same rows {@link GenericThingIncomingDependencyResolver} builds from cached ThingTemplate names (v1). */
    @Test
    void cached_template_name_rows_match_entity_collection_misuse() {
        List<IncomingDependencyMatcher.Row> rows =
                IncomingDependencyMatcher.rowsFromCachedThingTemplateNames(Collections.singletonList("Stream"));
        IncomingDependencyMatcher.MatchOutcome mo =
                IncomingDependencyMatcher.matchEntityCollectionMisuse(rows, "Stream");
        Assertions.assertSame(IncomingDependencyMatcher.MatchOutcome.Kind.PARENT_HIT, mo.kind);
        Assertions.assertSame(RelationshipTypes.ThingworxRelationshipTypes.ThingTemplate, mo.resolvedRel);
    }

    /** Authoritative empty GenericThing template list (snapshot present, zero names) — no live fallback rows. */
    @Test
    void cached_empty_template_names_miss_entity_collection_misuse() {
        List<IncomingDependencyMatcher.Row> rows = IncomingDependencyMatcher.rowsFromCachedThingTemplateNames(Collections.emptyList());
        IncomingDependencyMatcher.MatchOutcome mo =
                IncomingDependencyMatcher.matchEntityCollectionMisuse(rows, "Stream");
        Assertions.assertSame(IncomingDependencyMatcher.MatchOutcome.Kind.MISS, mo.kind);
    }
}
