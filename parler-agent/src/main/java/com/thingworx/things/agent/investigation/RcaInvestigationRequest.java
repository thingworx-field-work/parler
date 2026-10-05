package com.thingworx.things.agent.investigation;

import java.util.Objects;

import com.thingworx.things.agent.cache.ArtifactAccessContext;

/**
 * Internal G7 request shape (FRC-0). Playbook execution remains FRC-3.
 */
public final class RcaInvestigationRequest {

    private final IncidentAnchor incident;
    private final String investigationProfileId;
    private final ArtifactAccessContext access;
    private final InvestigationBudget budget;

    private RcaInvestigationRequest(Builder b) {
        this.incident = Objects.requireNonNull(b.incident, "incident");
        this.investigationProfileId = requireNonBlank(b.investigationProfileId, "investigationProfileId");
        // access may be null only for offline/fixture investigation runs; production Playbook
        // adapters (FRC-4) must supply a Core-minted ArtifactAccessContext.
        this.access = b.access;
        this.budget = Objects.requireNonNull(b.budget, "budget");
    }

    public static Builder builder() {
        return new Builder();
    }

    public IncidentAnchor incident() {
        return incident;
    }

    public String investigationProfileId() {
        return investigationProfileId;
    }

    public ArtifactAccessContext access() {
        return access;
    }

    public InvestigationBudget budget() {
        return budget;
    }

    private static String requireNonBlank(String s, String name) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return s.trim();
    }

    public static final class Builder {
        private IncidentAnchor incident;
        private String investigationProfileId;
        private ArtifactAccessContext access;
        private InvestigationBudget budget = InvestigationBudget.defaults();

        public Builder incident(IncidentAnchor v) {
            this.incident = v;
            return this;
        }

        public Builder investigationProfileId(String v) {
            this.investigationProfileId = v;
            return this;
        }

        public Builder access(ArtifactAccessContext v) {
            this.access = v;
            return this;
        }

        public Builder budget(InvestigationBudget v) {
            this.budget = v;
            return this;
        }

        public RcaInvestigationRequest build() {
            return new RcaInvestigationRequest(this);
        }
    }
}
