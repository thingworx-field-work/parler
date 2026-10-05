package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ProviderFallbackPlannerTest {

    @Test
    void capabilityMismatchAndDisabledAndEgressAndHitlAndContext() {
        ProviderRouteProfile profile = profile(List.of("P1", "P2"));
        ProviderRoundRequirements req = new ProviderRoundRequirements(
                Set.of(ProviderCapabilityToken.TOOLS, ProviderCapabilityToken.HITL_CONTINUATION),
                "INTERNAL",
                "US",
                true,
                8_000L,
                false);
        Map<String, ProviderEligibilityView> directory = Map.of(
                "P1", view("P1", true, Set.of(ProviderCapabilityToken.TOOLS), Set.of("PUBLIC"), Set.of("US"), 16_000),
                "P2", view("P2", false, Set.of(ProviderCapabilityToken.TOOLS, ProviderCapabilityToken.HITL_CONTINUATION,
                        ProviderCapabilityToken.CONTEXT_WINDOW, ProviderCapabilityToken.DATA_EGRESS_REGION),
                        Set.of("INTERNAL"), Set.of("EU"), 16_000));
        Map<String, String> reasons = ProviderRouteCoordinator.explainIneligibility(profile, req, directory);
        assertEquals("capability_missing:HITL_CONTINUATION", reasons.get("P1"));
        assertEquals("provider_disabled", reasons.get("P2"));

        ProviderEligibilityView hitlOk = view(
                "P3",
                true,
                Set.of(
                        ProviderCapabilityToken.TOOLS,
                        ProviderCapabilityToken.HITL_CONTINUATION,
                        ProviderCapabilityToken.CONTEXT_WINDOW,
                        ProviderCapabilityToken.DATA_EGRESS_REGION),
                Set.of("INTERNAL"),
                Set.of("US"),
                4_000);
        assertEquals(
                "context_window_insufficient",
                ProviderRouteEligibility.ineligibilityReason(profile, req, hitlOk).orElse(""));
        ProviderEligibilityView regionBad = view(
                "P4",
                true,
                Set.of(
                        ProviderCapabilityToken.TOOLS,
                        ProviderCapabilityToken.HITL_CONTINUATION,
                        ProviderCapabilityToken.CONTEXT_WINDOW,
                        ProviderCapabilityToken.DATA_EGRESS_REGION),
                Set.of("INTERNAL"),
                Set.of("EU"),
                16_000);
        assertEquals(
                "egress_region_denied",
                ProviderRouteEligibility.ineligibilityReason(profile, req, regionBad).orElse(""));
    }

    @Test
    void fallbackSuccessSelectsNextEligibleProvider() {
        ProviderRouteProfile profile = profile(List.of("Primary", "Fallback"));
        ProviderRoundRequirements req = ProviderRoundRequirements.ofCapabilities(
                Set.of(ProviderCapabilityToken.TOOLS));
        Map<String, ProviderEligibilityView> directory = Map.of(
                "Primary", full("Primary"),
                "Fallback", full("Fallback"));
        ProviderCircuitBreaker circuit = new ProviderCircuitBreaker(
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        ProviderRetryBudget budget = new ProviderRetryBudget(
                1, 5, 60_000L, 60_000L, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        budget.recordAttemptStarted();
        ProviderFallbackPlan plan = ProviderRouteCoordinator.planFallback(
                profile,
                req,
                directory,
                circuit,
                budget,
                List.of("Primary"),
                LlmProviderFailureClass.TRANSIENT_UPSTREAM,
                true);
        assertEquals(ProviderFallbackPlan.Action.TRY_PROVIDER, plan.action());
        assertEquals("Fallback", plan.nextProviderThingName().orElseThrow());
    }

    @Test
    void allFallbackExhaustedYieldsDegraded() {
        ProviderRouteProfile profile = profile(List.of("Primary", "Fallback"));
        ProviderRoundRequirements req = ProviderRoundRequirements.ofCapabilities(
                Set.of(ProviderCapabilityToken.TOOLS));
        Map<String, ProviderEligibilityView> directory = Map.of(
                "Primary", full("Primary"),
                "Fallback", full("Fallback"));
        ProviderCircuitBreaker circuit = new ProviderCircuitBreaker(
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        ProviderRetryBudget budget = new ProviderRetryBudget(
                1, 5, 60_000L, 60_000L, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        budget.recordAttemptStarted();
        ProviderFallbackPlan plan = ProviderRouteCoordinator.planFallback(
                profile,
                req,
                directory,
                circuit,
                budget,
                List.of("Primary", "Fallback"),
                LlmProviderFailureClass.RATE_LIMITED,
                true);
        assertEquals(ProviderFallbackPlan.Action.DEGRADED, plan.action());
        assertTrue(plan.degraded().isPresent());
        assertEquals(ProviderDegradedOutcome.CODE, plan.degraded().get().code());
        assertTrue(plan.degraded().get().evidenceStillUsable());
    }

    @Test
    void partialOutputForbidsFallback() {
        ProviderRouteProfile profile = profile(List.of("Primary", "Fallback"));
        ProviderRoundRequirements req = ProviderRoundRequirements.ofCapabilities(Set.of())
                .withOutputAccepted(true);
        ProviderFallbackPlan plan = ProviderRouteCoordinator.planFallback(
                profile,
                req,
                Map.of("Primary", full("Primary"), "Fallback", full("Fallback")),
                new ProviderCircuitBreaker(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)),
                new ProviderRetryBudget(1, 5, 60_000L, 60_000L, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)),
                List.of("Primary"),
                LlmProviderFailureClass.TRANSIENT_UPSTREAM,
                true);
        assertEquals(ProviderFallbackPlan.Action.FORBIDDEN, plan.action());
    }

    @Test
    void openCircuitSkipsToNextOrDegrades() {
        ProviderRouteProfile profile = profile(List.of("Primary", "Fallback"));
        ProviderRoundRequirements req = ProviderRoundRequirements.ofCapabilities(
                Set.of(ProviderCapabilityToken.TOOLS));
        MutableClock clock = new MutableClock(0L);
        ProviderCircuitBreaker circuit = new ProviderCircuitBreaker(clock, 1, 10_000L);
        circuit.recordFailure("Fallback", LlmProviderFailureClass.TRANSIENT_UPSTREAM);
        assertEquals(ProviderCircuitState.OPEN, circuit.state("Fallback"));
        ProviderRetryBudget budget = new ProviderRetryBudget(1, 5, 60_000L, 60_000L, clock);
        budget.recordAttemptStarted();
        ProviderFallbackPlan plan = ProviderRouteCoordinator.planFallback(
                profile,
                req,
                Map.of("Primary", full("Primary"), "Fallback", full("Fallback")),
                circuit,
                budget,
                List.of("Primary"),
                LlmProviderFailureClass.TRANSIENT_UPSTREAM,
                false);
        assertEquals(ProviderFallbackPlan.Action.DEGRADED, plan.action());
    }

    private static ProviderRouteProfile profile(List<String> providers) {
        return new ProviderRouteProfile(
                "r1",
                providers,
                Set.of(ProviderCapabilityToken.TOOLS),
                Set.of("INTERNAL", "PUBLIC"),
                1,
                providers.size(),
                5,
                60_000L,
                60_000L,
                "APPROVED_INTERACTIVE",
                true);
    }

    private static ProviderEligibilityView full(String name) {
        return view(
                name,
                true,
                Set.of(
                        ProviderCapabilityToken.TOOLS,
                        ProviderCapabilityToken.STRICT_JSON_SCHEMA,
                        ProviderCapabilityToken.HITL_CONTINUATION,
                        ProviderCapabilityToken.CONTEXT_WINDOW,
                        ProviderCapabilityToken.DATA_EGRESS_REGION,
                        ProviderCapabilityToken.STRUCTURED_OUTPUT),
                Set.of("PUBLIC", "INTERNAL"),
                Set.of("US", "EU"),
                128_000);
    }

    private static ProviderEligibilityView view(
            String name,
            boolean enabled,
            Set<ProviderCapabilityToken> caps,
            Set<String> classifications,
            Set<String> regions,
            long contextTokens) {
        return new ProviderEligibilityView(
                name, enabled, caps, classifications, regions, contextTokens, "APPROVED_INTERACTIVE");
    }

    private static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long start) {
            this.millis = start;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }
}
