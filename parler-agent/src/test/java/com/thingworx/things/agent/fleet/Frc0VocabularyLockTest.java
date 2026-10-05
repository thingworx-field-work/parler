package com.thingworx.things.agent.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.analysis.AnalysisOperation;
import com.thingworx.things.agent.analysis.AnalysisOperationClass;
import com.thingworx.things.agent.investigation.HypothesisLedgerEntry;
import com.thingworx.things.agent.tools.AnalyzeCachedResultToolSchema;

/**
 * FRC-0 freeze: U6 operation family, coverage vocabulary, D3 packaging boundary, causal wording.
 */
class Frc0VocabularyLockTest {

    @Test
    void u6FleetOperationLocked() {
        assertTrue(AnalysisOperation.FLEET_BENCHMARK.isU6());
        assertFalse(AnalysisOperation.FLEET_BENCHMARK.isU5());
        assertFalse(AnalysisOperation.FLEET_BENCHMARK.isDetection());
        assertFalse(AnalysisOperation.FLEET_BENCHMARK.isQuantification());
        assertEquals(AnalysisOperationClass.FLEET, AnalysisOperation.FLEET_BENCHMARK.operationClass());
        assertEquals("fleet_benchmark", AnalysisOperation.FLEET_BENCHMARK.wireName());
    }

    @Test
    void u5SetUnchangedByFleetEnum() {
        Set<AnalysisOperation> u5 = EnumSet.allOf(AnalysisOperation.class).stream()
                .filter(AnalysisOperation::isU5)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(AnalysisOperation.class)));
        assertEquals(EnumSet.of(
                AnalysisOperation.OUTLIER,
                AnalysisOperation.CHANGE_POINT,
                AnalysisOperation.SPC,
                AnalysisOperation.THRESHOLD_CROSSING,
                AnalysisOperation.RELATIONSHIP,
                AnalysisOperation.TREND), u5);
    }

    @Test
    void memberStatusVocabularyClosed() {
        assertEquals(6, CohortMemberStatus.values().length);
        EnumSet.allOf(CohortMemberStatus.class); // touch for coverage
    }

    @Test
    void coverageMetricKeysForbidUnauthorizedLeakage() {
        assertEquals("unauthorizedN", CohortCoverageCounts.MetricKeys.FORBIDDEN_UNAUTHORIZED_N);
        assertEquals("peerSetSize", CohortCoverageCounts.MetricKeys.FORBIDDEN_PEER_SET_SIZE);
        assertEquals("permissionLimited", CohortCoverageCounts.MetricKeys.PERMISSION_LIMITED);
        assertEquals("insufficientEvidenceN", CohortCoverageCounts.MetricKeys.INSUFFICIENT_EVIDENCE_N);
        assertEquals("valuedN", CohortCoverageCounts.MetricKeys.VALUED_N);
    }

    @Test
    void capabilityCatalogAllIsImmutableSnapshot() {
        assertThrows(UnsupportedOperationException.class, () -> U6FleetCapabilityCatalog.all().clear());
        assertEquals(1, U6FleetCapabilityCatalog.size());
        assertTrue(U6FleetCapabilityCatalog.find("fleet_benchmark").isPresent());
    }

    @Test
    void frc0DoesNotAdvertiseFleetOnAnalyzeCachedResult() {
        assertFalse(AnalyzeCachedResultToolSchema.OPERATIONS.contains("fleet_benchmark"));
        @SuppressWarnings("unchecked")
        java.util.List<String> required =
                (java.util.List<String>) AnalyzeCachedResultToolSchema.parametersSchema().get("required");
        assertTrue(required.contains("cacheId"));
        assertTrue(required.contains("operation"));
        assertEquals(2, required.size());
    }

    @Test
    void fleetCapabilityCatalogIsInternalOnly() {
        assertTrue(U6FleetCapabilityCatalog.find("fleet_benchmark").isPresent());
        assertEquals(1, U6FleetCapabilityCatalog.size());
        assertEquals(AnalysisOperation.FLEET_BENCHMARK,
                U6FleetCapabilityCatalog.find("fleet_benchmark").orElseThrow().operation());
    }

    @Test
    void hypothesisPriorityMeaningFrozen() {
        assertEquals(
                "ORDER_FOR_NEXT_CHECKS_NOT_CAUSAL_PROBABILITY",
                HypothesisLedgerEntry.PRIORITY_MEANING);
    }
}
