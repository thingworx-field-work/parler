package com.thingworx.things.agent.fleet;

/**
 * Typed FRC-1 collection/gate failure. Reason codes align with fleet-rca §8
 * ({@code COHORT_BUDGET_EXCEEDED}, {@code BATCH_SOURCE_PARTIAL} context, membership integrity).
 */
public final class CohortCollectionException extends RuntimeException {

    public static final String COHORT_BUDGET_EXCEEDED = "COHORT_BUDGET_EXCEEDED";
    public static final String COHORT_MEMBER_DUPLICATE = "COHORT_MEMBER_DUPLICATE";
    public static final String COHORT_MEMBER_OMITTED = "COHORT_MEMBER_OMITTED";

    private final String reasonCode;

    public CohortCollectionException(String reasonCode, String message) {
        super(message);
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode required");
        }
        this.reasonCode = reasonCode.trim();
    }

    public String reasonCode() {
        return reasonCode;
    }
}
