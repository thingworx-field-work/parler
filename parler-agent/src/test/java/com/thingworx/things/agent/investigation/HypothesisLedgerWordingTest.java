package com.thingworx.things.agent.investigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class HypothesisLedgerWordingTest {

    @Test
    void acceptsAssociationWordingAndPriorityMeaning() {
        HypothesisLedgerEntry entry = HypothesisLedgerEntry.builder()
                .candidateId("c1")
                .candidateKind(CandidateKind.SIGNAL)
                .statement("Vibration RMS rose before the stop; association only")
                .investigationPriority(12)
                .supports(List.of(HypothesisEvidenceRef.support("cache:abc", "anomaly_near_incident", 8)))
                .weakens(List.of(HypothesisEvidenceRef.weaken("cache:def", "maintenance_cleared", 3)))
                .unknown(List.of(HypothesisEvidenceRef.unknown("event_cooccur", "event history partial")))
                .nextChecks(List.of("Inspect upstream dryer differential pressure"))
                .unsearchedScope(List.of("batch genealogy beyond depth 1"))
                .build();
        assertEquals(HypothesisLedgerEntry.PRIORITY_MEANING, entry.priorityMeaning());
        assertEquals(12, entry.investigationPriority());
    }

    @Test
    void rejectsProbabilityWording() {
        assertThrows(IllegalArgumentException.class, () -> HypothesisLedgerEntry.builder()
                .candidateId("c1")
                .candidateKind(CandidateKind.EVENT)
                .statement("High confidence root cause")
                .investigationPriority(1)
                .build());
    }

    @Test
    void rejectsProbabilityInNextChecks() {
        assertThrows(IllegalArgumentException.class, () -> HypothesisLedgerEntry.builder()
                .candidateId("c1")
                .candidateKind(CandidateKind.EVENT)
                .statement("Alarm co-occurred within window")
                .investigationPriority(1)
                .nextChecks(List.of("Raise likelihood score with operator"))
                .build());
    }

    @Test
    void rejectsProbabilityInUnsearchedScope() {
        assertThrows(IllegalArgumentException.class, () -> HypothesisLedgerEntry.builder()
                .candidateId("c1")
                .candidateKind(CandidateKind.EVENT)
                .statement("Alarm co-occurred within window")
                .investigationPriority(1)
                .unsearchedScope(List.of("root cause probability not searched beyond depth 1"))
                .build());
    }

    @Test
    void rejectsProbabilityInUnknownReason() {
        assertThrows(IllegalArgumentException.class,
                () -> HypothesisEvidenceRef.unknown("event_cooccur", "causal probability unknown"));
    }
}
