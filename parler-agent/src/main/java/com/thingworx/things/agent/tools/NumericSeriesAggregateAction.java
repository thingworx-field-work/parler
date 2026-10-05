package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Aggregation actions for numeric time-series, aligned with
 * {@link org.apache.commons.math3.stat.descriptive.DescriptiveStatistics} and
 * {@link org.apache.commons.math3.stat.descriptive.rank.Percentile}.
 * <p>
 * Tools accept a <b>list</b> of these names. An <b>empty list</b> means no aggregation (return raw series only).
 * Use only these names (no free-form strings).
 *
 * @see NumericSeriesAggregator
 * @see docs/agent/property_value.md
 */
public enum NumericSeriesAggregateAction {

    // --- org.apache.commons.math3.stat.descriptive.DescriptiveStatistics ---
    MIN,
    MAX,
    SUM,
    COUNT,
    /** Arithmetic mean ({@code DescriptiveStatistics#getMean()}). */
    MEAN,
    /** {@code DescriptiveStatistics#getGeometricMean()} — undefined if any value &le; 0. */
    GEOMETRIC_MEAN,
    /** Population variance ({@code DescriptiveStatistics#getVariance()}). */
    VARIANCE,
    STANDARD_DEVIATION,
    /** Requires sufficient sample size; may be NaN for very small n. */
    SKEWNESS,
    /** Requires sufficient sample size; may be NaN for very small n. */
    KURTOSIS,

    // --- org.apache.commons.math3.stat.descriptive.rank.Percentile ---
    /** 50th percentile. */
    MEDIAN,
    PERCENTILE_5,
    PERCENTILE_10,
    PERCENTILE_25,
    PERCENTILE_75,
    PERCENTILE_90,
    PERCENTILE_95,
    PERCENTILE_99,

    /** First sample in array order (not Commons Math). */
    FIRST,
    /** Last sample in array order (not Commons Math). */
    LAST;

    /**
     * Parse a single action name (case-insensitive). {@code AVG}→MEAN, {@code STDDEV}→STANDARD_DEVIATION.
     *
     * @return null if blank or unrecognized
     */
    public static NumericSeriesAggregateAction parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if ("AVG".equals(t)) {
            return MEAN;
        }
        if ("STD".equals(t) || "STDDEV".equals(t)) {
            return STANDARD_DEVIATION;
        }
        try {
            return valueOf(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Parse tool {@code actions} array. Empty or null list → empty result (caller: no aggregates).
     *
     * @throws IllegalArgumentException if any non-blank token is not a known action
     */
    public static List<NumericSeriesAggregateAction> parseList(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<NumericSeriesAggregateAction> out = new ArrayList<>();
        for (String s : raw) {
            if (s == null || s.isBlank()) {
                continue;
            }
            NumericSeriesAggregateAction a = parse(s);
            if (a == null) {
                throw new IllegalArgumentException("Unknown aggregate action: \"" + s + "\". "
                        + "Use wire names like MIN, MAX, MEAN, PERCENTILE_95, PERCENTILE_99.");
            }
            out.add(a);
        }
        return out;
    }

    /** Stable name for JSON keys and tool schemas. */
    public String wireName() {
        return name();
    }
}
