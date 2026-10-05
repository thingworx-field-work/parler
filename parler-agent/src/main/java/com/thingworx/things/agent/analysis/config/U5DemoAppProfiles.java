package com.thingworx.things.agent.analysis.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.thingworx.things.agent.analysis.U5OperationAdmission;
import com.thingworx.things.agent.tools.AnalyzeCachedResultToolSchema;

/**
 * Demo App surface for U5 {@code analyze_cached_result} operations (DIK-5). Real Apps replace digests
 * via Thing/Service configuration. Does not open caches or execute analysis.
 */
public final class U5DemoAppProfiles {

    public enum Operation {
        OUTLIER("outlier", "u5-demo-outlier"),
        CHANGE_POINT("change_point", "u5-demo-change-point"),
        SPC("spc", "u5-demo-spc"),
        RELATIONSHIP("relationship", "u5-demo-relationship"),
        TREND("trend", "u5-demo-trend"),
        THRESHOLD_CROSSING("threshold_crossing", "u5-demo-threshold-crossing");

        private final String wireName;
        private final String profileDigest;

        Operation(String wireName, String profileDigest) {
            this.wireName = wireName;
            this.profileDigest = profileDigest;
        }

        public String wireName() {
            return wireName;
        }

        public String profileDigest() {
            return profileDigest;
        }

        public static Operation fromWire(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("operation required");
            }
            String key = name.trim().toLowerCase(Locale.ROOT);
            for (Operation op : values()) {
                if (op.wireName.equals(key)) {
                    return op;
                }
            }
            throw new IllegalArgumentException("unknown U5 operation: " + name);
        }
    }

    private U5DemoAppProfiles() {}

    public static String profileDigest(Operation operation) {
        return Objects.requireNonNull(operation, "operation").profileDigest();
    }

    public static List<String> advertisedOperations() {
        if (!U5OperationAdmission.analyzeEnabled()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(AnalyzeCachedResultToolSchema.OPERATIONS);
        return List.copyOf(out);
    }
}
