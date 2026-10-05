package com.thingworx.things.agent.join;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.cache.TypedCell;
import com.thingworx.things.agent.cache.TypedColumn;
import com.thingworx.things.agent.cache.TypedRow;
import com.thingworx.types.BaseTypes;

class G8ExactJoinTest {

    private static final List<TypedColumn> LEFT = List.of(
            new TypedColumn("id", BaseTypes.STRING),
            new TypedColumn("val", BaseTypes.NUMBER));
    private static final List<TypedColumn> RIGHT = List.of(
            new TypedColumn("id", BaseTypes.STRING),
            new TypedColumn("name", BaseTypes.STRING));

    @Test
    void innerJoin_success_sharedKeyOnce() {
        ExactJoinResult r = ExactJoin.join(config(JoinType.INNER, JoinCardinality.ONE_TO_ONE),
                LEFT, List.of(row(0, "a", 1d), row(1, "b", 2d)),
                RIGHT, List.of(row(0, "a", "alpha"), row(1, "b", "beta")));
        assertTrue(r.success());
        assertTrue(r.mayPublish());
        assertEquals(2, r.outputRows().size());
        assertEquals(2L, r.matchedRows());
        assertEquals(List.of("id", "val", "name"), names(r.outputColumns()));
    }

    @Test
    void leftJoin_nullKeyAndUnmatchedRemain() {
        ExactJoinResult r = ExactJoin.join(config(JoinType.LEFT, JoinCardinality.ONE_TO_ONE),
                LEFT, List.of(row(0, "a", 1d), row(1, null, 2d), row(2, "z", 3d)),
                RIGHT, List.of(row(0, "a", "alpha")));
        assertTrue(r.success());
        assertEquals(3, r.outputRows().size());
        assertEquals(1L, r.matchedRows());
        assertEquals(2L, r.unmatchedLeftRows());
        assertTrue(r.outputRows().get(1).cells().get(2).isNull());
    }

    @Test
    void nullKeysNeverMatch_innerDrops() {
        ExactJoinResult r = ExactJoin.join(config(JoinType.INNER, JoinCardinality.ONE_TO_ONE),
                LEFT, List.of(row(0, null, 1d)),
                RIGHT, List.of(row(0, null, "x")));
        assertTrue(r.success());
        assertEquals(0, r.outputRows().size());
        assertEquals(0L, r.matchedRows());
    }

    @Test
    void typeMismatch_failsWithoutOutput() {
        List<TypedColumn> rightNum = List.of(
                new TypedColumn("id", BaseTypes.NUMBER),
                new TypedColumn("name", BaseTypes.STRING));
        ExactJoinResult r = ExactJoin.join(config(JoinType.INNER, JoinCardinality.ONE_TO_ONE),
                LEFT, List.of(row(0, "a", 1d)),
                rightNum, List.of(new TypedRow(0, List.of(TypedCell.ofNumber(1d), TypedCell.ofString("x")))));
        assertEquals(ExactJoinReason.JOIN_TYPE_MISMATCH, r.reason());
        assertFalse(r.mayPublish());
        assertTrue(r.outputRows().isEmpty());
    }

    @Test
    void missingKey_fails() {
        ExactJoinConfig cfg = ExactJoinConfig.builder()
                .keys(List.of(new JoinKeySpec("missing", "id")))
                .build();
        ExactJoinResult r = ExactJoin.join(cfg, LEFT, List.of(), RIGHT, List.of());
        assertEquals(ExactJoinReason.JOIN_KEY_MISSING, r.reason());
        assertFalse(r.mayPublish());
    }

    @Test
    void cardinalityOneToOne_duplicateFails() {
        ExactJoinResult r = ExactJoin.join(config(JoinType.INNER, JoinCardinality.ONE_TO_ONE),
                LEFT, List.of(row(0, "a", 1d), row(1, "a", 2d)),
                RIGHT, List.of(row(0, "a", "alpha")));
        assertEquals(ExactJoinReason.CARDINALITY_VIOLATION, r.reason());
        assertFalse(r.mayPublish());
        assertTrue(r.outputRows().isEmpty());
        assertTrue(r.representativeDuplicateKeys().contains("a"));
    }

    @Test
    void cardinalityOneToN_allowsRightDuplicates() {
        ExactJoinResult r = ExactJoin.join(config(JoinType.INNER, JoinCardinality.ONE_TO_N),
                LEFT, List.of(row(0, "a", 1d)),
                RIGHT, List.of(row(0, "a", "x"), row(1, "a", "y")));
        assertTrue(r.success());
        assertEquals(2, r.outputRows().size());
    }

    @Test
    void collisionError_failsBeforeEmit() {
        List<TypedColumn> right = List.of(
                new TypedColumn("id", BaseTypes.STRING),
                new TypedColumn("val", BaseTypes.STRING));
        ExactJoinResult r = ExactJoin.join(config(JoinType.INNER, JoinCardinality.ONE_TO_ONE),
                LEFT, List.of(row(0, "a", 1d)),
                right, List.of(new TypedRow(0, List.of(TypedCell.ofString("a"), TypedCell.ofString("x")))));
        assertEquals(ExactJoinReason.COLUMN_COLLISION, r.reason());
        assertFalse(r.mayPublish());
    }

    @Test
    void collisionPrefix_renamesBothSides() {
        List<TypedColumn> right = List.of(
                new TypedColumn("id", BaseTypes.STRING),
                new TypedColumn("val", BaseTypes.STRING));
        ExactJoinConfig cfg = ExactJoinConfig.builder()
                .keys(List.of(new JoinKeySpec("id", "id")))
                .collisionPolicy(CollisionPolicy.prefixLeftRight())
                .build();
        ExactJoinResult r = ExactJoin.join(cfg,
                LEFT, List.of(row(0, "a", 1d)),
                right, List.of(new TypedRow(0, List.of(TypedCell.ofString("a"), TypedCell.ofString("x")))));
        assertTrue(r.success());
        assertEquals(List.of("id", "left_val", "right_val"), names(r.outputColumns()));
    }

    @Test
    void collisionExplicitMap_incompleteFails() {
        List<TypedColumn> right = List.of(
                new TypedColumn("id", BaseTypes.STRING),
                new TypedColumn("val", BaseTypes.STRING));
        ExactJoinConfig cfg = ExactJoinConfig.builder()
                .keys(List.of(new JoinKeySpec("id", "id")))
                .collisionPolicy(CollisionPolicy.explicitRenameMap(Map.of("left:val", "l_val")))
                .build();
        ExactJoinResult r = ExactJoin.join(cfg,
                LEFT, List.of(row(0, "a", 1d)),
                right, List.of(new TypedRow(0, List.of(TypedCell.ofString("a"), TypedCell.ofString("x")))));
        assertEquals(ExactJoinReason.COLUMN_COLLISION, r.reason());
    }

    @Test
    void collisionExplicitMap_success() {
        List<TypedColumn> right = List.of(
                new TypedColumn("id", BaseTypes.STRING),
                new TypedColumn("val", BaseTypes.STRING));
        ExactJoinConfig cfg = ExactJoinConfig.builder()
                .keys(List.of(new JoinKeySpec("id", "id")))
                .collisionPolicy(CollisionPolicy.explicitRenameMap(Map.of(
                        "left:val", "leftVal",
                        "right:val", "rightVal")))
                .build();
        ExactJoinResult r = ExactJoin.join(cfg,
                LEFT, List.of(row(0, "a", 1d)),
                right, List.of(new TypedRow(0, List.of(TypedCell.ofString("a"), TypedCell.ofString("x")))));
        assertTrue(r.success());
        assertEquals(List.of("id", "leftVal", "rightVal"), names(r.outputColumns()));
    }

    @Test
    void outputBudgetExceeded_noHandle() {
        ExactJoinConfig cfg = ExactJoinConfig.builder()
                .keys(List.of(new JoinKeySpec("id", "id")))
                .cardinality(JoinCardinality.ONE_TO_N)
                .maxOutputRows(1)
                .build();
        ExactJoinResult r = ExactJoin.join(cfg,
                LEFT, List.of(row(0, "a", 1d)),
                RIGHT, List.of(row(0, "a", "x"), row(1, "a", "y")));
        assertEquals(ExactJoinReason.OUTPUT_BUDGET_EXCEEDED, r.reason());
        assertFalse(r.mayPublish());
        assertTrue(r.outputRows().isEmpty());
    }

    @Test
    void buildBudgetExceeded_noHandle() {
        ExactJoinConfig cfg = ExactJoinConfig.builder()
                .keys(List.of(new JoinKeySpec("id", "id")))
                .maxBuildRows(1)
                .buildSide(BuildSide.LEFT)
                .build();
        ExactJoinResult r = ExactJoin.join(cfg,
                LEFT, List.of(row(0, "a", 1d), row(1, "b", 2d)),
                RIGHT, List.of(row(0, "a", "x")));
        assertEquals(ExactJoinReason.BUDGET_EXCEEDED, r.reason());
        assertFalse(r.mayPublish());
    }

    @Test
    void passwordColumn_rejected() {
        List<TypedColumn> left = List.of(
                new TypedColumn("id", BaseTypes.STRING),
                new TypedColumn("secret", BaseTypes.PASSWORD));
        ExactJoinResult r = ExactJoin.join(config(JoinType.INNER, JoinCardinality.ONE_TO_ONE),
                left, List.of(new TypedRow(0, List.of(TypedCell.ofString("a"), TypedCell.ofString("x")))),
                RIGHT, List.of(row(0, "a", "n")));
        assertEquals(ExactJoinReason.PASSWORD_COLUMN, r.reason());
        assertFalse(r.mayPublish());
    }

    @Test
    void config_rejectsInvalidThresholds() {
        assertThrows(IllegalArgumentException.class, () -> ExactJoinConfig.builder()
                .keys(List.of())
                .build());
        assertThrows(IllegalArgumentException.class, () -> ExactJoinConfig.builder()
                .keys(List.of(new JoinKeySpec("id", "id")))
                .maxBuildRows(0)
                .build());
        assertThrows(IllegalArgumentException.class, () -> ExactJoinConfig.builder()
                .keys(List.of(new JoinKeySpec("id", "id")))
                .maxOutputRows(-1)
                .build());
        assertThrows(IllegalArgumentException.class, () -> ExactJoinConfig.builder()
                .keys(List.of(new JoinKeySpec("id", "id")))
                .profileDigest(" ")
                .build());
        assertThrows(IllegalArgumentException.class, () -> ExactJoinConfig.builder()
                .joinType(JoinType.LEFT)
                .buildSide(BuildSide.LEFT)
                .keys(List.of(new JoinKeySpec("id", "id")))
                .build());
        assertThrows(IllegalArgumentException.class, () -> CollisionPolicy.explicitRenameMap(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new JoinKeySpec("", "id"));
    }

    @Test
    void buildSideLeft_streamsOversizedRightProbe_once() {
        // Default/LEFT build: only left is indexed; oversized right is probed once (no second row index).
        ExactJoinConfig cfg = ExactJoinConfig.builder()
                .joinType(JoinType.INNER)
                .cardinality(JoinCardinality.ONE_TO_N)
                .keys(List.of(new JoinKeySpec("id", "id")))
                .buildSide(BuildSide.LEFT)
                .maxBuildRows(1)
                .maxOutputRows(10_000)
                .build();
        List<TypedRow> left = List.of(row(0, "a", 1d));
        List<TypedRow> right = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            right.add(row(i, "a", "n" + i));
        }
        SinglePassIterable probe = new SinglePassIterable(right);
        ExactJoinResult r = ExactJoin.join(cfg, LEFT, left, RIGHT, probe);
        assertTrue(r.success());
        assertEquals(500, r.outputRows().size());
        assertEquals(1, probe.iteratorCalls());
        assertTrue(r.detail().contains("indexedSide=LEFT"));
        assertTrue(r.detail().contains("probeStreamed=true"));
        assertEquals(1L, r.buildRows());
    }

    @Test
    void leftJoin_requiresBuildSideRight() {
        ExactJoinConfig cfg = ExactJoinConfig.builder()
                .joinType(JoinType.LEFT)
                .buildSide(BuildSide.RIGHT)
                .cardinality(JoinCardinality.ONE_TO_ONE)
                .keys(List.of(new JoinKeySpec("id", "id")))
                .build();
        ExactJoinResult r = ExactJoin.join(cfg,
                LEFT, List.of(row(0, "a", 1d), row(1, "z", 2d)),
                RIGHT, List.of(row(0, "a", "alpha")));
        assertTrue(r.success());
        assertEquals(1L, r.matchedRows());
        assertEquals(1L, r.unmatchedLeftRows());
        assertTrue(r.detail().contains("indexedSide=RIGHT"));
    }

    @Test
    void failedResultBuilder_rejectsCarryingRows() {
        assertThrows(IllegalStateException.class, () -> ExactJoinResult.builder()
                .reason(ExactJoinReason.CARDINALITY_VIOLATION)
                .outputRows(List.of(row(0, "a", 1d)))
                .build());
    }

    private static ExactJoinConfig config(JoinType type, JoinCardinality card) {
        ExactJoinConfig.Builder b = ExactJoinConfig.builder()
                .joinType(type)
                .cardinality(card)
                .keys(List.of(new JoinKeySpec("id", "id")))
                .collisionPolicy(CollisionPolicy.error());
        if (type == JoinType.LEFT) {
            b.buildSide(BuildSide.RIGHT);
        }
        return b.build();
    }

    /** Fails if the probe is iterated more than once (would indicate a second index/pre-pass). */
    private static final class SinglePassIterable implements Iterable<TypedRow> {
        private final List<TypedRow> rows;
        private int iteratorCalls;

        SinglePassIterable(List<TypedRow> rows) {
            this.rows = rows;
        }

        int iteratorCalls() {
            return iteratorCalls;
        }

        @Override
        public Iterator<TypedRow> iterator() {
            iteratorCalls++;
            if (iteratorCalls > 1) {
                throw new AssertionError("probe iterated more than once — second index/pre-pass?");
            }
            return rows.iterator();
        }
    }

    private static TypedRow row(long ordinal, String id, double val) {
        return new TypedRow(ordinal, List.of(
                id == null ? TypedCell.ofNull() : TypedCell.ofString(id),
                TypedCell.ofNumber(val)));
    }

    private static TypedRow row(long ordinal, String id, String name) {
        return new TypedRow(ordinal, List.of(
                id == null ? TypedCell.ofNull() : TypedCell.ofString(id),
                TypedCell.ofString(name)));
    }

    private static List<String> names(List<TypedColumn> cols) {
        return cols.stream().map(TypedColumn::name).toList();
    }
}
