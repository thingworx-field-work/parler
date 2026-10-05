package com.thingworx.things.agent.analysis;

import java.util.Objects;
import java.util.Optional;

/**
 * DIK-1 method registry over the DIK-0 {@link U5MethodCapabilityCatalog}. Validates enablement and
 * complexity caps; does not advertise tools or bind executors (DIK-2/3/4).
 */
public final class U5MethodRegistry {

    public static final String UNAVAILABLE_REASON = "U5_METHOD_UNAVAILABLE";
    public static final String BUDGET_EXCEEDED_REASON = "U5_METHOD_BUDGET_EXCEEDED";

    private U5MethodRegistry() {}

    public static Optional<U5MethodCapability> find(String methodId) {
        return U5MethodCapabilityCatalog.find(methodId);
    }

    /**
     * Resolve an enabled capability or throw with a stable unavailable reason.
     */
    /**
     * Resolve a catalogued capability. Opt-in methods ({@code enabledByDefault=false}) still resolve
     * for App configuration; advertisement/admission binding remains DIK-5.
     */
    public static U5MethodCapability requireEnabled(String methodId) {
        return U5MethodCapabilityCatalog.find(methodId)
                .orElseThrow(() -> new IllegalArgumentException(UNAVAILABLE_REASON + ": unknown methodId"));
    }

    /**
     * Assert eligible points / materialized pairs fit the capability caps. Throws
     * {@link IllegalStateException} with {@link #BUDGET_EXCEEDED_REASON} on overflow.
     */
    public static void assertWithinComplexity(U5MethodCapability capability, long eligiblePoints,
            long materializedPairs) {
        Objects.requireNonNull(capability, "capability");
        if (eligiblePoints < 0L || materializedPairs < 0L) {
            throw new IllegalArgumentException("counts must be non-negative");
        }
        if (eligiblePoints > capability.maxEligiblePoints()) {
            throw new IllegalStateException(BUDGET_EXCEEDED_REASON + ": eligiblePoints");
        }
        if (capability.maxMaterializedPairs() > 0L
                && materializedPairs > capability.maxMaterializedPairs()) {
            throw new IllegalStateException(BUDGET_EXCEEDED_REASON + ": materializedPairs");
        }
    }

    public static AnalysisMethodDescriptor descriptor(String methodId, String profileDigest) {
        return requireEnabled(methodId).toMethodDescriptor(profileDigest);
    }
}
