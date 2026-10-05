package com.thingworx.things.agent.transform.time;

import java.time.Duration;
import java.time.Instant;

/**
 * Minimal App-owned resample profile for TQJ-5. Real Apps replace this with versioned config;
 * Core only validates and executes.
 */
public final class DemoResampleAppProfile {

    public static final String PROFILE_DIGEST = "demo-resample-v1";
    public static final Duration DEFAULT_BUCKET = Duration.ofHours(1);
    public static final Aggregation DEFAULT_AGGREGATION = Aggregation.MEAN;
    public static final MissingPolicy DEFAULT_MISSING = MissingPolicy.NULL;

    private DemoResampleAppProfile() {}

    public static BucketAssigner assigner(Instant windowStart) {
        return BucketAssigner.fixedDuration(windowStart, DEFAULT_BUCKET);
    }

    public static Aggregation aggregation() {
        return DEFAULT_AGGREGATION;
    }

    public static MissingPolicy missingPolicy() {
        return DEFAULT_MISSING;
    }
}
