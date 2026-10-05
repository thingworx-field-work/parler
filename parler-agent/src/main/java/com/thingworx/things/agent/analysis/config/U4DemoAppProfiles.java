package com.thingworx.things.agent.analysis.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.thingworx.things.agent.join.DemoExactJoinAppProfile;
import com.thingworx.things.agent.quality.DemoQualityAppProfile;
import com.thingworx.things.agent.tools.TabulateCachedResultToolSchema;
import com.thingworx.things.agent.transform.time.DemoResampleAppProfile;
import com.thingworx.things.agent.transform.time.DemoRollingAppProfile;
import com.thingworx.things.agent.transform.time.PeriodCompareCacheRunner;
import com.thingworx.things.agent.transform.time.RateOfChangeCacheRunner;

/**
 * Demo App surface for U4 TQJ-5 operation profiles. Real Apps replace these digests / defaults via
 * Thing or Service configuration; Playbooks invoke the same {@code tabulate_cached_result} modes
 * and do not receive a second implementation (design §5 / TQJ-5).
 *
 * <p>This class is documentation-and-discovery oriented for Apps: it does not open caches or
 * execute analysis. Execution remains in the shared mode executors / runners.
 */
public final class U4DemoAppProfiles {

    /** U4 operations exposed as governed {@code tabulate_cached_result} modes. */
    public enum Operation {
        EXACT_JOIN(TabulateCachedResultToolSchema.MODE_EXACT_JOIN, DemoExactJoinAppProfile.PROFILE_DIGEST),
        QUALITY(TabulateCachedResultToolSchema.MODE_QUALITY, DemoQualityAppProfile.PROFILE_DIGEST),
        RESAMPLE(TabulateCachedResultToolSchema.MODE_RESAMPLE, DemoResampleAppProfile.PROFILE_DIGEST),
        ROLLING(TabulateCachedResultToolSchema.MODE_ROLLING, DemoRollingAppProfile.PROFILE_DIGEST),
        RATE_OF_CHANGE(TabulateCachedResultToolSchema.MODE_RATE_OF_CHANGE, RateOfChangeCacheRunner.PROFILE_DIGEST),
        PERIOD_COMPARE(TabulateCachedResultToolSchema.MODE_PERIOD_COMPARE, PeriodCompareCacheRunner.PROFILE_DIGEST);

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

        public static Operation fromMode(String mode) {
            if (mode == null || mode.isBlank()) {
                throw new IllegalArgumentException("mode required");
            }
            String key = mode.trim().toLowerCase(Locale.ROOT);
            for (Operation op : values()) {
                if (op.mode.equals(key)) {
                    return op;
                }
            }
            throw new IllegalArgumentException("unknown U4 mode: " + mode);
        }
    }

    private U4DemoAppProfiles() {}

    /** Profile digest used by the demo App binding for {@code operation}. */
    public static String profileDigest(Operation operation) {
        return Objects.requireNonNull(operation, "operation").profileDigest();
    }

    /**
     * Model-visible U4 mode names under the current admission flags (subset of
     * {@link TabulateCachedResultToolSchema#advertisedModes()}).
     */
    public static List<String> advertisedU4Modes() {
        List<String> out = new ArrayList<>();
        List<String> advertised = TabulateCachedResultToolSchema.advertisedModes();
        for (Operation op : Operation.values()) {
            if (advertised.contains(op.mode)) {
                out.add(op.mode);
            }
        }
        return List.copyOf(out);
    }
}
