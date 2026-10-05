package com.thingworx.things.agent.investigation;

import java.util.ArrayList;
import java.util.List;

import com.thingworx.things.agent.cache.ArtifactAccessContext;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

/** In-memory evidence source for FRC-3 engine tests. */
final class FixtureInvestigationEvidenceSource implements InvestigationEvidenceSource {

    private final EventBatch eventBatch;
    private final List<CandidateEvidenceObservation> observations;
    private final boolean cancelled;

    FixtureInvestigationEvidenceSource(
            EventBatch eventBatch, List<CandidateEvidenceObservation> observations, boolean cancelled) {
        this.eventBatch = eventBatch;
        this.observations = observations == null ? List.of() : List.copyOf(observations);
        this.cancelled = cancelled;
    }

    static FixtureInvestigationEvidenceSource of(
            EventBatch events, List<CandidateEvidenceObservation> observations) {
        return new FixtureInvestigationEvidenceSource(events, observations, false);
    }

    static FixtureInvestigationEvidenceSource cancelledSource() {
        return new FixtureInvestigationEvidenceSource(
                new EventBatch(List.of(), CompletenessStatus.COMPLETE, false, false), List.of(), true);
    }

    @Override
    public EventBatch fetchEvents(
            IncidentAnchor incident, ArtifactAccessContext access, InvestigationSearchLimits limits) {
        return eventBatch;
    }

    @Override
    public List<CandidateEvidenceObservation> fetchAnalysisObservations(
            IncidentAnchor incident,
            List<ResolvedCandidate> candidates,
            ArtifactAccessContext access,
            InvestigationSearchLimits limits) {
        List<CandidateEvidenceObservation> out = new ArrayList<>();
        for (CandidateEvidenceObservation obs : observations) {
            for (ResolvedCandidate c : candidates) {
                if (c.candidateId().equals(obs.candidateId())) {
                    out.add(obs);
                    break;
                }
            }
        }
        return List.copyOf(out);
    }

    @Override
    public boolean cancelled() {
        return cancelled;
    }
}
