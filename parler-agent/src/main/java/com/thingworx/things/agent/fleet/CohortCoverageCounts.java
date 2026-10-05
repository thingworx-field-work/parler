package com.thingworx.things.agent.fleet;

/**
 * Authorized-only cohort coverage facts (fleet-rca D5). Never carries unauthorized member names
 * or an unauthorized peer-set size integer.
 *
 * <p>{@link #valuedN()} counts members that returned a finite metric from the batch source
 * ({@link CohortMemberStatus#ELIGIBLE_VALUE}). {@link #comparableN()} counts members that enter
 * the numeric distribution after U6 U4/quality gates and is always {@code <= valuedN}. Source-stage
 * helpers set {@code comparableN=0}; FRC-1 publishers set {@code comparableN} after gates.
 */
public final class CohortCoverageCounts {

    private final int requestedAuthorizedN;
    private final int returnedN;
    private final int valuedN;
    private final int comparableN;
    private final int noDataN;
    private final int incomparableN;
    private final int insufficientEvidenceN;
    private final int errorN;
    private final boolean permissionLimited;

    private CohortCoverageCounts(Builder b) {
        this.requestedAuthorizedN = requireNonNeg(b.requestedAuthorizedN, "requestedAuthorizedN");
        this.returnedN = requireNonNeg(b.returnedN, "returnedN");
        this.valuedN = requireNonNeg(b.valuedN, "valuedN");
        this.comparableN = requireNonNeg(b.comparableN, "comparableN");
        this.noDataN = requireNonNeg(b.noDataN, "noDataN");
        this.incomparableN = requireNonNeg(b.incomparableN, "incomparableN");
        this.insufficientEvidenceN = requireNonNeg(b.insufficientEvidenceN, "insufficientEvidenceN");
        this.errorN = requireNonNeg(b.errorN, "errorN");
        this.permissionLimited = b.permissionLimited;
        if (returnedN > requestedAuthorizedN) {
            throw new IllegalArgumentException("returnedN cannot exceed requestedAuthorizedN");
        }
        if (comparableN > valuedN) {
            throw new IllegalArgumentException("comparableN cannot exceed valuedN");
        }
        int partitioned = valuedN + noDataN + incomparableN + insufficientEvidenceN + errorN;
        if (partitioned != returnedN) {
            throw new IllegalArgumentException(
                    "valuedN + noDataN + incomparableN + insufficientEvidenceN + errorN ("
                            + partitioned + ") must equal returnedN (" + returnedN + ")");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public int requestedAuthorizedN() {
        return requestedAuthorizedN;
    }

    public int returnedN() {
        return returnedN;
    }

    /** Finite metric values returned by the batch source (pre U6 quality gate). */
    public int valuedN() {
        return valuedN;
    }

    /** Members admitted to the numeric distribution after U6 gates ({@code <= valuedN}). */
    public int comparableN() {
        return comparableN;
    }

    public int noDataN() {
        return noDataN;
    }

    public int incomparableN() {
        return incomparableN;
    }

    public int insufficientEvidenceN() {
        return insufficientEvidenceN;
    }

    public int errorN() {
        return errorN;
    }

    public boolean permissionLimited() {
        return permissionLimited;
    }

    /** Envelope metric keys for compact coverage projection (FRC-0 vocabulary freeze). */
    public static final class MetricKeys {
        public static final String REQUESTED_AUTHORIZED_N = "requestedAuthorizedN";
        public static final String RETURNED_N = "returnedN";
        public static final String VALUED_N = "valuedN";
        public static final String COMPARABLE_N = "comparableN";
        public static final String NO_DATA_N = "noDataN";
        public static final String INCOMPARABLE_N = "incomparableN";
        public static final String INSUFFICIENT_EVIDENCE_N = "insufficientEvidenceN";
        public static final String ERROR_N = "errorN";
        public static final String PERMISSION_LIMITED = "permissionLimited";
        /** Forbidden leakage keys — never emit. */
        public static final String FORBIDDEN_UNAUTHORIZED_N = "unauthorizedN";
        public static final String FORBIDDEN_PEER_SET_SIZE = "peerSetSize";

        private MetricKeys() {}
    }

    private static int requireNonNeg(int v, String name) {
        if (v < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return v;
    }

    public static final class Builder {
        private int requestedAuthorizedN;
        private int returnedN;
        private int valuedN;
        private int comparableN;
        private int noDataN;
        private int incomparableN;
        private int insufficientEvidenceN;
        private int errorN;
        private boolean permissionLimited;

        public Builder requestedAuthorizedN(int v) {
            this.requestedAuthorizedN = v;
            return this;
        }

        public Builder returnedN(int v) {
            this.returnedN = v;
            return this;
        }

        public Builder valuedN(int v) {
            this.valuedN = v;
            return this;
        }

        public Builder comparableN(int v) {
            this.comparableN = v;
            return this;
        }

        public Builder noDataN(int v) {
            this.noDataN = v;
            return this;
        }

        public Builder incomparableN(int v) {
            this.incomparableN = v;
            return this;
        }

        public Builder insufficientEvidenceN(int v) {
            this.insufficientEvidenceN = v;
            return this;
        }

        public Builder errorN(int v) {
            this.errorN = v;
            return this;
        }

        public Builder permissionLimited(boolean v) {
            this.permissionLimited = v;
            return this;
        }

        public CohortCoverageCounts build() {
            return new CohortCoverageCounts(this);
        }
    }
}
