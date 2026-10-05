package com.thingworx.things.agent.quality;

import java.time.Duration;

/**
 * Minimal App-owned quality profile for TQJ-5. Real Apps replace this with versioned config from
 * their Thing/Service surface; Core only validates and executes.
 */
public final class DemoQualityAppProfile {

    public static final String PROFILE_DIGEST = "demo-quality-v1";

    private DemoQualityAppProfile() {}

    /** Default 5-minute cadence quality profile for the first App binding. */
    public static QualityProfile defaultProfile() {
        return QualityProfile.builder()
                .profileDigest(PROFILE_DIGEST)
                .expectedCadence(Duration.ofMinutes(5))
                .freshnessMaxAge(Duration.ofMinutes(15))
                .flatlineEpsilon(0d)
                .flatlineMinDuration(Duration.ofMinutes(15))
                .flatlineMinSupport(4)
                .cadenceDriftRatio(2.0d)
                .build();
    }
}
