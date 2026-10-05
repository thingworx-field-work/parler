package com.thingworx.things.agent.investigation;

import java.util.Objects;

/** One supporting, weakening, or unknown evidence-test contribution on a hypothesis ledger row. */
public final class HypothesisEvidenceRef {

    private final String evidenceRef;
    private final String testId;
    private final int contribution;
    private final String reason;

    private HypothesisEvidenceRef(String evidenceRef, String testId, int contribution, String reason) {
        this.testId = requireNonBlank(testId, "testId");
        this.evidenceRef = blankToNull(evidenceRef);
        this.contribution = contribution;
        String cleanedReason = blankToNull(reason);
        if (cleanedReason != null) {
            HypothesisLedgerEntry.rejectForbiddenWording(cleanedReason, "reason");
        }
        this.reason = cleanedReason;
    }

    public static HypothesisEvidenceRef support(String evidenceRef, String testId, int contribution) {
        if (contribution < 0) {
            throw new IllegalArgumentException("support contribution must be non-negative");
        }
        return new HypothesisEvidenceRef(evidenceRef, testId, contribution, null);
    }

    public static HypothesisEvidenceRef weaken(String evidenceRef, String testId, int contribution) {
        if (contribution < 0) {
            throw new IllegalArgumentException("weaken contribution must be non-negative");
        }
        return new HypothesisEvidenceRef(evidenceRef, testId, contribution, null);
    }

    public static HypothesisEvidenceRef unknown(String testId, String reason) {
        return new HypothesisEvidenceRef(null, testId, 0, requireNonBlank(reason, "reason"));
    }

    public String evidenceRef() {
        return evidenceRef;
    }

    public String testId() {
        return testId;
    }

    public int contribution() {
        return contribution;
    }

    public String reason() {
        return reason;
    }

    private static String requireNonBlank(String s, String name) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return s.trim();
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HypothesisEvidenceRef)) {
            return false;
        }
        HypothesisEvidenceRef other = (HypothesisEvidenceRef) o;
        return contribution == other.contribution
                && Objects.equals(evidenceRef, other.evidenceRef)
                && testId.equals(other.testId)
                && Objects.equals(reason, other.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(evidenceRef, testId, contribution, reason);
    }
}
