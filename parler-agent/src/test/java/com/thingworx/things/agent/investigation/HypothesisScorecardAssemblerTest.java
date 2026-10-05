package com.thingworx.things.agent.investigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class HypothesisScorecardAssemblerTest {

    private static ResolvedCandidate signal(String id) {
        return new ResolvedCandidate(id, CandidateKind.SIGNAL, "Candidate " + id, 0, "asset");
    }

    private static InvestigationProfile lexProfile() {
        // No reviewed weights: ranking must use lexicographic order only (D7).
        return InvestigationProfile.of(
                "lex-demo",
                List.of(
                        new EvidenceTestSpec("alpha", 0, 0, false, "Check alpha", 0, true),
                        new EvidenceTestSpec("beta", 0, 0, false, "Check beta", 1, false),
                        new EvidenceTestSpec("gamma", 0, 0, false, "Check gamma", 2, true)),
                false);
    }

    @Test
    void noReviewedWeightsEmitsZeroContributionsAndZeroPriority() {
        List<ResolvedCandidate> candidates = List.of(signal("signal:a"), signal("signal:b"));
        List<CandidateEvidenceObservation> observations = List.of(
                CandidateEvidenceObservation.support("signal:a", "beta", "cache:b"),
                CandidateEvidenceObservation.support("signal:b", "alpha", "cache:a"));
        List<HypothesisLedgerEntry> ledger = HypothesisScorecardAssembler.assemble(
                lexProfile(),
                candidates,
                observations,
                Map.of(),
                List.of());

        for (HypothesisLedgerEntry e : ledger) {
            assertEquals(0, e.investigationPriority());
            for (HypothesisEvidenceRef ref : e.supports()) {
                assertEquals(0, ref.contribution(), "must not invent 10-order or default-1 weights");
            }
            for (HypothesisEvidenceRef ref : e.weakens()) {
                assertEquals(0, ref.contribution());
            }
        }
        // Lower lexicographic support test (alpha=0) ranks ahead of beta=1.
        assertEquals("signal:b", ledger.get(0).candidateId());
        assertEquals("signal:a", ledger.get(1).candidateId());
    }

    @Test
    void highPriorityTiebreakCountsOnlyHighPriorityTests() {
        // Both candidates support a low-priority test; only signal:hp also completes a high-priority test.
        List<ResolvedCandidate> candidates = List.of(signal("signal:lp"), signal("signal:hp"));
        List<CandidateEvidenceObservation> observations = List.of(
                CandidateEvidenceObservation.support("signal:lp", "beta", "cache:lp"),
                CandidateEvidenceObservation.support("signal:hp", "beta", "cache:hp-b"),
                CandidateEvidenceObservation.support("signal:hp", "gamma", "cache:hp-g"));
        List<HypothesisLedgerEntry> ledger = HypothesisScorecardAssembler.assemble(
                lexProfile(),
                candidates,
                observations,
                Map.of(),
                List.of());

        // Same best support lex order (beta=1); high-priority completion (gamma) breaks the tie.
        assertEquals("signal:hp", ledger.get(0).candidateId());
        assertEquals("signal:lp", ledger.get(1).candidateId());
        assertTrue(ledger.get(0).supports().stream().anyMatch(r -> "gamma".equals(r.testId())));
        assertFalse(ledger.get(1).supports().stream().anyMatch(r -> r.testId().equals("gamma")));
    }

    @Test
    void numericWeightsStillAccumulateReviewedContributions() {
        InvestigationProfile weighted = InvestigationProfile.of(
                "weighted",
                List.of(new EvidenceTestSpec("t1", 5, 2, false, "Run t1", 0, true)),
                true);
        List<HypothesisLedgerEntry> ledger = HypothesisScorecardAssembler.assemble(
                weighted,
                List.of(signal("signal:x")),
                List.of(CandidateEvidenceObservation.support("signal:x", "t1", "cache:x")),
                Map.of(),
                List.of());
        assertEquals(1, ledger.size());
        assertEquals(5, ledger.get(0).investigationPriority());
        assertEquals(5, ledger.get(0).supports().get(0).contribution());
    }

    @Test
    void unweightedBlockingWeakenRanksAfterOrdinarySupportedCandidate() {
        // Alpha support + blocking weaken must not beat beta support alone.
        InvestigationProfile profile = InvestigationProfile.of(
                "lex-block",
                List.of(
                        new EvidenceTestSpec("alpha", 0, 0, false, "Check alpha", 0, true),
                        new EvidenceTestSpec("beta", 0, 0, false, "Check beta", 1, false),
                        new EvidenceTestSpec("block", 0, 0, true, "Blocking contradict", 3, false)),
                false);
        List<ResolvedCandidate> candidates = List.of(signal("signal:blocked"), signal("signal:ok"));
        List<CandidateEvidenceObservation> observations = List.of(
                CandidateEvidenceObservation.support("signal:blocked", "alpha", "cache:a"),
                CandidateEvidenceObservation.weaken("signal:blocked", "block", "cache:w"),
                CandidateEvidenceObservation.support("signal:ok", "beta", "cache:b"));
        List<HypothesisLedgerEntry> ledger = HypothesisScorecardAssembler.assemble(
                profile, candidates, observations, Map.of(), List.of());
        assertEquals("signal:ok", ledger.get(0).candidateId());
        assertEquals("signal:blocked", ledger.get(1).candidateId());
        assertEquals(0, ledger.get(1).investigationPriority());
        assertFalse(ledger.get(0).deprioritized());
        assertTrue(ledger.get(1).deprioritized());
        assertFalse(ledger.get(1).supports().isEmpty());
        assertFalse(ledger.get(1).weakens().isEmpty());
    }
}
