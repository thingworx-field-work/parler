package com.thingworx.things.agent.investigation;

import java.util.List;

import com.thingworx.things.agent.cache.ArtifactAccessContext;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

/**
 * Bounded batch evidence surface for FRC-3. App adapters / Playbook derive ops implement this in
 * FRC-4; unit tests use fixtures.
 */
public interface InvestigationEvidenceSource {

    EventBatch fetchEvents(
            IncidentAnchor incident, ArtifactAccessContext access, InvestigationSearchLimits limits);

    /**
     * Pre-resolved U5/analysis observations for candidates (association only). Each call counts
     * against {@code analysisCalls}.
     */
    List<CandidateEvidenceObservation> fetchAnalysisObservations(
            IncidentAnchor incident,
            List<ResolvedCandidate> candidates,
            ArtifactAccessContext access,
            InvestigationSearchLimits limits);

    boolean cancelled();

    final class EventBatch {
        private final List<InvestigationEvent> events;
        private final CompletenessStatus completeness;
        private final boolean permissionLimited;
        private final boolean sourceUnavailable;

        public EventBatch(
                List<InvestigationEvent> events,
                CompletenessStatus completeness,
                boolean permissionLimited,
                boolean sourceUnavailable) {
            this.events = events == null ? List.of() : List.copyOf(events);
            this.completeness = completeness == null ? CompletenessStatus.UNKNOWN : completeness;
            this.permissionLimited = permissionLimited;
            this.sourceUnavailable = sourceUnavailable;
        }

        public List<InvestigationEvent> events() {
            return events;
        }

        public CompletenessStatus completeness() {
            return completeness;
        }

        public boolean permissionLimited() {
            return permissionLimited;
        }

        public boolean sourceUnavailable() {
            return sourceUnavailable;
        }
    }
}
