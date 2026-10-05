package com.thingworx.things.agent.playbook;

import java.util.Set;

/**
 * Numeric and naming limits for Priority 1 generic derive ops (normative design:
 * {@code docs/agent/playbook-generic-ops-foundation.md} sections 6.3–6.4).
 */
public final class PlaybookGenericOpsConstants {

    private PlaybookGenericOpsConstants() {}

    /** Runtime: resolved row-array inputs. */
    public static final int MAX_GENERIC_INPUT_ROWS = 10_000;

    /** Runtime: {@code output.rows} length after execution. */
    public static final int MAX_GENERIC_OUTPUT_ROWS = 10_000;

    /** Validator: {@code top_n.n}. */
    public static final int MAX_GENERIC_TOP_N = 5_000;

    /** Validator: {@code group_by.maxGroups}. */
    public static final int MAX_GENERIC_GROUPS = 5_000;

    /** Validator: {@code build_nested_object.maxParents}. */
    public static final int MAX_NESTED_PARENTS = 200;

    /** Validator: {@code build_nested_object.maxChildren} (total {@code $src} row copies). */
    public static final int MAX_NESTED_CHILDREN = 1_000;

    /** Validator: {@code json_stringify.maxBytes}. */
    public static final int MAX_JSON_STRINGIFY_BYTES = 64_000;

    /** Validator: {@code build_targets.maxTargets} (fan-out / tool budget class). */
    public static final int MAX_GENERIC_TARGETS = 200;

    /** Validator: {@code join_by_key.maxRows}. */
    public static final int MAX_GENERIC_JOIN_OUTPUT_ROWS = 10_000;

    /** {@code collect_gaps} merged list cap after {@code maxItems}. */
    public static final int MAX_COLLECT_GAPS_ITEMS = 64;

    /** Validator/runtime: {@code fan_out.maxConcurrency} (serial fan-out only in v1). */
    public static final int MAX_FAN_OUT_CONCURRENCY = 1;

    /** Validator/runtime: {@code collect_values.maxValues}. */
    public static final int MAX_COLLECT_VALUES = 5_000;

    /** Validator/runtime: {@code join_values.maxLength}. */
    public static final int MAX_JOIN_VALUE_CHARS = 16_000;

    /** Validator: {@code merge_row_sets.maxSources} and {@code sources} array length. */
    public static final int MAX_MERGE_ROW_SETS_SOURCES = 16;

    /** String gap and structured-gap string fields. */
    public static final int MAX_GAP_TEXT_CHARS = 512;

    private static final Set<String> RESERVED_OUTPUT_FIELDS = Set.of(
            "rows",
            "totalCount",
            "returned",
            "gaps",
            "status",
            "targets",
            "row",
            "output",
            "_other");

    /** Reserved names for {@code project} {@code as}, {@code aggregate} measure names, etc. */
    public static boolean isReservedOutputField(String name) {
        return name != null && RESERVED_OUTPUT_FIELDS.contains(name);
    }
}
