package com.thingworx.things.agent.investigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.thingworx.things.agent.evidence.EvidenceStatus;

/**
 * FRC-3 G7 investigation result: ranked hypothesis ledger, searched/unsearched scope, and topic
 * reasons. Playbook-facing compact summary — no resident RCA tool advertisement.
 */
public final class RcaInvestigationResult {

    private final RcaInvestigationRequest request;
    private final List<HypothesisLedgerEntry> ledger;
    private final List<String> searchedScope;
    private final List<String> unsearchedScope;
    private final List<String> reasonCodes;
    private final EvidenceStatus status;
    private final Map<String, String> metrics;

    private RcaInvestigationResult(Builder b) {
        this.request = Objects.requireNonNull(b.request, "request");
        this.ledger = Collections.unmodifiableList(new ArrayList<>(b.ledger));
        this.searchedScope = Collections.unmodifiableList(new ArrayList<>(b.searchedScope));
        this.unsearchedScope = Collections.unmodifiableList(new ArrayList<>(b.unsearchedScope));
        this.reasonCodes = Collections.unmodifiableList(new ArrayList<>(b.reasonCodes));
        this.status = Objects.requireNonNull(b.status, "status");
        this.metrics = Collections.unmodifiableMap(new LinkedHashMap<>(b.metrics));
    }

    public static Builder builder() {
        return new Builder();
    }

    public RcaInvestigationRequest request() {
        return request;
    }

    public List<HypothesisLedgerEntry> ledger() {
        return ledger;
    }

    public List<String> searchedScope() {
        return searchedScope;
    }

    public List<String> unsearchedScope() {
        return unsearchedScope;
    }

    public List<String> reasonCodes() {
        return reasonCodes;
    }

    public EvidenceStatus status() {
        return status;
    }

    public Map<String, String> metrics() {
        return metrics;
    }

    public List<String> summaryFacts() {
        List<String> facts = new ArrayList<>();
        facts.add("candidates=" + ledger.size());
        facts.add("outcome=" + metrics.getOrDefault("outcomeCode", status.name()));
        if (!unsearchedScope.isEmpty()) {
            facts.add("unsearchedN=" + unsearchedScope.size());
        }
        if (reasonCodes.contains(RcaOutcomeCodes.NO_SUPPORTED_CANDIDATE)) {
            facts.add("no_supported_candidate_in_searched_scope");
        }
        return List.copyOf(facts);
    }

    public static final class Builder {
        private RcaInvestigationRequest request;
        private List<HypothesisLedgerEntry> ledger = List.of();
        private List<String> searchedScope = List.of();
        private List<String> unsearchedScope = List.of();
        private List<String> reasonCodes = List.of();
        private EvidenceStatus status = EvidenceStatus.SUCCESS;
        private Map<String, String> metrics = Map.of();

        public Builder request(RcaInvestigationRequest v) {
            this.request = v;
            return this;
        }

        public Builder ledger(List<HypothesisLedgerEntry> v) {
            this.ledger = v == null ? List.of() : v;
            return this;
        }

        public Builder searchedScope(List<String> v) {
            this.searchedScope = v == null ? List.of() : v;
            return this;
        }

        public Builder unsearchedScope(List<String> v) {
            this.unsearchedScope = v == null ? List.of() : v;
            return this;
        }

        public Builder reasonCodes(List<String> v) {
            this.reasonCodes = v == null ? List.of() : v;
            return this;
        }

        public Builder status(EvidenceStatus v) {
            this.status = v;
            return this;
        }

        public Builder metrics(Map<String, String> v) {
            this.metrics = v == null ? Map.of() : v;
            return this;
        }

        public RcaInvestigationResult build() {
            return new RcaInvestigationResult(this);
        }
    }
}
