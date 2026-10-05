package com.thingworx.things.agent.investigation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.analysis.HalfOpenWindow;

/**
 * Deterministic event co-occurrence relative to the incident evidence window (fleet-rca §7.3).
 * Counts and time deltas are association facts — never causal language.
 */
public final class EventCoOccurrence {

    private EventCoOccurrence() {}

    public static Result forCandidate(
            ResolvedCandidate candidate, HalfOpenWindow window, List<InvestigationEvent> events) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(events, "events");
        if (candidate.kind() != CandidateKind.EVENT
                && candidate.kind() != CandidateKind.MAINTENANCE
                && candidate.kind() != CandidateKind.BATCH) {
            return Result.empty();
        }
        String asset = candidate.relatedAssetSemanticId();
        List<InvestigationEvent> matched = new ArrayList<>();
        Long minAbsDeltaMs = null;
        Instant anchor = window.startInclusive();
        for (InvestigationEvent e : events) {
            if (e.kind() != candidate.kind()) {
                continue;
            }
            if (asset != null && !asset.equals(e.assetSemanticId())) {
                continue;
            }
            Instant t = e.occurredAt();
            if (t.isBefore(window.startInclusive()) || !t.isBefore(window.endExclusive())) {
                continue;
            }
            matched.add(e);
            long delta = Math.abs(Duration.between(anchor, t).toMillis());
            if (minAbsDeltaMs == null || delta < minAbsDeltaMs) {
                minAbsDeltaMs = delta;
            }
        }
        return new Result(matched.size(), minAbsDeltaMs, List.copyOf(matched));
    }

    public static final class Result {
        private final int eventCount;
        private final Long minAbsDeltaMillis;
        private final List<InvestigationEvent> matched;

        Result(int eventCount, Long minAbsDeltaMillis, List<InvestigationEvent> matched) {
            this.eventCount = eventCount;
            this.minAbsDeltaMillis = minAbsDeltaMillis;
            this.matched = matched;
        }

        static Result empty() {
            return new Result(0, null, List.of());
        }

        public int eventCount() {
            return eventCount;
        }

        public Long minAbsDeltaMillis() {
            return minAbsDeltaMillis;
        }

        public List<InvestigationEvent> matched() {
            return matched;
        }

        public boolean hasAssociation() {
            return eventCount > 0;
        }
    }
}
