package com.thingworx.things.agent.join;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.thingworx.things.agent.cache.TypedCell;
import com.thingworx.things.agent.cache.TypedColumn;
import com.thingworx.things.agent.cache.TypedRow;
import com.thingworx.types.BaseTypes;

/**
 * Deterministic G8 exact inner/left hash join over typed projected rows. Pure operator — no cache
 * path access and no spill.
 *
 * <p><b>Bounded memory model (this layer):</b> only the configured build side is indexed into a
 * hash bounded by {@link ExactJoinConfig#maxBuildRows()}. The probe side is streamed once; probe
 * uniqueness (when required) is checked during that single pass via a key-only seen-set, never by
 * building a second row index. {@link JoinType#LEFT} requires {@link BuildSide#RIGHT} so unmatched
 * left rows can be emitted while streaming the left.
 *
 * <p><b>TQJ-5 scope:</b> callers may still pass fully materialized {@link List}s. End-to-end
 * "streamed probe / no spill" proof against {@code TypedTabularStream} is deferred to TQJ-5 cache
 * wiring; this slice makes the operator internally consistent with one bounded hash.
 *
 * <p>Failed joins return {@link ExactJoinResult#mayPublish()} {@code false} with empty output rows.
 */
public final class ExactJoin {

    private static final int MAX_DUP_KEYS_REPORTED = 8;

    private ExactJoin() {}

    public static ExactJoinResult join(
            ExactJoinConfig config,
            List<TypedColumn> leftSchema,
            List<TypedRow> leftRows,
            List<TypedColumn> rightSchema,
            List<TypedRow> rightRows) {
        return joinIterable(config, leftSchema,
                leftRows == null ? List.of() : leftRows,
                rightSchema,
                rightRows == null ? List.of() : rightRows);
    }

    /**
     * Join with iterable inputs. The probe side is consumed once; the build side is consumed once
     * into the bounded hash.
     */
    public static ExactJoinResult join(
            ExactJoinConfig config,
            List<TypedColumn> leftSchema,
            Iterable<TypedRow> leftRows,
            List<TypedColumn> rightSchema,
            Iterable<TypedRow> rightRows) {
        return joinIterable(config, leftSchema, leftRows, rightSchema, rightRows);
    }

    private static ExactJoinResult joinIterable(
            ExactJoinConfig config,
            List<TypedColumn> leftSchema,
            Iterable<TypedRow> leftRows,
            List<TypedColumn> rightSchema,
            Iterable<TypedRow> rightRows) {
        Objects.requireNonNull(config, "config");
        leftSchema = List.copyOf(Objects.requireNonNull(leftSchema, "leftSchema"));
        rightSchema = List.copyOf(Objects.requireNonNull(rightSchema, "rightSchema"));
        leftRows = leftRows == null ? List.of() : leftRows;
        rightRows = rightRows == null ? List.of() : rightRows;

        if (containsPassword(leftSchema) || containsPassword(rightSchema)) {
            return fail(ExactJoinReason.PASSWORD_COLUMN, 0L, 0L,
                    "PASSWORD columns are not joinable or publishable");
        }

        int[] leftKeyIdx = resolveKeyIndexes(leftSchema, config.keys(), true);
        if (leftKeyIdx == null) {
            return fail(ExactJoinReason.JOIN_KEY_MISSING, 0L, 0L, "left key column missing");
        }
        int[] rightKeyIdx = resolveKeyIndexes(rightSchema, config.keys(), false);
        if (rightKeyIdx == null) {
            return fail(ExactJoinReason.JOIN_KEY_MISSING, 0L, 0L, "right key column missing");
        }

        if (validateKeyTypes(leftSchema, leftKeyIdx, rightSchema, rightKeyIdx) != null) {
            return fail(ExactJoinReason.JOIN_TYPE_MISMATCH, 0L, 0L, "join key type family mismatch");
        }

        OutputPlan plan = planOutput(leftSchema, rightSchema, config);
        if (plan.failure != null) {
            return fail(plan.failure, 0L, 0L, plan.detail);
        }

        boolean buildLeft = config.buildSide() == BuildSide.LEFT;
        Iterable<TypedRow> buildInput = buildLeft ? leftRows : rightRows;
        Iterable<TypedRow> probeInput = buildLeft ? rightRows : leftRows;
        int[] buildKeyIdx = buildLeft ? leftKeyIdx : rightKeyIdx;
        int[] probeKeyIdx = buildLeft ? rightKeyIdx : leftKeyIdx;

        BuildIndex build = buildBoundedHash(buildInput, buildKeyIdx, config.maxBuildRows());
        if (build.budgetExceeded) {
            return fail(ExactJoinReason.BUDGET_EXCEEDED, buildLeft ? build.rowsRead : 0L,
                    buildLeft ? 0L : build.rowsRead,
                    "build side rows exceed maxBuildRows " + config.maxBuildRows());
        }
        if (sideMustBeUnique(config.cardinality(), buildLeft) && !build.multiplicity.isEmpty()) {
            long leftCount = buildLeft ? build.rowsRead : 0L;
            long rightCount = buildLeft ? 0L : build.rowsRead;
            return fail(ExactJoinReason.CARDINALITY_VIOLATION, leftCount, rightCount,
                    "build-side cardinality violation",
                    representativeKeys(build.multiplicity.keySet()));
        }

        boolean probeMustBeUnique = sideMustBeUnique(config.cardinality(), !buildLeft);
        List<TypedRow> out = new ArrayList<>();
        long matched = 0L;
        long unmatchedLeft = 0L;
        long probeRowsRead = 0L;
        Set<CompositeKey> seenProbeKeys = probeMustBeUnique ? new HashSet<>() : null;

        for (TypedRow probeRow : probeInput) {
            if (probeRow == null) {
                continue;
            }
            probeRowsRead++;
            CompositeKey key = extractKey(probeRow, probeKeyIdx);

            if (probeMustBeUnique && key != null && !seenProbeKeys.add(key)) {
                long leftCount = buildLeft ? build.rowsRead : probeRowsRead;
                long rightCount = buildLeft ? probeRowsRead : build.rowsRead;
                return fail(ExactJoinReason.CARDINALITY_VIOLATION, leftCount, rightCount,
                        "probe-side cardinality violation", List.of(key.display()));
            }

            if (buildLeft) {
                // Probe is right: emit left⋈right for each left match.
                if (key == null) {
                    continue;
                }
                List<TypedRow> leftMatches = build.hash.get(key);
                if (leftMatches == null || leftMatches.isEmpty()) {
                    continue;
                }
                for (TypedRow left : leftMatches) {
                    if (out.size() >= config.maxOutputRows()) {
                        return fail(ExactJoinReason.OUTPUT_BUDGET_EXCEEDED,
                                build.rowsRead, probeRowsRead,
                                "output rows exceed maxOutputRows " + config.maxOutputRows());
                    }
                    out.add(emit(plan, left, probeRow));
                    matched++;
                }
            } else {
                // Probe is left: emit left⋈right or unmatched left for LEFT join.
                List<TypedRow> rightMatches = key == null ? List.of()
                        : build.hash.getOrDefault(key, List.of());
                if (rightMatches.isEmpty()) {
                    if (config.joinType() == JoinType.LEFT) {
                        if (out.size() >= config.maxOutputRows()) {
                            return fail(ExactJoinReason.OUTPUT_BUDGET_EXCEEDED,
                                    probeRowsRead, build.rowsRead,
                                    "output rows exceed maxOutputRows " + config.maxOutputRows());
                        }
                        out.add(emit(plan, probeRow, null));
                        unmatchedLeft++;
                    }
                    continue;
                }
                for (TypedRow right : rightMatches) {
                    if (out.size() >= config.maxOutputRows()) {
                        return fail(ExactJoinReason.OUTPUT_BUDGET_EXCEEDED,
                                probeRowsRead, build.rowsRead,
                                "output rows exceed maxOutputRows " + config.maxOutputRows());
                    }
                    out.add(emit(plan, probeRow, right));
                    matched++;
                }
            }
        }

        long leftRowsRead = buildLeft ? build.rowsRead : probeRowsRead;
        long rightRowsRead = buildLeft ? probeRowsRead : build.rowsRead;

        return ExactJoinResult.builder()
                .reason(ExactJoinReason.SUCCESS)
                .outputColumns(plan.columns)
                .outputRows(out)
                .leftRowsRead(leftRowsRead)
                .rightRowsRead(rightRowsRead)
                .matchedRows(matched)
                .unmatchedLeftRows(unmatchedLeft)
                .buildRows(build.hash.size())
                .detail("profile=" + config.profileDigest()
                        + ";indexedSide=" + (buildLeft ? "LEFT" : "RIGHT")
                        + ";probeStreamed=true")
                .build();
    }

    private static BuildIndex buildBoundedHash(
            Iterable<TypedRow> buildInput, int[] buildKeyIdx, long maxBuildRows) {
        BuildIndex index = new BuildIndex();
        for (TypedRow row : buildInput) {
            if (row == null) {
                continue;
            }
            index.rowsRead++;
            if (index.rowsRead > maxBuildRows) {
                index.budgetExceeded = true;
                return index;
            }
            CompositeKey key = extractKey(row, buildKeyIdx);
            if (key == null) {
                continue;
            }
            List<TypedRow> bucket = index.hash.computeIfAbsent(key, k -> new ArrayList<>(1));
            bucket.add(row);
            if (bucket.size() > 1) {
                index.multiplicity.put(key, bucket.size());
            }
        }
        return index;
    }

    /** Whether the named side (true=left) must have unique keys under the cardinality. */
    private static boolean sideMustBeUnique(JoinCardinality card, boolean leftSide) {
        if (card == JoinCardinality.ONE_TO_ONE) {
            return true;
        }
        if (card == JoinCardinality.ONE_TO_N) {
            return leftSide;
        }
        if (card == JoinCardinality.N_TO_ONE) {
            return !leftSide;
        }
        return true;
    }

    private static List<String> representativeKeys(Set<CompositeKey> keys) {
        List<String> out = new ArrayList<>();
        for (CompositeKey k : keys) {
            out.add(k.display());
            if (out.size() >= MAX_DUP_KEYS_REPORTED) {
                break;
            }
        }
        return out;
    }

    private static ExactJoinResult fail(ExactJoinReason reason, long left, long right, String detail) {
        return fail(reason, left, right, detail, List.of());
    }

    private static ExactJoinResult fail(
            ExactJoinReason reason, long left, long right, String detail, List<String> dupKeys) {
        return ExactJoinResult.builder()
                .reason(reason)
                .leftRowsRead(left)
                .rightRowsRead(right)
                .detail(detail)
                .representativeDuplicateKeys(dupKeys)
                .build();
    }

    private static boolean containsPassword(List<TypedColumn> schema) {
        for (TypedColumn c : schema) {
            if (c.baseType() == BaseTypes.PASSWORD) {
                return true;
            }
        }
        return false;
    }

    private static int[] resolveKeyIndexes(List<TypedColumn> schema, List<JoinKeySpec> keys, boolean left) {
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < schema.size(); i++) {
            idx.put(schema.get(i).name(), i);
        }
        int[] out = new int[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            String name = left ? keys.get(i).leftColumn() : keys.get(i).rightColumn();
            Integer j = idx.get(name);
            if (j == null) {
                return null;
            }
            out[i] = j;
        }
        return out;
    }

    private static ExactJoinReason validateKeyTypes(
            List<TypedColumn> leftSchema,
            int[] leftKeyIdx,
            List<TypedColumn> rightSchema,
            int[] rightKeyIdx) {
        for (int i = 0; i < leftKeyIdx.length; i++) {
            if (!sameTypeFamily(leftSchema.get(leftKeyIdx[i]).baseType(),
                    rightSchema.get(rightKeyIdx[i]).baseType())) {
                return ExactJoinReason.JOIN_TYPE_MISMATCH;
            }
        }
        return null;
    }

    private static boolean sameTypeFamily(BaseTypes a, BaseTypes b) {
        return typeFamily(a).equals(typeFamily(b));
    }

    private static String typeFamily(BaseTypes bt) {
        if (bt == BaseTypes.NUMBER || bt == BaseTypes.INTEGER || bt == BaseTypes.LONG) {
            return "NUMBER";
        }
        if (bt == BaseTypes.BOOLEAN) {
            return "BOOLEAN";
        }
        if (bt == BaseTypes.DATETIME) {
            return "DATETIME";
        }
        if (bt == BaseTypes.PASSWORD) {
            return "PASSWORD";
        }
        return "STRING";
    }

    private static CompositeKey extractKey(TypedRow row, int[] keyIdx) {
        Object[] parts = new Object[keyIdx.length];
        for (int i = 0; i < keyIdx.length; i++) {
            TypedCell cell = cellAt(row, keyIdx[i]);
            if (cell == null || cell.isNull()) {
                return null;
            }
            Object canon = canonicalize(cell);
            if (canon == null) {
                return null;
            }
            parts[i] = canon;
        }
        return new CompositeKey(parts);
    }

    private static Object canonicalize(TypedCell cell) {
        switch (cell.kind()) {
            case STRING:
                return cell.stringValue();
            case NUMBER:
                return Double.valueOf(cell.numberValue());
            case BOOLEAN:
                return Boolean.valueOf(cell.booleanValue());
            case DATETIME:
                return cell.datetimeValue() == null ? null
                        : Long.valueOf(cell.datetimeValue().toEpochMilli());
            case NULL:
            default:
                return null;
        }
    }

    private static TypedCell cellAt(TypedRow row, int index) {
        if (row == null || index < 0 || index >= row.cells().size()) {
            return TypedCell.ofNull();
        }
        TypedCell c = row.cells().get(index);
        return c == null ? TypedCell.ofNull() : c;
    }

    private static TypedRow emit(OutputPlan plan, TypedRow left, TypedRow right) {
        List<TypedCell> cells = new ArrayList<>(plan.columns.size());
        for (OutputCol oc : plan.mapping) {
            if (oc.fromLeft) {
                cells.add(cellAt(left, oc.sourceIndex));
            } else if (right == null) {
                cells.add(TypedCell.ofNull());
            } else {
                cells.add(cellAt(right, oc.sourceIndex));
            }
        }
        return new TypedRow(left.sourceOrdinal(), cells);
    }

    private static OutputPlan planOutput(
            List<TypedColumn> leftSchema, List<TypedColumn> rightSchema, ExactJoinConfig config) {
        Set<String> sharedKeyNames = new HashSet<>();
        for (JoinKeySpec k : config.keys()) {
            if (k.leftColumn().equals(k.rightColumn())) {
                sharedKeyNames.add(k.leftColumn());
            }
        }

        Set<String> leftNames = new HashSet<>();
        for (TypedColumn c : leftSchema) {
            leftNames.add(c.name());
        }
        Set<String> colliding = new LinkedHashSet<>();
        for (TypedColumn c : rightSchema) {
            if (leftNames.contains(c.name()) && !sharedKeyNames.contains(c.name())) {
                colliding.add(c.name());
            }
        }

        CollisionPolicy policy = config.collisionPolicy();
        if (policy.kind() == CollisionPolicyKind.ERROR && !colliding.isEmpty()) {
            return OutputPlan.fail(ExactJoinReason.COLUMN_COLLISION,
                    "column collision: " + String.join(",", colliding));
        }

        Map<String, String> leftRename = new LinkedHashMap<>();
        Map<String, String> rightRename = new LinkedHashMap<>();
        for (String name : colliding) {
            String leftOut;
            String rightOut;
            switch (policy.kind()) {
                case PREFIX_LEFT_RIGHT:
                    leftOut = "left_" + name;
                    rightOut = "right_" + name;
                    break;
                case EXPLICIT_RENAME_MAP:
                    leftOut = policy.renameMap().get("left:" + name);
                    rightOut = policy.renameMap().get("right:" + name);
                    if (leftOut == null || rightOut == null) {
                        return OutputPlan.fail(ExactJoinReason.COLUMN_COLLISION,
                                "rename map must include left:" + name + " and right:" + name);
                    }
                    break;
                case ERROR:
                default:
                    return OutputPlan.fail(ExactJoinReason.COLUMN_COLLISION, "column collision: " + name);
            }
            leftRename.put(name, leftOut);
            rightRename.put(name, rightOut);
        }

        Set<String> used = new HashSet<>();
        List<TypedColumn> columns = new ArrayList<>();
        List<OutputCol> mapping = new ArrayList<>();

        for (int i = 0; i < leftSchema.size(); i++) {
            TypedColumn c = leftSchema.get(i);
            String outName = leftRename.getOrDefault(c.name(), c.name());
            if (!used.add(outName)) {
                return OutputPlan.fail(ExactJoinReason.COLUMN_COLLISION,
                        "duplicate output column name: " + outName);
            }
            columns.add(new TypedColumn(outName, c.baseType()));
            mapping.add(new OutputCol(true, i));
        }
        for (int i = 0; i < rightSchema.size(); i++) {
            TypedColumn c = rightSchema.get(i);
            if (sharedKeyNames.contains(c.name())) {
                continue;
            }
            String outName = rightRename.getOrDefault(c.name(), c.name());
            if (!used.add(outName)) {
                return OutputPlan.fail(ExactJoinReason.COLUMN_COLLISION,
                        "duplicate output column name: " + outName);
            }
            columns.add(new TypedColumn(outName, c.baseType()));
            mapping.add(new OutputCol(false, i));
        }

        return OutputPlan.ok(columns, mapping);
    }

    private static final class BuildIndex {
        final Map<CompositeKey, List<TypedRow>> hash = new HashMap<>();
        final Map<CompositeKey, Integer> multiplicity = new LinkedHashMap<>();
        long rowsRead;
        boolean budgetExceeded;
    }

    private static final class OutputPlan {
        final ExactJoinReason failure;
        final String detail;
        final List<TypedColumn> columns;
        final List<OutputCol> mapping;

        private OutputPlan(ExactJoinReason failure, String detail, List<TypedColumn> columns,
                List<OutputCol> mapping) {
            this.failure = failure;
            this.detail = detail;
            this.columns = columns;
            this.mapping = mapping;
        }

        static OutputPlan fail(ExactJoinReason reason, String detail) {
            return new OutputPlan(reason, detail, List.of(), List.of());
        }

        static OutputPlan ok(List<TypedColumn> columns, List<OutputCol> mapping) {
            return new OutputPlan(null, null, columns, mapping);
        }
    }

    private static final class OutputCol {
        final boolean fromLeft;
        final int sourceIndex;

        OutputCol(boolean fromLeft, int sourceIndex) {
            this.fromLeft = fromLeft;
            this.sourceIndex = sourceIndex;
        }
    }

    private static final class CompositeKey {
        private final Object[] parts;

        CompositeKey(Object[] parts) {
            this.parts = parts;
        }

        String display() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    sb.append('|');
                }
                sb.append(parts[i]);
            }
            return sb.toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CompositeKey)) {
                return false;
            }
            return Arrays.equals(parts, ((CompositeKey) o).parts);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(parts);
        }
    }
}
