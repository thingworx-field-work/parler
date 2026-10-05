package com.thingworx.things.agent.investigation;

/**
 * U6 search caps composed beside the shared {@link com.thingworx.things.agent.execution.BudgetVector}
 * (fleet-rca D10). Not a private counter ledger.
 */
public final class InvestigationSearchLimits {

    private final int relationDepth;
    private final int relationNodes;
    private final int candidateSignals;
    private final int events;
    private final int analysisCalls;

    private InvestigationSearchLimits(Builder b) {
        this.relationDepth = requirePositive(b.relationDepth, "relationDepth");
        this.relationNodes = requirePositive(b.relationNodes, "relationNodes");
        this.candidateSignals = requirePositive(b.candidateSignals, "candidateSignals");
        this.events = requirePositive(b.events, "events");
        this.analysisCalls = requirePositive(b.analysisCalls, "analysisCalls");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static InvestigationSearchLimits defaults() {
        return builder().build();
    }

    public int relationDepth() {
        return relationDepth;
    }

    public int relationNodes() {
        return relationNodes;
    }

    public int candidateSignals() {
        return candidateSignals;
    }

    public int events() {
        return events;
    }

    public int analysisCalls() {
        return analysisCalls;
    }

    private static int requirePositive(int v, String name) {
        if (v <= 0) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
        return v;
    }

    public static final class Builder {
        private int relationDepth = 2;
        private int relationNodes = 32;
        private int candidateSignals = 24;
        private int events = 100;
        private int analysisCalls = 16;

        public Builder relationDepth(int v) {
            this.relationDepth = v;
            return this;
        }

        public Builder relationNodes(int v) {
            this.relationNodes = v;
            return this;
        }

        public Builder candidateSignals(int v) {
            this.candidateSignals = v;
            return this;
        }

        public Builder events(int v) {
            this.events = v;
            return this;
        }

        public Builder analysisCalls(int v) {
            this.analysisCalls = v;
            return this;
        }

        public InvestigationSearchLimits build() {
            return new InvestigationSearchLimits(this);
        }
    }
}
