package com.thingworx.things.agent.investigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.cache.ArtifactAccessContext;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

/**
 * Demo App relation/event evidence adapter for FRC-4. Supplies bounded event batches and
 * pre-resolved U5/analysis observations. Real site Apps replace this with governed Services;
 * they MUST NOT invent causal claims or unbounded scans.
 */
public final class DemoRcaEvidenceAdapter implements InvestigationEvidenceSource {

    public static final String PROFILE_DIGEST = "u6-demo-rca-evidence-v1";

    private final EventBatch eventBatch;
    private final List<CandidateEvidenceObservation> observations;
    private final boolean cancelled;
    private int eventFetchCount;
    private int analysisFetchCount;

    public DemoRcaEvidenceAdapter(
            EventBatch eventBatch,
            List<CandidateEvidenceObservation> observations,
            boolean cancelled) {
        this.eventBatch = Objects.requireNonNull(eventBatch, "eventBatch");
        this.observations = observations == null ? List.of() : List.copyOf(observations);
        this.cancelled = cancelled;
    }

    public static DemoRcaEvidenceAdapter of(
            EventBatch events, List<CandidateEvidenceObservation> observations) {
        return new DemoRcaEvidenceAdapter(events, observations, false);
    }

    public String profileDigest() {
        return PROFILE_DIGEST;
    }

    public int eventFetchCount() {
        return eventFetchCount;
    }

    public int analysisFetchCount() {
        return analysisFetchCount;
    }

    @Override
    public EventBatch fetchEvents(
            IncidentAnchor incident, ArtifactAccessContext access, InvestigationSearchLimits limits) {
        eventFetchCount++;
        return eventBatch;
    }

    @Override
    public List<CandidateEvidenceObservation> fetchAnalysisObservations(
            IncidentAnchor incident,
            List<ResolvedCandidate> candidates,
            ArtifactAccessContext access,
            InvestigationSearchLimits limits) {
        analysisFetchCount++;
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
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
