package com.thingworx.things.agent.analysis;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DIK-0 frozen catalog of planned U5 method ids/versions/complexity bounds. Not admission and not
 * tool advertisement — executors bind in DIK-2/3/4.
 */
public final class U5MethodCapabilityCatalog {

    private static final Map<String, U5MethodCapability> BY_ID;

    static {
        Map<String, U5MethodCapability> m = new LinkedHashMap<>();
        put(m, U5MethodCapability.builder()
                .methodId("robust_z")
                .operation(AnalysisOperation.OUTLIER)
                .complexityNote("streaming two-pass median/MAD; materialize eligible finite values")
                .maxEligiblePoints(100_000L)
                .build());
        put(m, U5MethodCapability.builder()
                .methodId("iqr")
                .operation(AnalysisOperation.OUTLIER)
                .complexityNote("streaming percentile fences; materialize eligible finite values")
                .maxEligiblePoints(100_000L)
                .build());
        put(m, U5MethodCapability.builder()
                .methodId("binary_segmentation")
                .operation(AnalysisOperation.CHANGE_POINT)
                .complexityNote("bounded binary segmentation O(k*n) with candidate cap")
                .maxEligiblePoints(50_000L)
                .build());
        put(m, U5MethodCapability.builder()
                .methodId("imr")
                .operation(AnalysisOperation.SPC)
                .complexityNote("I-MR limits + versioned run-rule catalog; streaming eligible series")
                .maxEligiblePoints(100_000L)
                .build());
        put(m, U5MethodCapability.builder()
                .methodId("spc_run_r1")
                .operation(AnalysisOperation.SPC)
                .complexityNote("run rule R1 (one point beyond 3σ); default enabled")
                .maxEligiblePoints(100_000L)
                .enabledByDefault(true)
                .build());
        put(m, U5MethodCapability.builder()
                .methodId("spc_run_r2")
                .operation(AnalysisOperation.SPC)
                .complexityNote("run rule R2 (2 of 3 beyond 2σ); opt-in")
                .maxEligiblePoints(100_000L)
                .enabledByDefault(false)
                .build());
        put(m, U5MethodCapability.builder()
                .methodId("spc_run_r3")
                .operation(AnalysisOperation.SPC)
                .complexityNote("run rule R3 (4 of 5 beyond 1σ); opt-in")
                .maxEligiblePoints(100_000L)
                .enabledByDefault(false)
                .build());
        put(m, U5MethodCapability.builder()
                .methodId("spc_run_r4")
                .operation(AnalysisOperation.SPC)
                .complexityNote("run rule R4 (8 consecutive one side); opt-in")
                .maxEligiblePoints(100_000L)
                .enabledByDefault(false)
                .build());
        put(m, U5MethodCapability.builder()
                .methodId("pearson")
                .operation(AnalysisOperation.RELATIONSHIP)
                .complexityNote("stable two-pass Pearson after alignment")
                .maxEligiblePoints(100_000L)
                .build());
        put(m, U5MethodCapability.builder()
                .methodId("spearman")
                .operation(AnalysisOperation.RELATIONSHIP)
                .complexityNote("average ranks then Pearson; materialize ranks")
                .maxEligiblePoints(50_000L)
                .build());
        put(m, U5MethodCapability.builder()
                .methodId("ols_pair")
                .operation(AnalysisOperation.RELATIONSHIP)
                .complexityNote("simple OLS y~x with residual summary")
                .maxEligiblePoints(100_000L)
                .build());
        put(m, U5MethodCapability.builder()
                .methodId("lag_scan")
                .operation(AnalysisOperation.RELATIONSHIP)
                .complexityNote("bounded lag grid after alignment; no silent sampling")
                .maxEligiblePoints(50_000L)
                .maxMaterializedPairs(2_000L)
                .build());
        put(m, U5MethodCapability.builder()
                .methodId("ols_trend")
                .operation(AnalysisOperation.TREND)
                .complexityNote("OLS on elapsed seconds from first observation")
                .maxEligiblePoints(100_000L)
                .build());
        put(m, U5MethodCapability.builder()
                .methodId("theil_sen")
                .operation(AnalysisOperation.TREND)
                .complexityNote("exact Theil–Sen; requires n*(n-1)/2 within pair budget")
                .maxEligiblePoints(2_000L)
                .maxMaterializedPairs(1_999_000L)
                .build());
        put(m, U5MethodCapability.builder()
                .methodId("threshold_crossing")
                .operation(AnalysisOperation.THRESHOLD_CROSSING)
                .complexityNote("horizon-gated crossing over fitted trend; §6.3.3 outcomes")
                .maxEligiblePoints(100_000L)
                .build());
        BY_ID = Collections.unmodifiableMap(m);
    }

    private U5MethodCapabilityCatalog() {}

    public static Optional<U5MethodCapability> find(String methodId) {
        if (methodId == null || methodId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_ID.get(methodId.trim()));
    }

    public static Collection<U5MethodCapability> all() {
        return BY_ID.values();
    }

    public static int size() {
        return BY_ID.size();
    }

    private static void put(Map<String, U5MethodCapability> m, U5MethodCapability c) {
        if (m.put(c.methodId(), c) != null) {
            throw new IllegalStateException("duplicate methodId: " + c.methodId());
        }
    }
}
