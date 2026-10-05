package com.thingworx.things.agent.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * G16 Provider route orchestrator (U7 D8 / D10 / D11 / D12).
 *
 * <p>SPR-3: failure classification and same-provider retry planning. SPR-4: route eligibility,
 * circuit admission, multi-provider fallback selection, and degraded outcome. Rate limiting
 * stays on {@link com.thingworx.things.agent.llm.ratecontrol.LLMAPIProviderRateGate}; this
 * coordinator must not create a second token bucket.
 */
public final class ProviderRouteCoordinator {

    private ProviderRouteCoordinator() {}

    public static LlmProviderFailureClass classify(LlmProviderFailureSignal signal) {
        return LlmProviderFailureClassifier.classify(signal);
    }

    /**
     * Plan the next same-provider action after a failure. Callers apply {@link #delayMs} via an
     * injected sleeper/scheduler, then {@link ProviderRetryBudget#recordWaitMs} and
     * {@link ProviderRetryBudget#recordSameProviderRetry}.
     *
     * <p>Same-provider retry-cap exhaustion and a {@code Retry-After} that exceeds remaining
     * wait/wall budget yield {@link ProviderSameProviderRetryPlan.Action#FALLBACK_CANDIDATE}
     * for fallback-eligible classes when route attempt/wall budget remains — not
     * {@link ProviderSameProviderRetryPlan.Action#STOP_EXHAUSTED} and not a shortened
     * {@link ProviderSameProviderRetryPlan.Action#RETRY_SAME}.
     *
     * @param cancelled when true, forces {@link LlmProviderFailureClass#CANCELLED} / terminal stop
     * @param unitRandom jitter in {@code [0, 1)} — delay only; never Provider order
     */
    public static ProviderSameProviderRetryPlan planSameProviderRetry(
            LlmProviderFailureSignal signal,
            ProviderRetryBudget budget,
            boolean cancelled,
            DoubleSupplier unitRandom) {
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(unitRandom, "unitRandom");

        LlmProviderFailureSignal effective = cancelled || (signal != null && signal.cancelled())
                ? LlmProviderFailureSignal.forCancelled()
                : signal;
        LlmProviderFailureClass cls = LlmProviderFailureClassifier.classify(effective);

        if (cls == LlmProviderFailureClass.CANCELLED) {
            return terminal(cls, "cancelled");
        }
        if (!cls.sameProviderRetryEligible()) {
            if (cls.fallbackEligible()) {
                return fallbackCandidate(cls, "same-provider retry not eligible; fallback candidate");
            }
            return terminal(cls, "terminal failure class");
        }

        if (!budget.canStartAttempt() || budget.remainingWallMs() <= 0L) {
            return exhausted(cls, "route attempt/wall budget exhausted");
        }

        long remaining = Math.min(budget.remainingWaitMs(), budget.remainingWallMs());
        long hint = ProviderRetryBackoff.retryAfterHintMs(
                effective != null ? effective.safeHeaders() : null);

        // D10 / §7.3: respect Retry-After unless it exceeds total budget — never shorten into
        // an early same-provider retry.
        if (hint > 0L && hint > remaining) {
            return sameProviderCannotProceed(
                    cls, budget, "Retry-After exceeds remaining wait/wall budget");
        }

        if (!budget.canSameProviderRetry()) {
            return sameProviderCannotProceed(cls, budget, "same-provider retry cap reached");
        }

        if (remaining <= 0L) {
            return sameProviderCannotProceed(cls, budget, "no remaining wait/wall budget");
        }

        long delay = ProviderRetryBackoff.delayMs(
                budget.sameProviderRetriesUsed(), hint, remaining, unitRandom);
        if (delay <= 0L && hint > 0L) {
            return sameProviderCannotProceed(
                    cls, budget, "Retry-After cannot be honored within remaining budget");
        }
        if (!budget.remainingWaitAllows(delay)) {
            return sameProviderCannotProceed(
                    cls, budget, "proposed backoff exceeds remaining budget");
        }
        return new ProviderSameProviderRetryPlan(
                ProviderSameProviderRetryPlan.Action.RETRY_SAME,
                cls,
                delay,
                "same-provider retry after backoff",
                ProviderRetryBackoff.telemetryToken(cls, delay));
    }

    /**
     * Same-provider path cannot continue, but the route may still have attempt/wall room for
     * another Provider (SPR-4). Wait-budget exhaustion is treated as route exhaustion.
     */
    private static ProviderSameProviderRetryPlan sameProviderCannotProceed(
            LlmProviderFailureClass cls, ProviderRetryBudget budget, String reason) {
        if (cls.fallbackEligible()
                && budget.canStartAttempt()
                && budget.remainingWallMs() > 0L
                && budget.remainingWaitMs() > 0L) {
            return fallbackCandidate(cls, reason + "; fallback candidate");
        }
        return exhausted(cls, reason);
    }

    private static ProviderSameProviderRetryPlan fallbackCandidate(
            LlmProviderFailureClass cls, String reason) {
        return new ProviderSameProviderRetryPlan(
                ProviderSameProviderRetryPlan.Action.FALLBACK_CANDIDATE,
                cls,
                0L,
                reason,
                ProviderRetryBackoff.telemetryToken(cls, 0L));
    }

    private static ProviderSameProviderRetryPlan terminal(LlmProviderFailureClass cls, String reason) {
        return new ProviderSameProviderRetryPlan(
                ProviderSameProviderRetryPlan.Action.STOP_TERMINAL,
                cls,
                0L,
                reason,
                ProviderRetryBackoff.telemetryToken(cls, 0L));
    }

    private static ProviderSameProviderRetryPlan exhausted(LlmProviderFailureClass cls, String reason) {
        return new ProviderSameProviderRetryPlan(
                ProviderSameProviderRetryPlan.Action.STOP_EXHAUSTED,
                cls,
                0L,
                reason,
                ProviderRetryBackoff.telemetryToken(cls, 0L));
    }

    /**
     * Select the next Provider after a same-provider {@code FALLBACK_CANDIDATE} (or when starting
     * the route). Honors eligibility, circuit admission, {@code maxProvidersTried}, and
     * no-fallback-after-partial-output.
     *
     * @param directory map of Provider Thing name → eligibility view
     * @param triedProviders already attempted Provider Thing names (order preserved for audit)
     * @param evidenceStillUsable whether deterministic evidence remains for the degraded summary
     */
    public static ProviderFallbackPlan planFallback(
            ProviderRouteProfile profile,
            ProviderRoundRequirements requirements,
            Map<String, ProviderEligibilityView> directory,
            ProviderCircuitBreaker circuit,
            ProviderRetryBudget budget,
            List<String> triedProviders,
            LlmProviderFailureClass lastFailure,
            boolean evidenceStillUsable) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(requirements, "requirements");
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(circuit, "circuit");
        Objects.requireNonNull(budget, "budget");
        List<String> tried = triedProviders == null ? List.of() : List.copyOf(triedProviders);

        if (requirements.outputAccepted()
                || lastFailure == LlmProviderFailureClass.PARTIAL_OR_UNKNOWN_OUTCOME) {
            return ProviderFallbackPlan.forbidden("no_fallback_after_partial_or_unknown_output");
        }
        if (lastFailure == LlmProviderFailureClass.CANCELLED) {
            return ProviderFallbackPlan.forbidden("cancelled");
        }
        if (lastFailure != null && !lastFailure.fallbackEligible()) {
            return ProviderFallbackPlan.forbidden("failure_class_forbids_fallback:" + lastFailure.name());
        }
        if (!budget.canStartAttempt() || budget.remainingWallMs() <= 0L || budget.remainingWaitMs() <= 0L) {
            return ProviderFallbackPlan.degraded(
                    ProviderDegradedOutcome.exhausted(profile, tried, lastFailure, evidenceStillUsable),
                    "route_budget_exhausted");
        }
        if (tried.size() >= profile.maxProvidersTried()) {
            return ProviderFallbackPlan.degraded(
                    ProviderDegradedOutcome.exhausted(profile, tried, lastFailure, evidenceStillUsable),
                    "max_providers_tried");
        }

        Map<String, String> skipReasons = new LinkedHashMap<>();
        for (String name : profile.providers()) {
            if (containsIgnoreCase(tried, name)) {
                continue;
            }
            ProviderEligibilityView view = findView(directory, name);
            if (view == null) {
                skipReasons.put(name, "provider_not_in_directory");
                continue;
            }
            if (circuit.isConfigIneligible(view.providerThingName())) {
                skipReasons.put(name, "circuit_config_ineligible");
                continue;
            }
            var ineligible = ProviderRouteEligibility.ineligibilityReason(profile, requirements, view);
            if (ineligible.isPresent()) {
                skipReasons.put(name, ineligible.get());
                continue;
            }
            if (!circuit.admit(view.providerThingName())) {
                skipReasons.put(name, "circuit_deny:" + circuit.state(view.providerThingName()).name());
                continue;
            }
            return ProviderFallbackPlan.tryProvider(
                    view.providerThingName(),
                    "next_eligible_provider; skipped=" + skipReasons);
        }

        return ProviderFallbackPlan.degraded(
                ProviderDegradedOutcome.exhausted(profile, tried, lastFailure, evidenceStillUsable),
                "all_providers_ineligible_or_denied; skipped=" + skipReasons);
    }

    /** Sanitized eligibility diagnostics for operator/audit (no secrets). */
    public static Map<String, String> explainIneligibility(
            ProviderRouteProfile profile,
            ProviderRoundRequirements requirements,
            Map<String, ProviderEligibilityView> directory) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : profile.providers()) {
            ProviderEligibilityView view = findView(directory, name);
            if (view == null) {
                out.put(name, "provider_not_in_directory");
            } else {
                out.put(name, ProviderRouteEligibility.ineligibilityReason(profile, requirements, view)
                        .orElse("eligible"));
            }
        }
        return Map.copyOf(out);
    }

    private static ProviderEligibilityView findView(
            Map<String, ProviderEligibilityView> directory, String name) {
        if (directory.containsKey(name)) {
            return directory.get(name);
        }
        for (Map.Entry<String, ProviderEligibilityView> e : directory.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static boolean containsIgnoreCase(List<String> values, String needle) {
        for (String v : values) {
            if (v != null && v.equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
    }
}
