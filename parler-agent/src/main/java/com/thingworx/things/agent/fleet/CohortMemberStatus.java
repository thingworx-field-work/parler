package com.thingworx.things.agent.fleet;

/**
 * Per-member outcome on the U6 cohort batch-source contract (FRC-0). App Services MUST use these
 * statuses; they MUST NOT invent a competing vocabulary or treat unauthorized identity as
 * {@link #NO_DATA}.
 */
public enum CohortMemberStatus {
    /**
     * Authorized member returned a finite metric value that already passed the batch source's
     * unit/grain/window/method comparability checks. Sources MUST emit {@link #INCOMPARABLE}
     * (not this status) for those mismatches. U6 may still demote valued members to
     * {@link #INSUFFICIENT_EVIDENCE} under quality/support gates before publishing
     * {@code comparableN}.
     */
    ELIGIBLE_VALUE,
    /** Authorized member had no data in the requested window. */
    NO_DATA,
    /** Authorized member data failed quality/support gates (not absence). */
    INSUFFICIENT_EVIDENCE,
    /** Authorized member failed unit/grain/window/method comparability. */
    INCOMPARABLE,
    /** Authorized member fetch/compute failed. */
    ERROR,
    /**
     * Member could not be authorized under the current principal. Never counted in
     * authorized-only coverage integers; surfaces only as {@code permissionLimited=true}.
     */
    PERMISSION_LIMITED
}
