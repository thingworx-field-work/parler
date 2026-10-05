package com.thingworx.things.agent.tools;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;

/**
 * Applies one or more {@link NumericSeriesAggregateAction} values using Apache Commons Math.
 * <p>
 * <b>Empty {@code actions}</b> → empty map (tool layer: omit {@code aggregates} and return raw points only).
 */
public final class NumericSeriesAggregator {

    private NumericSeriesAggregator() {}

    /**
     * Run all requested aggregates in one pass where possible (shared {@link DescriptiveStatistics}).
     * Duplicate actions in the list are de-duplicated while preserving first-seen order.
     *
     * @return map {@code action.name() → value}; empty if {@code actions} is null/empty or {@code values} is null/empty
     */
    public static Map<String, Double> aggregateAll(double[] values, List<NumericSeriesAggregateAction> actions) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (actions == null || actions.isEmpty() || values == null || values.length == 0) {
            return out;
        }
        LinkedHashSet<NumericSeriesAggregateAction> unique = new LinkedHashSet<>(actions);

        boolean needDs = false;
        for (NumericSeriesAggregateAction a : unique) {
            if (usesDescriptiveStatistics(a)) {
                needDs = true;
                break;
            }
        }
        DescriptiveStatistics ds = null;
        if (needDs) {
            ds = new DescriptiveStatistics();
            for (double v : values) {
                ds.addValue(v);
            }
        }

        for (NumericSeriesAggregateAction a : unique) {
            putIfPresent(out, a, computeOne(values, ds, a));
        }
        return out;
    }

    /** Single statistic; empty sample → empty. */
    public static OptionalDouble aggregate(double[] values, NumericSeriesAggregateAction action) {
        if (action == null) {
            return OptionalDouble.empty();
        }
        Map<String, Double> m = aggregateAll(values, List.of(action));
        Double d = m.get(action.name());
        return d != null ? OptionalDouble.of(d) : OptionalDouble.empty();
    }

    private static void putIfPresent(Map<String, Double> out, NumericSeriesAggregateAction a, OptionalDouble od) {
        if (od.isPresent()) {
            out.put(a.name(), od.getAsDouble());
        }
    }

    private static boolean usesDescriptiveStatistics(NumericSeriesAggregateAction a) {
        switch (a) {
            case MIN:
            case MAX:
            case SUM:
            case COUNT:
            case MEAN:
            case GEOMETRIC_MEAN:
            case VARIANCE:
            case STANDARD_DEVIATION:
            case SKEWNESS:
            case KURTOSIS:
                return true;
            default:
                return false;
        }
    }

    private static OptionalDouble computeOne(double[] values, DescriptiveStatistics ds, NumericSeriesAggregateAction action) {
        switch (action) {
            case FIRST:
                return OptionalDouble.of(values[0]);
            case LAST:
                return OptionalDouble.of(values[values.length - 1]);
            case MIN:
                return OptionalDouble.of(ds.getMin());
            case MAX:
                return OptionalDouble.of(ds.getMax());
            case SUM:
                return OptionalDouble.of(ds.getSum());
            case COUNT:
                return OptionalDouble.of((double) ds.getN());
            case MEAN:
                return OptionalDouble.of(ds.getMean());
            case GEOMETRIC_MEAN:
                return OptionalDouble.of(ds.getGeometricMean());
            case VARIANCE:
                return OptionalDouble.of(ds.getVariance());
            case STANDARD_DEVIATION:
                return OptionalDouble.of(ds.getStandardDeviation());
            case SKEWNESS:
                return OptionalDouble.of(ds.getSkewness());
            case KURTOSIS:
                return OptionalDouble.of(ds.getKurtosis());
            case MEDIAN:
                return percentile(values, 50);
            case PERCENTILE_5:
                return percentile(values, 5);
            case PERCENTILE_10:
                return percentile(values, 10);
            case PERCENTILE_25:
                return percentile(values, 25);
            case PERCENTILE_75:
                return percentile(values, 75);
            case PERCENTILE_90:
                return percentile(values, 90);
            case PERCENTILE_95:
                return percentile(values, 95);
            case PERCENTILE_99:
                return percentile(values, 99);
            default:
                throw new IllegalArgumentException("Unsupported action: " + action);
        }
    }

    private static OptionalDouble percentile(double[] values, double p) {
        Percentile pct = new Percentile(p);
        return OptionalDouble.of(pct.evaluate(values));
    }
}
