package com.thingworx.things.agent.investigation;

import java.util.Objects;

/**
 * Closed evidence-test entry from an investigation profile (fleet-rca §7.4). Weights order
 * next-check work only — never calibrated probability. When the profile has no reviewed weights,
 * ranking uses {@link #lexicographicOrder()} and must not invent numeric contributions (D7).
 */
public final class EvidenceTestSpec {

    private final String testId;
    private final int supportWeight;
    private final int weakenWeight;
    private final boolean blockingWeaken;
    private final String nextCheckTemplate;
    private final int lexicographicOrder;
    private final boolean highPriority;

    public EvidenceTestSpec(
            String testId,
            int supportWeight,
            int weakenWeight,
            boolean blockingWeaken,
            String nextCheckTemplate,
            int lexicographicOrder) {
        this(testId, supportWeight, weakenWeight, blockingWeaken, nextCheckTemplate, lexicographicOrder,
                lexicographicOrder == 0);
    }

    public EvidenceTestSpec(
            String testId,
            int supportWeight,
            int weakenWeight,
            boolean blockingWeaken,
            String nextCheckTemplate,
            int lexicographicOrder,
            boolean highPriority) {
        this.testId = requireNonBlank(testId, "testId");
        if (supportWeight < 0 || weakenWeight < 0) {
            throw new IllegalArgumentException("weights must be non-negative");
        }
        this.supportWeight = supportWeight;
        this.weakenWeight = weakenWeight;
        this.blockingWeaken = blockingWeaken;
        this.nextCheckTemplate = requireNonBlank(nextCheckTemplate, "nextCheckTemplate");
        HypothesisLedgerEntry.rejectForbiddenWording(this.nextCheckTemplate, "nextCheckTemplate");
        this.lexicographicOrder = lexicographicOrder;
        this.highPriority = highPriority;
    }

    public String testId() {
        return testId;
    }

    public int supportWeight() {
        return supportWeight;
    }

    public int weakenWeight() {
        return weakenWeight;
    }

    public boolean blockingWeaken() {
        return blockingWeaken;
    }

    public String nextCheckTemplate() {
        return nextCheckTemplate;
    }

    public int lexicographicOrder() {
        return lexicographicOrder;
    }

    /** Used for §7.4 "more completed high-priority tests" tiebreak. */
    public boolean highPriority() {
        return highPriority;
    }

    private static String requireNonBlank(String s, String name) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return s.trim();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EvidenceTestSpec)) {
            return false;
        }
        return testId.equals(((EvidenceTestSpec) o).testId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(testId);
    }
}
