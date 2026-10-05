package com.thingworx.things.agent.transform.time;

import java.time.Duration;

import com.thingworx.things.agent.transform.time.RollingOperator.WindowKind;

/** Minimal App-owned rolling profile for TQJ-5. */
public final class DemoRollingAppProfile {

    public static final String PROFILE_DIGEST = "demo-rolling-v1";
    public static final WindowKind DEFAULT_KIND = WindowKind.OBSERVATION_COUNT;
    public static final int DEFAULT_OBSERVATION_WINDOW = 5;
    public static final Duration DEFAULT_DURATION_WINDOW = Duration.ofHours(1);
    public static final int DEFAULT_MIN_SUPPORT = 1;

    private DemoRollingAppProfile() {}
}
