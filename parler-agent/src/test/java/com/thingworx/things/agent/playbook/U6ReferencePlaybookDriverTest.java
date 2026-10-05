package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.analysis.AnalysisEnvelopeValidator;
import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.execution.BudgetVector;
import com.thingworx.things.agent.fleet.CohortBatchMemberRow;
import com.thingworx.things.agent.fleet.CohortMemberStatus;
import com.thingworx.things.agent.fleet.DemoPeerCohortBatchAdapter;
import com.thingworx.things.agent.fleet.FrozenCohortMembership;
import com.thingworx.things.agent.fleet.MetricComparabilitySpec;
import com.thingworx.things.agent.fleet.RankingDirection;
import com.thingworx.things.agent.investigation.CandidateCatalog;
import com.thingworx.things.agent.investigation.CandidateEvidenceObservation;
import com.thingworx.things.agent.investigation.CatalogRelationEntry;
import com.thingworx.things.agent.investigation.CatalogServiceBinding;
import com.thingworx.things.agent.investigation.CatalogSignalEntry;
import com.thingworx.things.agent.investigation.DemoRcaEvidenceAdapter;
import com.thingworx.things.agent.investigation.EvidenceTestSpec;
import com.thingworx.things.agent.investigation.IncidentAnchor;
import com.thingworx.things.agent.investigation.InvestigationEvidenceSource;
import com.thingworx.things.agent.investigation.InvestigationProfile;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

class U6ReferencePlaybookDriverTest {

    private static final HalfOpenWindow WINDOW = HalfOpenWindow.of(
            Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-02T00:00:00Z"));

    @Test
    void referencePlaybookExercisesBothEnginesWithoutPackRegistry() {
        List<CohortBatchMemberRow> rows = List.of(
                valued("compressor.line1", 12),
                valued("compressor.line2", 20),
                valued("compressor.line3", 8));
        DemoPeerCohortBatchAdapter peer = DemoPeerCohortBatchAdapter.of(rows);
        FrozenCohortMembership mem = FrozenCohortMembership.freeze(
                "peer-v1", "metric-v1", WINDOW, List.of("compressor.line1", "compressor.line2", "compressor.line3"));

        DemoRcaEvidenceAdapter evidence = DemoRcaEvidenceAdapter.of(
                new InvestigationEvidenceSource.EventBatch(
                        List.of(), CompletenessStatus.COMPLETE, false, false),
                List.of(CandidateEvidenceObservation.support(
                        "signal:role.vibration.rms", "u5_change_point", "cache:cp-1")));

        U6ReferencePlaybookDriver.RunResult run = U6ReferencePlaybookDriver.run(
                mem,
                new MetricComparabilitySpec("C", "1m", "kpi_v1"),
                peer,
                RankingDirection.HIGHER_IS_BETTER,
                2,
                "compressor.line1",
                BudgetVector.defaultsForTabular(),
                null,
                IncidentAnchor.builder()
                        .focusAssetId("compressor.line1")
                        .eventId("inc-1")
                        .evidenceWindow(WINDOW)
                        .build(),
                "rca-demo",
                catalog(),
                profile(),
                evidence);

        assertEquals(U6ReferencePlaybookDriver.PLAYBOOK_ID, run.playbookId());
        assertEquals(1, peer.fetchCount());
        assertEquals(1, evidence.eventFetchCount());
        assertEquals(EvidenceStatus.SUCCESS, run.fleet().result().status());
        AnalysisEnvelopeValidator.validateOrThrow(run.fleet().envelope());
        assertEquals(EvidenceStatus.SUCCESS, run.rca().result().status());
        assertFalse(run.rca().result().ledger().isEmpty());
        assertTrue(run.rca().envelope().budget() != null);
        assertTrue(run.rca().envelope().assessment().hasApplicability("not_tested_causal"));
    }

    private static CohortBatchMemberRow valued(String id, double v) {
        return CohortBatchMemberRow.builder()
                .semanticAssetId(id)
                .status(CohortMemberStatus.ELIGIBLE_VALUE)
                .metricValue(v)
                .unit("C")
                .grain("1m")
                .methodId("kpi_v1")
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
}
