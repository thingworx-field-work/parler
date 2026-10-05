package com.thingworx.things.agent.investigation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves bounded relation/signal candidates from a validated {@link CandidateCatalog} under
 * {@link InvestigationSearchLimits}. Excess catalog entries become unsearched-scope items rather
 * than silent drops without disclosure.
 */
public final class CandidateResolver {

    private CandidateResolver() {}

    public static Resolution resolve(
            CandidateCatalog catalog, IncidentAnchor incident, InvestigationSearchLimits limits) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(incident, "incident");
        Objects.requireNonNull(limits, "limits");

        String focus = incident.focusAssetId();
        int depthCap = Math.min(limits.relationDepth(), catalog.maxRelationDepth());
        int nodeCap = Math.min(limits.relationNodes(), catalog.maxRelationNodes());
        int signalCap = limits.candidateSignals();

        List<ResolvedCandidate> admitted = new ArrayList<>();
        List<String> unsearched = new ArrayList<>();
        boolean boundary = false;

        Set<String> relatedAssets = new LinkedHashSet<>();
        relatedAssets.add(focus);
        for (CatalogRelationEntry rel : catalog.relations()) {
            if (!focus.equals(rel.fromAssetSemanticId()) && !focus.equals(rel.toAssetSemanticId())) {
                continue;
            }
            if (rel.declaredDistance() > depthCap) {
                unsearched.add("relation_depth:" + rel.relationTypeId() + ":" + rel.toAssetSemanticId());
                boundary = true;
                continue;
            }
            String other = focus.equals(rel.fromAssetSemanticId())
                    ? rel.toAssetSemanticId()
                    : rel.fromAssetSemanticId();
            if (relatedAssets.size() >= nodeCap && !relatedAssets.contains(other)) {
                unsearched.add("relation_nodes:" + other);
                boundary = true;
                continue;
            }
            relatedAssets.add(other);
            admitted.add(new ResolvedCandidate(
                    "upstream:" + other,
                    CandidateKind.UPSTREAM_ASSET,
                    "Related asset " + other + " via " + rel.relationTypeId(),
                    rel.declaredDistance(),
                    other));
        }

        int signalsAdmitted = 0;
        for (CatalogSignalEntry signal : catalog.signals()) {
            if (signalsAdmitted >= signalCap) {
                unsearched.add("signal:" + signal.signalSemanticId());
                boundary = true;
                continue;
            }
            admitted.add(new ResolvedCandidate(
                    "signal:" + signal.signalSemanticId(),
                    CandidateKind.SIGNAL,
                    "Candidate signal " + signal.signalSemanticId(),
                    0,
                    focus));
            signalsAdmitted++;
        }

        // Event/maintenance/batch bindings become event-kind candidates when the binding exists.
        for (CatalogServiceBinding binding : catalog.serviceBindings()) {
            CandidateKind kind;
            switch (binding.kind()) {
                case EVENT:
                    kind = CandidateKind.EVENT;
                    break;
                case MAINTENANCE:
                    kind = CandidateKind.MAINTENANCE;
                    break;
                case BATCH:
                    kind = CandidateKind.BATCH;
                    break;
                default:
                    throw new IllegalStateException("unexpected binding kind " + binding.kind());
            }
            admitted.add(new ResolvedCandidate(
                    kind.wireName() + ":" + binding.thingName() + "." + binding.serviceName(),
                    kind,
                    "Governed " + kind.wireName() + " source " + binding.thingName(),
                    0,
                    focus));
        }

        return new Resolution(List.copyOf(admitted), List.copyOf(unsearched), boundary);
    }

    public static final class Resolution {
        private final List<ResolvedCandidate> candidates;
        private final List<String> unsearchedScope;
        private final boolean searchBoundaryExceeded;

        Resolution(List<ResolvedCandidate> candidates, List<String> unsearchedScope, boolean searchBoundaryExceeded) {
            this.candidates = candidates;
            this.unsearchedScope = unsearchedScope;
            this.searchBoundaryExceeded = searchBoundaryExceeded;
        }

        public List<ResolvedCandidate> candidates() {
            return candidates;
        }

        public List<String> unsearchedScope() {
            return unsearchedScope;
        }

        public boolean searchBoundaryExceeded() {
            return searchBoundaryExceeded;
        }
    }
}
