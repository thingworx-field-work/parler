package com.thingworx.things.agent.investigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

class BoundedInvestigationEngineTest {

    private static final HalfOpenWindow WINDOW = HalfOpenWindow.of(
            Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-02T00:00:00Z"));

    private static IncidentAnchor incident() {
        return IncidentAnchor.builder()
                .focusAssetId("compressor.line1")
                .eventId("inc-1")
                .evidenceWindow(WINDOW)
                .build();
    }

    private static RcaInvestigationRequest request(InvestigationBudget budget) {
        return RcaInvestigationRequest.builder()
                .incident(incident())
                .investigationProfileId("rca-demo")
                .budget(budget)
                .build();
    }

    private static InvestigationProfile profile() {
        return InvestigationProfile.of(
                "rca-demo",
                List.of(
                        new EvidenceTestSpec("event_co_occurrence", 5, 0, false, "Review co-occurring events", 0),
                        new EvidenceTestSpec("u5_change_point", 3, 2, true, "Run change-point on candidate signal", 1),
                        new EvidenceTestSpec("u5_outlier", 2, 1, false, "Run outlier check on candidate signal", 2)),
                true);
    }

    private static CandidateCatalog catalog(List<CatalogSignalEntry> extraSignals) {
        List<CatalogSignalEntry> signals = new java.util.ArrayList<>();
        signals.add(new CatalogSignalEntry("role.vibration.rms", "Compressor", "RUNNING"));
        signals.addAll(extraSignals);
        return CandidateCatalog.builder()
                .catalogId("demo-rca-v1")
                .maxRelationDepth(2)
                .maxRelationNodes(8)
                .relations(List.of(new CatalogRelationEntry(
                        "feeds", "compressor.line1", "dryer.line1", 1)))
                .signals(signals)
                .serviceBindings(List.of(
                        new CatalogServiceBinding(
                                CatalogServiceBinding.BindingKind.EVENT,
                                "SCPA_EventHelper",
                                "QueryAlarms",
                                3_600_000L),
                        new CatalogServiceBinding(
                                CatalogServiceBinding.BindingKind.MAINTENANCE,
                                "SCPA_MaintHelper",
                                "QueryWorkOrders",
                                86_400_000L),
                        new CatalogServiceBinding(
                                CatalogServiceBinding.BindingKind.BATCH,
                                "SCPA_BatchHelper",
                                "QueryBatches",
                                86_400_000L)))
                .build();
    }

    @Test
    void knownCandidateWithSupportIsSuccess() {
        var events = new InvestigationEvidenceSource.EventBatch(
                List.of(new InvestigationEvent(
                        "e1",
                        "compressor.line1",
                        Instant.parse("2026-07-01T12:00:00Z"),
                        CandidateKind.EVENT)),
                CompletenessStatus.COMPLETE,
                false,
                false);
        var observations = List.of(CandidateEvidenceObservation.support(
                "signal:role.vibration.rms", "u5_change_point", "cache:cp-1"));
        RcaInvestigationResult result = BoundedInvestigationEngine.investigate(
                request(InvestigationBudget.defaults()),
                catalog(List.of()),
                profile(),
                FixtureInvestigationEvidenceSource.of(events, observations));
        assertEquals(EvidenceStatus.SUCCESS, result.status());
        assertTrue(result.ledger().stream().anyMatch(e -> !e.supports().isEmpty()));
        assertFalse(result.reasonCodes().contains(RcaOutcomeCodes.NO_SUPPORTED_CANDIDATE));
        assertEquals(HypothesisLedgerEntry.PRIORITY_MEANING, result.ledger().get(0).priorityMeaning());
    }

    @Test
    void multipleSupportedCandidatesRankByPriority() {
        var events = new InvestigationEvidenceSource.EventBatch(
                List.of(), CompletenessStatus.COMPLETE, false, false);
        var observations = List.of(
                CandidateEvidenceObservation.support(
                        "signal:role.vibration.rms", "u5_change_point", "cache:cp-1"),
                CandidateEvidenceObservation.support(
                        "upstream:dryer.line1", "u5_outlier", "cache:out-1"));
        RcaInvestigationResult result = BoundedInvestigationEngine.investigate(
                request(InvestigationBudget.defaults()),
                catalog(List.of()),
                profile(),
                FixtureInvestigationEvidenceSource.of(events, observations));
        assertEquals(EvidenceStatus.SUCCESS, result.status());
        long supported = result.ledger().stream().filter(e -> !e.supports().isEmpty()).count();
        assertTrue(supported >= 2);
        // Higher support weight first.
        assertEquals("signal:role.vibration.rms", result.ledger().get(0).candidateId());
    }

    @Test
    void noSupportedCandidateIsValidNoFinding() {
        var events = new InvestigationEvidenceSource.EventBatch(
                List.of(), CompletenessStatus.COMPLETE, false, false);
        RcaInvestigationResult result = BoundedInvestigationEngine.investigate(
                request(InvestigationBudget.defaults()),
                catalog(List.of()),
                profile(),
                FixtureInvestigationEvidenceSource.of(events, List.of()));
        assertEquals(EvidenceStatus.NO_FINDING, result.status());
        assertTrue(result.reasonCodes().contains(RcaOutcomeCodes.NO_SUPPORTED_CANDIDATE));
        assertTrue(result.summaryFacts().stream()
                .anyMatch(s -> s.contains("no_supported_candidate_in_searched_scope")));
    }

    @Test
    void permissionLimitedIsDisclosed() {
        var events = new InvestigationEvidenceSource.EventBatch(
                List.of(), CompletenessStatus.COMPLETE, true, false);
        var observations = List.of(CandidateEvidenceObservation.support(
                "signal:role.vibration.rms", "u5_outlier", "cache:o-1"));
        RcaInvestigationResult result = BoundedInvestigationEngine.investigate(
                request(InvestigationBudget.defaults()),
                catalog(List.of()),
                profile(),
                FixtureInvestigationEvidenceSource.of(events, observations));
        assertTrue(result.reasonCodes().contains(RcaOutcomeCodes.PERMISSION_LIMITED));
        assertEquals(EvidenceStatus.SUCCESS, result.status());
    }

    @Test
    void partialEventHistoryIsDisclosed() {
        var events = new InvestigationEvidenceSource.EventBatch(
                List.of(new InvestigationEvent(
                        "e1",
                        "compressor.line1",
                        Instant.parse("2026-07-01T01:00:00Z"),
                        CandidateKind.EVENT)),
                CompletenessStatus.PARTIAL,
                false,
                false);
        RcaInvestigationResult result = BoundedInvestigationEngine.investigate(
                request(InvestigationBudget.defaults()),
                catalog(List.of()),
                profile(),
                FixtureInvestigationEvidenceSource.of(events, List.of()));
        assertTrue(result.reasonCodes().contains(RcaOutcomeCodes.PARTIAL_EVENT_HISTORY));
        // Co-occurrence still supports the EVENT candidate when events match.
        assertEquals(EvidenceStatus.SUCCESS, result.status());
    }

    @Test
    void searchBudgetSurplusBecomesUnsearched() {
        InvestigationBudget budget = InvestigationBudget.of(
                com.thingworx.things.agent.execution.BudgetVector.defaultsForTabular(),
                InvestigationSearchLimits.builder().candidateSignals(1).relationNodes(8).build());
        var events = new InvestigationEvidenceSource.EventBatch(
                List.of(), CompletenessStatus.COMPLETE, false, false);
        // Upstream relation candidate remains searchable under the signal cap.
        var observations = List.of(CandidateEvidenceObservation.support(
                "upstream:dryer.line1", "u5_outlier", "cache:out-1"));
        RcaInvestigationResult result = BoundedInvestigationEngine.investigate(
                request(budget),
                catalog(List.of(
                        new CatalogSignalEntry("role.temp", "Compressor", null),
                        new CatalogSignalEntry("role.pressure", "Compressor", null))),
                profile(),
                FixtureInvestigationEvidenceSource.of(events, observations));
        assertTrue(result.reasonCodes().contains(RcaOutcomeCodes.SEARCH_BOUNDARY_EXCEEDED));
        assertFalse(result.unsearchedScope().isEmpty());
        assertTrue(result.unsearchedScope().stream().anyMatch(s -> s.startsWith("signal:")));
        assertEquals(EvidenceStatus.SUCCESS, result.status());
    }

    @Test
    void cancellationFailsClosed() {
        RcaInvestigationResult result = BoundedInvestigationEngine.investigate(
                request(InvestigationBudget.defaults()),
                catalog(List.of()),
                profile(),
                FixtureInvestigationEvidenceSource.cancelledSource());
        assertEquals(EvidenceStatus.ERROR, result.status());
        assertTrue(result.reasonCodes().contains(RcaOutcomeCodes.CANCELLED));
        assertTrue(result.ledger().isEmpty());
    }

    @Test
    void blockingWeakenDeprioritizesButKeepsCandidate() {
        var events = new InvestigationEvidenceSource.EventBatch(
                List.of(), CompletenessStatus.COMPLETE, false, false);
        var observations = List.of(
                CandidateEvidenceObservation.support(
                        "signal:role.vibration.rms", "u5_outlier", "cache:o-1"),
                CandidateEvidenceObservation.weaken(
                        "signal:role.vibration.rms", "u5_change_point", "cache:cp-weak"));
        RcaInvestigationResult result = BoundedInvestigationEngine.investigate(
                request(InvestigationBudget.defaults()),
                catalog(List.of()),
                profile(),
                FixtureInvestigationEvidenceSource.of(events, observations));
        HypothesisLedgerEntry signal = result.ledger().stream()
                .filter(e -> e.candidateId().equals("signal:role.vibration.rms"))
                .findFirst()
                .orElseThrow();
        // Kept (not erased); demotion ranking is covered in HypothesisScorecardAssemblerTest.
        assertFalse(signal.supports().isEmpty());
        assertFalse(signal.weakens().isEmpty());
        assertTrue(signal.deprioritized());
    }
}
