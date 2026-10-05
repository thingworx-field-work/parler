package com.thingworx.things.agent.analysis.config;

import com.thingworx.things.agent.tools.TabulateCachedResultToolSchema;
import com.thingworx.things.agent.transform.time.CalendarBucketCacheRunner;
import com.thingworx.things.agent.transform.time.CounterDeltaCacheRunner;
import com.thingworx.things.agent.transform.time.RollingStatsCacheRunner;
import com.thingworx.things.agent.transform.time.TimeWeightedCacheRunner;

/**
 * Demo App surface for computing-enhancement operations. Discovery only, like
 * {@link U4DemoAppProfiles}: it does not open caches or execute analysis. Counter rules have no
 * profile defaults; they arrive as explicit call arguments from a Playbook step or a user statement.
 */
public final class ComputingDemoAppProfiles {

    public enum Operation {
        COUNTER_DELTA(TabulateCachedResultToolSchema.MODE_COUNTER_DELTA, CounterDeltaCacheRunner.PROFILE_DIGEST),
        ROLLING_STATS(TabulateCachedResultToolSchema.MODE_ROLLING_STATS, RollingStatsCacheRunner.PROFILE_DIGEST),
        TIME_WEIGHTED(TabulateCachedResultToolSchema.MODE_TIME_WEIGHTED, TimeWeightedCacheRunner.PROFILE_DIGEST),
        CALENDAR_BUCKET(TabulateCachedResultToolSchema.MODE_CALENDAR_BUCKET,
                CalendarBucketCacheRunner.PROFILE_DIGEST);

        private final String mode;
        private final String profileDigest;

        Operation(String mode, String profileDigest) {
            this.mode = mode;
            this.profileDigest = profileDigest;
        }

        public String mode() {
            return mode;
        }

        public String profileDigest() {
            return profileDigest;
        }
    }

    private ComputingDemoAppProfiles() {}
}
