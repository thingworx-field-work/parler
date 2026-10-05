package com.thingworx.things.agent.analysis;

/**
 * Operation family identifiers on the base {@link AnalysisEnvelope}. U4 landed transform/quality/
 * join families; U5 (DIK-0) adds detection/quantification families; U6 (FRC-0) adds
 * {@link #FLEET_BENCHMARK}. This enum is not a method registry and does not advertise tools.
 */
public enum AnalysisOperation {
    // U4
    RESAMPLE(AnalysisOperationClass.TRANSFORM),
    ROLLING(AnalysisOperationClass.TRANSFORM),
    RATE_OF_CHANGE(AnalysisOperationClass.TRANSFORM),
    PERIOD_COMPARE(AnalysisOperationClass.TRANSFORM),
    QUALITY(AnalysisOperationClass.TRANSFORM),
    EXACT_JOIN(AnalysisOperationClass.TRANSFORM),
    // U5
    OUTLIER(AnalysisOperationClass.DETECTION),
    CHANGE_POINT(AnalysisOperationClass.DETECTION),
    SPC(AnalysisOperationClass.DETECTION),
    THRESHOLD_CROSSING(AnalysisOperationClass.DETECTION),
    RELATIONSHIP(AnalysisOperationClass.QUANTIFICATION),
    TREND(AnalysisOperationClass.QUANTIFICATION),
    // U6
    FLEET_BENCHMARK(AnalysisOperationClass.FLEET),
    // computing-enhancement CF-05
    COUNTER_DELTA(AnalysisOperationClass.MEASUREMENT),
    // computing-enhancement CF-52
    ROLLING_STATS(AnalysisOperationClass.MEASUREMENT),
    // computing-enhancement CF-01
    TIME_WEIGHTED(AnalysisOperationClass.MEASUREMENT),
    // computing-enhancement CF-03
    CALENDAR_BUCKET(AnalysisOperationClass.MEASUREMENT);

    private final AnalysisOperationClass operationClass;

    AnalysisOperation(AnalysisOperationClass operationClass) {
        this.operationClass = operationClass;
    }

    public AnalysisOperationClass operationClass() {
        return operationClass;
    }

    public boolean isU5() {
        return operationClass == AnalysisOperationClass.DETECTION
                || operationClass == AnalysisOperationClass.QUANTIFICATION;
    }

    /** U6 fleet/cohort operation families (FRC-0+). */
    public boolean isU6() {
        return operationClass == AnalysisOperationClass.FLEET;
    }

    /** Computing-enhancement measurement families (CF-05+). */
    public boolean isMeasurement() {
        return operationClass == AnalysisOperationClass.MEASUREMENT;
    }

    public boolean isDetection() {
        return operationClass == AnalysisOperationClass.DETECTION;
    }

    public boolean isQuantification() {
        return operationClass == AnalysisOperationClass.QUANTIFICATION;
    }

    /** Wire / metrics form ({@code threshold_crossing}, {@code fleet_benchmark}). */
    public String wireName() {
        return name().toLowerCase();
    }
}
