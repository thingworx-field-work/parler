package com.thingworx.things.agent.analysis.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Projected numeric series with finite/missing accounting and optional unit (DIK-1). Does not own
 * cache I/O — callers project from {@code TypedTimeSeries} / streams.
 */
public final class NumericSeries {

    private final List<NumericObservation> observations;
    private final FiniteMissingAccounting accounting;
    private final String valueUnit;
    private final boolean inputsFullyScanned;

    public NumericSeries(List<NumericObservation> observations, String valueUnit, boolean inputsFullyScanned) {
        this.observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        long finite = 0L;
        long missing = 0L;
        long nonFinite = 0L;
        for (NumericObservation o : this.observations) {
            if (o.isFinite()) {
                finite++;
            } else if (Double.isNaN(o.value())) {
                missing++;
            } else {
                nonFinite++;
            }
        }
        this.accounting = new FiniteMissingAccounting(this.observations.size(), finite, missing, nonFinite);
        this.valueUnit = blankToNull(valueUnit);
        this.inputsFullyScanned = inputsFullyScanned;
    }

    public static NumericSeries fromFiniteValues(double[] finiteValues, String valueUnit) {
        List<NumericObservation> obs = new ArrayList<>();
        if (finiteValues != null) {
            for (int i = 0; i < finiteValues.length; i++) {
                obs.add(new NumericObservation(null, i, finiteValues[i]));
            }
        }
        return new NumericSeries(obs, valueUnit, true);
    }

    public List<NumericObservation> observations() {
        return observations;
    }

    public FiniteMissingAccounting accounting() {
        return accounting;
    }

    public String valueUnit() {
        return valueUnit;
    }

    public boolean inputsFullyScanned() {
        return inputsFullyScanned;
    }

    /** Finite values only, in observation order. */
    public double[] finiteValues() {
        double[] out = new double[(int) accounting.finite()];
        int j = 0;
        for (NumericObservation o : observations) {
            if (o.isFinite()) {
                out[j++] = o.value();
            }
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
