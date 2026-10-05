package com.thingworx.things.agent.analysis.config;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Demo App surface for U6 G5/G7 profiles (FRC-4). Real Apps replace digests via Thing/Service
 * configuration. Discovery-only — does not open caches, advertise tools, or execute analysis.
 *
 * <p>D3 packaging: G5 remains App/Playbook-invoked (not model-advertised on
 * {@code analyze_cached_result}); G7 remains Playbook-only.
 */
public final class U6DemoAppProfiles {

    public enum Surface {
        FLEET_BENCHMARK("fleet_benchmark", "u6-demo-fleet-peer-v1"),
        RCA_INVESTIGATION("rca_investigation", "u6-demo-rca-investigation-v1");

        private final String wireName;
        private final String profileDigest;

        Surface(String wireName, String profileDigest) {
            this.wireName = wireName;
            this.profileDigest = profileDigest;
        }

        public String wireName() {
            return wireName;
        }

        public String profileDigest() {
            return profileDigest;
        }

        public static Surface fromWire(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("surface required");
            }
            String key = name.trim().toLowerCase(Locale.ROOT);
            for (Surface s : values()) {
                if (s.wireName.equals(key)) {
                    return s;
                }
            }
            throw new IllegalArgumentException("unknown U6 surface: " + name);
        }
    }

    private U6DemoAppProfiles() {}

    public static String profileDigest(Surface surface) {
        return Objects.requireNonNull(surface, "surface").profileDigest();
    }

    /** Model-advertised U6 operations — empty while D3 keeps G5 App-invoked. */
    public static List<String> advertisedOperations() {
        return List.of();
    }
}
