package com.thingworx.things.agent.investigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * U6-output-only hypothesis scorecard row (fleet-rca D9 / §6.4). No U8 persistence fields.
 * {@link #PRIORITY_MEANING} forbids calibrated-probability wording.
 */
public final class HypothesisLedgerEntry {

    public static final String PRIORITY_MEANING = "ORDER_FOR_NEXT_CHECKS_NOT_CAUSAL_PROBABILITY";

    private static final String[] FORBIDDEN_WORDING = {
            "confidence",
            "likelihood",
            "probability",
            "root-cause score",
            "root cause score",
            "proves cause",
            "causal probability"
    };

    private final String candidateId;
    private final CandidateKind candidateKind;
    private final String statement;
    private final int investigationPriority;
    private final List<HypothesisEvidenceRef> supports;
    private final List<HypothesisEvidenceRef> weakens;
    private final List<HypothesisEvidenceRef> unknown;
    private final List<String> nextChecks;
    private final List<String> unsearchedScope;
    /** True when a blocking weaken demoted this candidate (§7.4 DEPRIORITIZED); not erased. */
    private final boolean deprioritized;

    private HypothesisLedgerEntry(Builder b) {
        this.candidateId = requireNonBlank(b.candidateId, "candidateId");
        this.candidateKind = Objects.requireNonNull(b.candidateKind, "candidateKind");
        this.statement = requireNonBlank(b.statement, "statement");
        rejectForbiddenWording(statement, "statement");
        this.investigationPriority = b.investigationPriority;
        this.supports = copy(b.supports);
        this.weakens = copy(b.weakens);
        this.unknown = copy(b.unknown);
        this.nextChecks = copyStrings(b.nextChecks, "nextChecks");
        this.unsearchedScope = copyStrings(b.unsearchedScope, "unsearchedScope");
        this.deprioritized = b.deprioritized;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String candidateId() {
        return candidateId;
    }

    public CandidateKind candidateKind() {
        return candidateKind;
    }

    public String statement() {
        return statement;
    }

    public int investigationPriority() {
        return investigationPriority;
    }

    public String priorityMeaning() {
        return PRIORITY_MEANING;
    }

    public List<HypothesisEvidenceRef> supports() {
        return supports;
    }

    public List<HypothesisEvidenceRef> weakens() {
        return weakens;
    }

    public List<HypothesisEvidenceRef> unknown() {
        return unknown;
    }

    public List<String> nextChecks() {
        return nextChecks;
    }

    public List<String> unsearchedScope() {
        return unsearchedScope;
    }

    /**
     * Observability for Playbook/envelope rendering: blocking contradiction demoted this row
     * below ordinary work-queue entries. Ordering alone is insufficient for a standalone row.
     */
    public boolean deprioritized() {
        return deprioritized;
    }

    static void rejectForbiddenWording(String text, String field) {
        if (text == null) {
            return;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String banned : FORBIDDEN_WORDING) {
            if (lower.contains(banned)) {
                throw new IllegalArgumentException(field + " must not contain causal/probability wording: "
                        + banned);
            }
        }
    }

    private static String requireNonBlank(String s, String name) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return s.trim();
    }

    private static List<HypothesisEvidenceRef> copy(List<HypothesisEvidenceRef> in) {
        if (in == null || in.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(in));
    }

    private static List<String> copyStrings(List<String> in, String field) {
        if (in == null || in.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) {
            if (s != null && !s.isBlank()) {
                String trimmed = s.trim();
                rejectForbiddenWording(trimmed, field);
                out.add(trimmed);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public static final class Builder {
        private String candidateId;
        private CandidateKind candidateKind;
        private String statement;
        private int investigationPriority;
        private List<HypothesisEvidenceRef> supports = List.of();
        private List<HypothesisEvidenceRef> weakens = List.of();
        private List<HypothesisEvidenceRef> unknown = List.of();
        private List<String> nextChecks = List.of();
        private List<String> unsearchedScope = List.of();
        private boolean deprioritized;

        public Builder candidateId(String v) {
            this.candidateId = v;
            return this;
        }

        public Builder candidateKind(CandidateKind v) {
            this.candidateKind = v;
            return this;
        }

        public Builder statement(String v) {
            this.statement = v;
            return this;
        }

        public Builder investigationPriority(int v) {
            this.investigationPriority = v;
            return this;
        }

        public Builder supports(List<HypothesisEvidenceRef> v) {
            this.supports = v;
            return this;
        }

        public Builder weakens(List<HypothesisEvidenceRef> v) {
            this.weakens = v;
            return this;
        }

        public Builder unknown(List<HypothesisEvidenceRef> v) {
            this.unknown = v;
            return this;
        }

        public Builder nextChecks(List<String> v) {
            this.nextChecks = v;
            return this;
        }

        public Builder unsearchedScope(List<String> v) {
            this.unsearchedScope = v;
            return this;
        }

        public Builder deprioritized(boolean v) {
            this.deprioritized = v;
            return this;
        }

        public HypothesisLedgerEntry build() {
            return new HypothesisLedgerEntry(this);
        }
    }
}
