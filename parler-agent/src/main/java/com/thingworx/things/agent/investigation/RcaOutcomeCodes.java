package com.thingworx.things.agent.investigation;

/** Topic reason codes from fleet-rca §8 used by FRC-3 G7 investigation. */
public final class RcaOutcomeCodes {

    public static final String INCIDENT_UNRESOLVED = "INCIDENT_UNRESOLVED";
    public static final String SEARCH_BOUNDARY_EXCEEDED = "SEARCH_BOUNDARY_EXCEEDED";
    public static final String CANDIDATE_SOURCE_UNAVAILABLE = "CANDIDATE_SOURCE_UNAVAILABLE";
    public static final String NO_SUPPORTED_CANDIDATE = "NO_SUPPORTED_CANDIDATE";
    public static final String PERMISSION_LIMITED = "PERMISSION_LIMITED";
    public static final String PARTIAL_EVENT_HISTORY = "PARTIAL_EVENT_HISTORY";
    public static final String CANCELLED = "CANCELLED";

    private RcaOutcomeCodes() {}
}
