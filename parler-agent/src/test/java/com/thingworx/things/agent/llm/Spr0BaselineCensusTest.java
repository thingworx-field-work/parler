package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.configrepo.ConfigurationRepositoryPaths;
import com.thingworx.things.agent.execution.BudgetVector;
import com.thingworx.things.agent.tools.AnalyzeCachedResultToolSchema;

/**
 * Locks the service-provider-resilience baseline: the failure-class, circuit, and capability
 * vocabulary is frozen; the analyze tool surface and global returned-rows default are unchanged; and
 * there is no second registry or connector plane.
 */
class Spr0BaselineCensusTest {

    @Test
    void failureClassTableMatchesD9() {
        assertEquals(9, LlmProviderFailureClass.values().length);

        assertTrue(LlmProviderFailureClass.RATE_LIMITED.sameProviderRetryEligible());
        assertTrue(LlmProviderFailureClass.RATE_LIMITED.fallbackEligible());

        assertTrue(LlmProviderFailureClass.TIMEOUT_BEFORE_RESPONSE.sameProviderRetryEligible());
        assertTrue(LlmProviderFailureClass.TRANSIENT_UPSTREAM.fallbackEligible());

        assertFalse(LlmProviderFailureClass.MODEL_UNAVAILABLE.sameProviderRetryEligible());
        assertTrue(LlmProviderFailureClass.MODEL_UNAVAILABLE.fallbackEligible());

        assertFalse(LlmProviderFailureClass.AUTHENTICATION_OR_CONFIG.sameProviderRetryEligible());
        assertFalse(LlmProviderFailureClass.AUTHENTICATION_OR_CONFIG.fallbackEligible());

        assertFalse(LlmProviderFailureClass.INVALID_REQUEST_OR_SCHEMA.fallbackEligible());
        assertFalse(LlmProviderFailureClass.PARTIAL_OR_UNKNOWN_OUTCOME.fallbackEligible());
        assertFalse(LlmProviderFailureClass.CANCELLED.fallbackEligible());

        assertFalse(LlmProviderFailureClass.POLICY_OR_EGRESS_BLOCKED.sameProviderRetryEligible());
        assertTrue(LlmProviderFailureClass.POLICY_OR_EGRESS_BLOCKED.fallbackEligible());
    }

    @Test
    void capabilityTokensFrozenAtSix() {
        assertEquals(6, ProviderCapabilityToken.values().length);
        assertEquals(
                EnumSet.of(
                        ProviderCapabilityToken.TOOLS,
                        ProviderCapabilityToken.STRICT_JSON_SCHEMA,
                        ProviderCapabilityToken.HITL_CONTINUATION,
                        ProviderCapabilityToken.CONTEXT_WINDOW,
                        ProviderCapabilityToken.DATA_EGRESS_REGION,
                        ProviderCapabilityToken.STRUCTURED_OUTPUT),
                EnumSet.allOf(ProviderCapabilityToken.class));
    }

    @Test
    void circuitStatesAndProvisionalDefaults() {
        assertEquals(3, ProviderCircuitState.values().length);
        assertTrue(ProviderCircuitDefaults.PROVISIONAL);
        assertTrue(ProviderCircuitDefaults.TRANSIENT_FAILURE_THRESHOLD > 0);
        assertTrue(ProviderCircuitDefaults.OPEN_COOLDOWN_MS > 0);
    }

    @Test
    void routeProfileRejectsEmptyProvidersAndExposesBudgetKeys() {
        assertThrows(IllegalArgumentException.class, () -> new ProviderRouteProfile(
                "x",
                List.of(),
                Set.of(),
                Set.of(),
                1,
                2,
                3,
                30_000L,
                60_000L,
                "APPROVED_INTERACTIVE",
                true));
        ProviderRouteProfile profile = new ProviderRouteProfile(
                "interactive-industrial-tools-v1",
                List.of("PrimaryProviderThing", "FallbackProviderThing"),
                Set.of(ProviderCapabilityToken.TOOLS, ProviderCapabilityToken.HITL_CONTINUATION),
                Set.of("PUBLIC", "INTERNAL"),
                1,
                2,
                3,
                30_000L,
                60_000L,
                "APPROVED_INTERACTIVE",
                true);
        assertEquals(2, profile.providers().size());
        assertEquals(1, profile.maxSameProviderRetries());
        assertEquals(3, profile.maxTotalAttempts());
        assertTrue(profile.fallbackVisible());
        assertThrows(UnsupportedOperationException.class, () -> profile.providers().add("x"));
    }

    @Test
    void coordinatorOwnershipAndRoutePathRemainLocked() {
        assertEquals("ProviderRouteCoordinator", ProviderRouteCoordinator.class.getSimpleName());
        assertEquals("/providers/route_profiles.json", ConfigurationRepositoryPaths.PROVIDER_ROUTE_PROFILES);
        // Ownership marker stays unconstructable; SPR-3 adds static classify/plan methods only.
        assertEquals(0, ProviderRouteCoordinator.class.getDeclaredConstructors()[0].getParameterCount());
    }

    @Test
    void spr0DoesNotChangeAnalyzeCachedResultSurface() {
        @SuppressWarnings("unchecked")
        List<String> required =
                (List<String>) AnalyzeCachedResultToolSchema.parametersSchema().get("required");
        assertTrue(required.contains("cacheId"));
        assertTrue(required.contains("operation"));
        assertEquals(2, required.size());
    }

    @Test
    void noGlobalReturnedRowsDefaultChange() {
        assertEquals(5000L, BudgetVector.defaultsForTabular().maxReturnedRows());
    }

    @Test
    void noConnectorPlaneTypes() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
                "com.thingworx.things.agent.connector.ConnectorProvider"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
                "com.thingworx.things.agent.mcp.McpClient"));
    }
}
