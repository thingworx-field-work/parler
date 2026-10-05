package com.thingworx.things.agent.fleet;

/**
 * Expected unit/grain/method for G5 comparability gates (fleet-rca §7.1 step 4). Members whose
 * source row carries a mismatched lineage are demoted to {@link CohortMemberStatus#INCOMPARABLE}.
 */
public final class MetricComparabilitySpec {

    private final String expectedUnit;
    private final String expectedGrain;
    private final String expectedMethodId;

    public MetricComparabilitySpec(String expectedUnit, String expectedGrain, String expectedMethodId) {
        this.expectedUnit = requireNonBlank(expectedUnit, "expectedUnit");
        this.expectedGrain = requireNonBlank(expectedGrain, "expectedGrain");
        this.expectedMethodId = requireNonBlank(expectedMethodId, "expectedMethodId");
    }

    public String expectedUnit() {
        return expectedUnit;
    }

    public String expectedGrain() {
        return expectedGrain;
    }

    public String expectedMethodId() {
        return expectedMethodId;
    }

    public boolean matches(CohortBatchMemberRow row) {
        if (row == null || row.status() != CohortMemberStatus.ELIGIBLE_VALUE) {
            return false;
        }
        return expectedUnit.equals(row.unit())
                && expectedGrain.equals(row.grain())
                && expectedMethodId.equals(row.methodId());
    }

    private static String requireNonBlank(String s, String name) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return s.trim();
    }
}
