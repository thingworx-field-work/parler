package com.thingworx.things.agent.investigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.analysis.config.U6DemoAppProfiles;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;
import com.thingworx.things.agent.taskstate.AgentTaskState;
import com.thingworx.things.agent.tools.AgentToolContext;

class U6Frc4RcaAppAdapterTest {

    private static final HalfOpenWindow WINDOW = HalfOpenWindow.of(
            Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-02T00:00:00Z"));

    @BeforeEach
    void setUp() {
        AgentToolContext.setConversationId("u6-frc4-rca");
        AgentToolContext.setAgentTaskState(new AgentTaskState("req", "u6-frc4-rca", "goal"));
    }

    @AfterEach
    void tearDown() {
        AgentToolContext.clear();
    }

    private static IncidentAnchor incident() {
        return IncidentAnchor.builder()
                .focusAssetId("compressor.line1")
                .eventId("inc-1")
                .evidenceWindow(WINDOW)
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

    private static CandidateCatalog catalog() {
        return CandidateCatalog.builder()
                .catalogId("demo-rca-v1")
                .maxRelationDepth(2)
                .maxRelationNodes(8)
                .relations(List.of(new CatalogRelationEntry(
                        "feeds", "compressor.line1", "dryer.line1", 1)))
                .signals(List.of(new CatalogSignalEntry("role.vibration.rms", "Compressor", "RUNNING")))
                .serviceBindings(List.of(
                        new CatalogServiceBinding(
                                CatalogServiceBinding.BindingKind.EVENT,
                                "SCPA_EventHelper",
                                "QueryAlarms",
                                3_600_000L)))
                .build();
    }

    @Test
    void demoAdapterProjectsBudgetAndDeprioritizedFlag() {
        var events = new InvestigationEvidenceSource.EventBatch(
                List.of(), CompletenessStatus.COMPLETE, false, false);
        var observations = List.of(
                CandidateEvidenceObservation.support(
                        "signal:role.vibration.rms", "u5_outlier", "cache:o-1"),
                CandidateEvidenceObservation.weaken(
                        "signal:role.vibration.rms", "u5_change_point", "cache:cp-weak"));
        DemoRcaEvidenceAdapter adapter = DemoRcaEvidenceAdapter.of(events, observations);

        RcaInvestigationRequest request = RcaInvestigationRequest.builder()
                .incident(incident())
                .investigationProfileId("rca-demo")
                .budget(InvestigationBudget.defaults())
                .build();
        U6RcaInvestigationAppRunner.Outcome outcome = U6RcaInvestigationAppRunner.run(
                request,
                catalog(),
                profile(),
                adapter,
                U6DemoAppProfiles.profileDigest(U6DemoAppProfiles.Surface.RCA_INVESTIGATION));

        assertEquals(1, adapter.eventFetchCount());
        assertEquals(1, adapter.analysisFetchCount());
        HypothesisLedgerEntry signal = outcome.result().ledger().stream()
                .filter(e -> e.candidateId().equals("signal:role.vibration.rms"))
                .findFirst()
                .orElseThrow();
        assertTrue(signal.deprioritized());
        assertNotNullBudget(outcome);
        assertEquals("1", outcome.envelope().metrics().get("deprioritizedN"));
        assertTrue(outcome.envelope().metrics().containsKey("search.events"));
        assertTrue(outcome.envelope().assessment().hasApplicability("associational"));
        assertTrue(outcome.envelope().assessment().hasApplicability("not_tested_causal"));
    }

    private static void assertNotNullBudget(U6RcaInvestigationAppRunner.Outcome outcome) {
        assertTrue(outcome.envelope().budget() != null);
        assertTrue(outcome.envelope().budget().consumedWallTimeMillis() >= 0L);
    }

    @Test
    void claimTracesToEvidenceRefs() {
        var observations = List.of(CandidateEvidenceObservation.support(
                "signal:role.vibration.rms", "u5_outlier", "cache:out-1"));
        U6RcaInvestigationAppRunner.Outcome outcome = U6RcaInvestigationAppRunner.run(
                RcaInvestigationRequest.builder()
                        .incident(incident())
                        .investigationProfileId("rca-demo")
                        .build(),
                catalog(),
                profile(),
                DemoRcaEvidenceAdapter.of(
                        new InvestigationEvidenceSource.EventBatch(
                                List.of(), CompletenessStatus.COMPLETE, false, false),
                        observations),
                "digest");
        HypothesisLedgerEntry entry = outcome.result().ledger().stream()
                .filter(e -> !e.supports().isEmpty())
                .findFirst()
                .orElseThrow();
        assertFalse(entry.supports().isEmpty());
        assertTrue(entry.supports().stream().allMatch(r -> r.evidenceRef() != null && !r.evidenceRef().isBlank()));
        assertFalse(entry.deprioritized());
    }
}
