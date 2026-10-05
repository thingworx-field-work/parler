package com.thingworx.things.agent.investigation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

/**
 * FRC-3 G7 bounded investigation workflow (fleet-rca §7.3). Pure orchestration over catalog +
 * evidence source — Playbook/App adapters wire this in FRC-4; no resident RCA tool.
 */
public final class BoundedInvestigationEngine {

    private BoundedInvestigationEngine() {}

    public static RcaInvestigationResult investigate(
            RcaInvestigationRequest request,
            CandidateCatalog catalog,
            InvestigationProfile profile,
            InvestigationEvidenceSource evidenceSource) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(evidenceSource, "evidenceSource");

        if (!profile.profileId().equals(request.investigationProfileId())) {
            throw new IllegalArgumentException(
                    "profile.profileId must match request.investigationProfileId");
        }

        List<String> reasons = new ArrayList<>();
        Map<String, String> metrics = new LinkedHashMap<>(request.budget().searchLimitMetrics());

        if (evidenceSource.cancelled()) {
            reasons.add(RcaOutcomeCodes.CANCELLED);
            return terminal(request, List.of(), List.of(), List.of(), reasons, EvidenceStatus.ERROR, metrics);
        }

        CandidateResolver.Resolution resolution =
                CandidateResolver.resolve(catalog, request.incident(), request.budget().searchLimits());
        List<String> unsearched = new ArrayList<>(resolution.unsearchedScope());
        if (resolution.searchBoundaryExceeded()) {
            reasons.add(RcaOutcomeCodes.SEARCH_BOUNDARY_EXCEEDED);
        }

        InvestigationEvidenceSource.EventBatch eventBatch = evidenceSource.fetchEvents(
                request.incident(), request.access(), request.budget().searchLimits());
        if (eventBatch.sourceUnavailable()) {
            reasons.add(RcaOutcomeCodes.CANDIDATE_SOURCE_UNAVAILABLE);
            unsearched.add("events:source_unavailable");
        }
        if (eventBatch.permissionLimited()) {
            reasons.add(RcaOutcomeCodes.PERMISSION_LIMITED);
        }
        if (eventBatch.completeness() != CompletenessStatus.COMPLETE) {
            reasons.add(RcaOutcomeCodes.PARTIAL_EVENT_HISTORY);
        }

        List<InvestigationEvent> events = eventBatch.events();
        int eventCap = request.budget().searchLimits().events();
        if (events.size() > eventCap) {
            unsearched.add("events:truncated_to_" + eventCap);
            reasons.add(RcaOutcomeCodes.SEARCH_BOUNDARY_EXCEEDED);
            events = List.copyOf(events.subList(0, eventCap));
        }

        Map<String, EventCoOccurrence.Result> coByCandidate = new LinkedHashMap<>();
        for (ResolvedCandidate c : resolution.candidates()) {
            coByCandidate.put(
                    c.candidateId(),
                    EventCoOccurrence.forCandidate(c, request.incident().evidenceWindow(), events));
        }

        List<CandidateEvidenceObservation> observations = evidenceSource.fetchAnalysisObservations(
                request.incident(),
                resolution.candidates(),
                request.access(),
                request.budget().searchLimits());
        int analysisCap = request.budget().searchLimits().analysisCalls();
        if (observations.size() > analysisCap) {
            unsearched.add("analysis:truncated_to_" + analysisCap);
            reasons.add(RcaOutcomeCodes.SEARCH_BOUNDARY_EXCEEDED);
            observations = List.copyOf(observations.subList(0, analysisCap));
        }

        List<HypothesisLedgerEntry> ledger = HypothesisScorecardAssembler.assemble(
                profile, resolution.candidates(), observations, coByCandidate, unsearched);

        List<String> searched = new ArrayList<>();
        for (ResolvedCandidate c : resolution.candidates()) {
            searched.add(c.candidateId());
        }
        searched.add("events:" + events.size());

        boolean anySupported = ledger.stream().anyMatch(e -> !e.supports().isEmpty());
        EvidenceStatus status;
        if (eventBatch.sourceUnavailable() && resolution.candidates().isEmpty()) {
            status = EvidenceStatus.ERROR;
        } else if (!anySupported) {
            reasons.add(RcaOutcomeCodes.NO_SUPPORTED_CANDIDATE);
            status = EvidenceStatus.NO_FINDING;
        } else {
            status = EvidenceStatus.SUCCESS;
        }

        metrics.put("outcomeCode", primaryOutcome(reasons, status));
        metrics.put("candidateN", Integer.toString(ledger.size()));
        metrics.put("supportedCandidateN", Long.toString(ledger.stream()
                .filter(e -> !e.supports().isEmpty())
                .count()));
        metrics.put("eventN", Integer.toString(events.size()));
        metrics.put("unsearchedN", Integer.toString(unsearched.size()));
        if (!reasons.isEmpty()) {
            metrics.put("reasonCodes", String.join(",", reasons));
        }

        return RcaInvestigationResult.builder()
                .request(request)
                .ledger(ledger)
                .searchedScope(searched)
                .unsearchedScope(unsearched)
                .reasonCodes(reasons)
                .status(status)
                .metrics(metrics)
                .build();
    }

    private static RcaInvestigationResult terminal(
            RcaInvestigationRequest request,
            List<HypothesisLedgerEntry> ledger,
            List<String> searched,
            List<String> unsearched,
            List<String> reasons,
            EvidenceStatus status,
            Map<String, String> metrics) {
        Map<String, String> m = new LinkedHashMap<>(metrics);
        m.put("outcomeCode", primaryOutcome(reasons, status));
        if (!reasons.isEmpty()) {
            m.put("reasonCodes", String.join(",", reasons));
        }
        return RcaInvestigationResult.builder()
                .request(request)
                .ledger(ledger)
                .searchedScope(searched)
                .unsearchedScope(unsearched)
                .reasonCodes(reasons)
                .status(status)
                .metrics(m)
                .build();
    }

    private static String primaryOutcome(List<String> reasons, EvidenceStatus status) {
        if (reasons.contains(RcaOutcomeCodes.CANCELLED)) {
            return RcaOutcomeCodes.CANCELLED;
        }
        if (reasons.contains(RcaOutcomeCodes.CANDIDATE_SOURCE_UNAVAILABLE)) {
            return RcaOutcomeCodes.CANDIDATE_SOURCE_UNAVAILABLE;
        }
        if (reasons.contains(RcaOutcomeCodes.NO_SUPPORTED_CANDIDATE)) {
            return RcaOutcomeCodes.NO_SUPPORTED_CANDIDATE;
        }
        if (reasons.contains(RcaOutcomeCodes.SEARCH_BOUNDARY_EXCEEDED)) {
            return RcaOutcomeCodes.SEARCH_BOUNDARY_EXCEEDED;
        }
        if (reasons.contains(RcaOutcomeCodes.PARTIAL_EVENT_HISTORY)) {
            return RcaOutcomeCodes.PARTIAL_EVENT_HISTORY;
        }
        if (reasons.contains(RcaOutcomeCodes.PERMISSION_LIMITED)) {
            return RcaOutcomeCodes.PERMISSION_LIMITED;
        }
        return status.name();
    }
}
