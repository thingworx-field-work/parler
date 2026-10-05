package com.thingworx.things.agent.fleet;

/**
 * Topic reason codes from fleet-rca §8 used by FRC-2 distribution packaging. Mapped into shared
 * G18 categories by later envelope/Playbook consumers — not a second status vocabulary.
 */
public final class FleetOutcomeCodes {

    public static final String NO_COMPARABLE_MEMBERS = "NO_COMPARABLE_MEMBERS";
    public static final String FOCUS_NOT_IN_COHORT = "FOCUS_NOT_IN_COHORT";
    public static final String COHORT_PARTIAL = "COHORT_PARTIAL";

    private FleetOutcomeCodes() {}
}
