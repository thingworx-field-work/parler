package com.thingworx.things.agent.playbook;

import java.util.Objects;

import com.thingworx.things.agent.analysis.config.U6DemoAppProfiles;
import com.thingworx.things.agent.cache.ArtifactAccessContext;
import com.thingworx.things.agent.execution.BudgetVector;
import com.thingworx.things.agent.fleet.CohortBatchSource;
import com.thingworx.things.agent.fleet.FrozenCohortMembership;
import com.thingworx.things.agent.fleet.MetricComparabilitySpec;
import com.thingworx.things.agent.fleet.RankingDirection;
import com.thingworx.things.agent.fleet.U6FleetBenchmarkAppRunner;
import com.thingworx.things.agent.investigation.CandidateCatalog;
import com.thingworx.things.agent.investigation.IncidentAnchor;
import com.thingworx.things.agent.investigation.InvestigationEvidenceSource;
import com.thingworx.things.agent.investigation.InvestigationProfile;
import com.thingworx.things.agent.investigation.RcaInvestigationRequest;
import com.thingworx.things.agent.investigation.U6RcaInvestigationAppRunner;

/**
 * Reference Playbook driver for U6 §7.3 (FRC-4). Exercises App adapters + both engines in a fixed
 * node sequence without a pack registry or new resident tools. Apps / operators invoke this path;
 * the model does not call per-member tools.
 *
 * <pre>
 * resolve incident / freeze cohort
 *   -> G5 batch collect + distribute
 *   -> G7 resolve candidates + evidence + scorecard
 *   -> compact envelopes (fleet AnalysisEnvelope + RCA EvidenceAssessment)
 * </pre>
 */
public final class U6ReferencePlaybookDriver {

    public static final String PLAYBOOK_ID = "u6_fleet_rca_reference";

    private U6ReferencePlaybookDriver() {}

    public static final class RunResult {
        private final U6FleetBenchmarkAppRunner.Outcome fleet;
        private final U6RcaInvestigationAppRunner.Outcome rca;

        RunResult(U6FleetBenchmarkAppRunner.Outcome fleet, U6RcaInvestigationAppRunner.Outcome rca) {
            this.fleet = fleet;
            this.rca = rca;
        }

        public U6FleetBenchmarkAppRunner.Outcome fleet() {
            return fleet;
        }

        public U6RcaInvestigationAppRunner.Outcome rca() {
            return rca;
        }

        public String playbookId() {
            return PLAYBOOK_ID;
        }
    }

    public static RunResult run(
            FrozenCohortMembership membership,
            MetricComparabilitySpec metricSpec,
            CohortBatchSource peerSource,
            RankingDirection direction,
            int topN,
            String focusAssetId,
            BudgetVector fleetBudget,
            ArtifactAccessContext access,
            IncidentAnchor incident,
            String investigationProfileId,
            CandidateCatalog catalog,
            InvestigationProfile profile,
            InvestigationEvidenceSource evidenceSource) {
        Objects.requireNonNull(membership, "membership");
        Objects.requireNonNull(metricSpec, "metricSpec");
        Objects.requireNonNull(peerSource, "peerSource");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(fleetBudget, "fleetBudget");
        Objects.requireNonNull(incident, "incident");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(evidenceSource, "evidenceSource");

        U6FleetBenchmarkAppRunner.Outcome fleet = U6FleetBenchmarkAppRunner.run(
                membership,
                metricSpec,
                peerSource,
                access,
                fleetBudget,
                direction,
                topN,
                focusAssetId,
                U6DemoAppProfiles.profileDigest(U6DemoAppProfiles.Surface.FLEET_BENCHMARK));

        RcaInvestigationRequest rcaRequest = RcaInvestigationRequest.builder()
                .incident(incident)
                .investigationProfileId(investigationProfileId)
                .access(access)
                .build();
        U6RcaInvestigationAppRunner.Outcome rca = U6RcaInvestigationAppRunner.run(
                rcaRequest,
                catalog,
                profile,
                evidenceSource,
                U6DemoAppProfiles.profileDigest(U6DemoAppProfiles.Surface.RCA_INVESTIGATION));

        return new RunResult(fleet, rca);
    }
}
