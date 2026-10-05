package com.thingworx.things.agent.investigation;

import java.util.Objects;

import com.thingworx.things.agent.analysis.AnalysisBudgetAccounting;

/**
 * App-facing G7 orchestration (FRC-4): bounded investigation → scorecard → compact envelope.
 * Playbook-only packaging (D3) — no resident RCA tool.
 */
public final class U6RcaInvestigationAppRunner {

    private U6RcaInvestigationAppRunner() {}

    public static final class Outcome {
        private final RcaInvestigationResult result;
        private final U6RcaEnvelopeFactory.CompactEnvelope envelope;

        Outcome(RcaInvestigationResult result, U6RcaEnvelopeFactory.CompactEnvelope envelope) {
            this.result = result;
            this.envelope = envelope;
        }

        public RcaInvestigationResult result() {
            return result;
        }

        public U6RcaEnvelopeFactory.CompactEnvelope envelope() {
            return envelope;
        }
    }

    public static Outcome run(
            RcaInvestigationRequest request,
            CandidateCatalog catalog,
            InvestigationProfile profile,
            InvestigationEvidenceSource evidenceSource,
            String profileDigest) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(evidenceSource, "evidenceSource");

        long started = System.nanoTime();
        RcaInvestigationResult result =
                BoundedInvestigationEngine.investigate(request, catalog, profile, evidenceSource);
        long wallMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        boolean clamped = result.reasonCodes().contains(RcaOutcomeCodes.SEARCH_BOUNDARY_EXCEEDED);
        AnalysisBudgetAccounting accounting = request.budget().toEnvelopeBudget(
                result.ledger().size(),
                0L,
                wallMs,
                clamped);
        U6RcaEnvelopeFactory.CompactEnvelope envelope =
                U6RcaEnvelopeFactory.fromInvestigation(result, profileDigest, accounting);
        return new Outcome(result, envelope);
    }
}
