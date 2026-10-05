package com.thingworx.things.agent.fleet;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.thingworx.things.agent.analysis.AnalysisOperation;

/**
 * Internal U6 fleet capability descriptors (FRC-0). Not model-visible admission.
 */
public final class U6FleetCapabilityCatalog {

    private static final Map<String, U6FleetCapability> BY_ID = new LinkedHashMap<>();

    static {
        register(U6FleetCapability.builder()
                .methodId("fleet_benchmark")
                .operation(AnalysisOperation.FLEET_BENCHMARK)
                .complexityNote("O(n log n) sort + O(n) rank/percentile/MAD over comparable members")
                .maxCohortMembers(500)
                .enabledByDefault(true)
                .build());
    }

    private U6FleetCapabilityCatalog() {}

    private static void register(U6FleetCapability c) {
        BY_ID.put(c.methodId(), c);
    }

    public static Optional<U6FleetCapability> find(String methodId) {
        if (methodId == null || methodId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_ID.get(methodId.trim()));
    }

    /** Immutable snapshot; callers cannot mutate the frozen registry. */
    public static Collection<U6FleetCapability> all() {
        return java.util.List.copyOf(BY_ID.values());
    }

    public static int size() {
        return BY_ID.size();
    }
}
