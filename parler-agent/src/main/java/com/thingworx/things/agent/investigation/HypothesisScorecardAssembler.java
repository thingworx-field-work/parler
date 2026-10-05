package com.thingworx.things.agent.investigation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Assembles U6 hypothesis ledger rows and ranks next-check work (fleet-rca §7.4). Association
 * language only — never causal probability. When the profile has no reviewed weights (D7),
 * contributions stay {@code 0} and ranking uses lexicographic test order.
 */
public final class HypothesisScorecardAssembler {

    private static final int NO_SUPPORT_LEX = Integer.MAX_VALUE;

    private HypothesisScorecardAssembler() {}

    public static List<HypothesisLedgerEntry> assemble(
            InvestigationProfile profile,
            List<ResolvedCandidate> candidates,
            List<CandidateEvidenceObservation> observations,
            Map<String, EventCoOccurrence.Result> coOccurrenceByCandidate,
            List<String> sharedUnsearchedScope) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(coOccurrenceByCandidate, "coOccurrenceByCandidate");
        Objects.requireNonNull(sharedUnsearchedScope, "sharedUnsearchedScope");

        Map<String, List<CandidateEvidenceObservation>> byCandidate = new HashMap<>();
        for (CandidateEvidenceObservation obs : observations) {
            byCandidate.computeIfAbsent(obs.candidateId(), k -> new ArrayList<>()).add(obs);
        }

        List<Scored> scored = new ArrayList<>();
        for (ResolvedCandidate candidate : candidates) {
            scored.add(scoreOne(
                    profile,
                    candidate,
                    byCandidate.getOrDefault(candidate.candidateId(), List.of()),
                    coOccurrenceByCandidate.getOrDefault(candidate.candidateId(), EventCoOccurrence.Result.empty()),
                    sharedUnsearchedScope));
        }

        scored.sort(rankOrder(profile.useNumericWeights()));
        List<HypothesisLedgerEntry> out = new ArrayList<>(scored.size());
        for (Scored s : scored) {
            out.add(s.entry);
        }
        return List.copyOf(out);
    }

    private static Scored scoreOne(
            InvestigationProfile profile,
            ResolvedCandidate candidate,
            List<CandidateEvidenceObservation> observations,
            EventCoOccurrence.Result coOccurrence,
            List<String> sharedUnsearchedScope) {
        Map<String, CandidateEvidenceObservation> obsByTest = new HashMap<>();
        for (CandidateEvidenceObservation obs : observations) {
            obsByTest.putIfAbsent(obs.testId(), obs);
        }

        List<HypothesisEvidenceRef> supports = new ArrayList<>();
        List<HypothesisEvidenceRef> weakens = new ArrayList<>();
        List<HypothesisEvidenceRef> unknown = new ArrayList<>();
        List<String> nextChecks = new ArrayList<>();
        int priority = 0;
        int completedHighPriority = 0;
        int bestSupportLexOrder = NO_SUPPORT_LEX;
        boolean deprioritized = false;
        Set<String> seenTests = new HashSet<>();

        // Event co-occurrence becomes a support when any matching events exist.
        if (coOccurrence.hasAssociation()) {
            String testId = "event_co_occurrence";
            if (profile.tests().stream().anyMatch(t -> testId.equals(t.testId()))) {
                EvidenceTestSpec spec = profile.requireTest(testId);
                int w = reviewedContribution(profile, spec.supportWeight());
                supports.add(HypothesisEvidenceRef.support(
                        "events:" + coOccurrence.eventCount()
                                + ";minAbsDeltaMs=" + coOccurrence.minAbsDeltaMillis(),
                        testId,
                        w));
                priority += w;
                bestSupportLexOrder = Math.min(bestSupportLexOrder, spec.lexicographicOrder());
                if (spec.highPriority()) {
                    completedHighPriority++;
                }
                seenTests.add(testId);
            }
        }

        for (EvidenceTestSpec spec : profile.tests()) {
            if (seenTests.contains(spec.testId())) {
                continue;
            }
            CandidateEvidenceObservation obs = obsByTest.get(spec.testId());
            if (obs == null) {
                unknown.add(HypothesisEvidenceRef.unknown(spec.testId(), "not_run"));
                nextChecks.add(spec.nextCheckTemplate());
                continue;
            }
            seenTests.add(spec.testId());
            switch (obs.polarity()) {
                case SUPPORT: {
                    int w = reviewedContribution(profile, spec.supportWeight());
                    supports.add(HypothesisEvidenceRef.support(obs.evidenceRef(), spec.testId(), w));
                    priority += w;
                    bestSupportLexOrder = Math.min(bestSupportLexOrder, spec.lexicographicOrder());
                    if (spec.highPriority()) {
                        completedHighPriority++;
                    }
                    break;
                }
                case WEAKEN: {
                    int w = reviewedContribution(profile, spec.weakenWeight());
                    weakens.add(HypothesisEvidenceRef.weaken(obs.evidenceRef(), spec.testId(), w));
                    priority -= w;
                    if (spec.blockingWeaken()) {
                        deprioritized = true;
                    }
                    if (spec.highPriority()) {
                        completedHighPriority++;
                    }
                    break;
                }
                case UNKNOWN:
                    unknown.add(HypothesisEvidenceRef.unknown(spec.testId(), obs.unknownReason()));
                    nextChecks.add(spec.nextCheckTemplate());
                    break;
                default:
                    throw new IllegalStateException("unexpected polarity " + obs.polarity());
            }
        }

        if (!profile.useNumericWeights()) {
            // D7: do not invent a numeric investigationPriority when weights are absent.
            priority = 0;
        }
        // Blocking weaken keeps the candidate but ranks it behind ordinary entries via the
        // leading {@code deprioritized} comparator key (both weighted and unweighted modes).

        HypothesisLedgerEntry entry = HypothesisLedgerEntry.builder()
                .candidateId(candidate.candidateId())
                .candidateKind(candidate.kind())
                .statement(candidate.statement())
                .investigationPriority(priority)
                .supports(supports)
                .weakens(weakens)
                .unknown(unknown)
                .nextChecks(nextChecks)
                .unsearchedScope(sharedUnsearchedScope)
                .deprioritized(deprioritized)
                .build();
        return new Scored(
                entry,
                completedHighPriority,
                unknown.size(),
                candidate.relationDistance(),
                bestSupportLexOrder,
                deprioritized);
    }

    /** Reviewed weight when enabled; otherwise {@code 0} — never invent defaults (D7). */
    private static int reviewedContribution(InvestigationProfile profile, int reviewedWeight) {
        return profile.useNumericWeights() ? reviewedWeight : 0;
    }

    private static Comparator<Scored> rankOrder(boolean useNumericWeights) {
        // DEPRIORITIZED is a top-level demotion below ordinary work-queue entries (§7.4),
        // then mode-specific ranking / tiebreaks.
        Comparator<Scored> modeRank;
        if (useNumericWeights) {
            modeRank = Comparator.comparingInt((Scored s) -> s.entry.investigationPriority()).reversed();
        } else {
            // Lexicographic: better (lower) completed support-test order first; no support last.
            modeRank = Comparator.comparingInt((Scored s) -> s.bestSupportLexOrder);
        }
        return Comparator
                .comparingInt((Scored s) -> s.deprioritized ? 1 : 0) // ordinary before DEPRIORITIZED
                .thenComparing(modeRank)
                .thenComparingInt((Scored s) -> -s.completedHighPriority)
                .thenComparingInt(s -> s.unknownCount)
                .thenComparingInt(s -> s.relationDistance)
                .thenComparing(s -> s.entry.candidateId());
    }

    private static final class Scored {
        final HypothesisLedgerEntry entry;
        final int completedHighPriority;
        final int unknownCount;
        final int relationDistance;
        final int bestSupportLexOrder;
        final boolean deprioritized;

        Scored(
                HypothesisLedgerEntry entry,
                int completedHighPriority,
                int unknownCount,
                int relationDistance,
                int bestSupportLexOrder,
                boolean deprioritized) {
            this.entry = entry;
            this.completedHighPriority = completedHighPriority;
            this.unknownCount = unknownCount;
            this.relationDistance = relationDistance;
            this.bestSupportLexOrder = bestSupportLexOrder;
            this.deprioritized = deprioritized;
        }
    }
}
