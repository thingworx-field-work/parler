package com.thingworx.things.agent.configrepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.ToolResultEgressGateway;
import com.thingworx.things.agent.llm.LlmProviderFailureClass;
import com.thingworx.things.agent.llm.LlmProviderFailureSignal;
import com.thingworx.things.agent.llm.ProviderCapabilityToken;
import com.thingworx.things.agent.llm.ProviderCircuitBreaker;
import com.thingworx.things.agent.llm.ProviderDegradedOutcome;
import com.thingworx.things.agent.llm.ProviderEligibilityView;
import com.thingworx.things.agent.llm.ProviderFallbackPlan;
import com.thingworx.things.agent.llm.ProviderRetryBudget;
import com.thingworx.things.agent.llm.ProviderRoundRequirements;
import com.thingworx.things.agent.llm.ProviderRouteCoordinator;
import com.thingworx.things.agent.llm.ProviderRouteProfile;
import com.thingworx.things.agent.llm.ProviderRouteProfilesParser;
import com.thingworx.things.agent.llm.ProviderSameProviderRetryPlan;
import com.thingworx.things.agent.llm.ToolDefinition;

/**
 * SPR-5 offline App/provider matrix: demo READ_ONLY + MUTATING descriptors, dry-run enforce,
 * egress classification stamp, and Provider classify → retry → fallback → degraded path.
 */
class Spr5AppProviderMatrixTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-21T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void demoDescriptorsParseAndPolicyMatrix() throws Exception {
        ObjectNode root = (ObjectNode) JSON.readTree(U7DemoCapabilityProfiles.extendedToolsFixtureJson());
        ServiceParameterLookup params = stubParams(Set.of("dryRun", "requestId"));

        ServiceCapabilityMetadata read = parseTool(root, U7DemoCapabilityProfiles.READ_TOOL, params);
        ServiceCapabilityMetadata mut = parseTool(root, U7DemoCapabilityProfiles.MUTATING_TOOL, params);

        assertEquals(ServiceCapabilityRisk.READ_ONLY, read.risk().orElseThrow());
        assertFalse(read.dryRunSupported());
        assertEquals(List.of("INTERNAL"), read.dataClassification());

        assertEquals(ServiceCapabilityRisk.MUTATING, mut.risk().orElseThrow());
        assertTrue(mut.dryRunSupported());
        assertEquals("dryRun", mut.dryRunParameter());

        ExtendedToolDefinition readEt = tool(U7DemoCapabilityProfiles.READ_TOOL, true, true, read);
        ExtendedToolDefinition mutEt = tool(U7DemoCapabilityProfiles.MUTATING_TOOL, false, false, mut);

        assertTrue(ServiceCapabilityRuntimePolicy.isModelAdvertisable(readEt));
        assertTrue(ServiceCapabilityRuntimePolicy.isPlaybookEligible(readEt));
        assertEquals(
                ServiceCapabilityRuntimePolicy.DirectDispatchDecision.EXECUTE,
                ServiceCapabilityRuntimePolicy.directDispatchDecision(readEt));

        assertTrue(ServiceCapabilityRuntimePolicy.isModelAdvertisable(mutEt));
        assertFalse(ServiceCapabilityRuntimePolicy.isPlaybookEligible(mutEt));
        assertEquals(
                ServiceCapabilityRuntimePolicy.DirectDispatchDecision.REQUIRE_HITL,
                ServiceCapabilityRuntimePolicy.directDispatchDecision(mutEt));
        assertTrue(U7DemoCapabilityProfiles.advertisedOperations().isEmpty());
    }

    @Test
    void mutatingDryRunInjectsAndEgressStampsClassification() throws Exception {
        ObjectNode root = (ObjectNode) JSON.readTree(U7DemoCapabilityProfiles.extendedToolsFixtureJson());
        ServiceCapabilityMetadata mut =
                parseTool(root, U7DemoCapabilityProfiles.MUTATING_TOOL, stubParams(Set.of("dryRun", "requestId")));

        ServiceCapabilityDryRunEnforce.Result dry =
                ServiceCapabilityDryRunEnforce.apply("{\"requestId\":\"wo-1\"}", mut);
        assertFalse(dry.blocked());
        assertTrue(JSON.readTree(dry.argumentsJson()).path("dryRun").asBoolean());

        String raw = "{\"status\":\"success\",\"workOrderId\":\"WO-DEMO\",\"note\":\"preview only\"}";
        ToolResultEgressGateway.EgressResult egress = ToolResultEgressGateway.compactForLlmAppend(
                U7DemoCapabilityProfiles.MUTATING_TOOL, "c1", raw, null, mut.dataClassification());
        assertFalse(egress.isCompacted());
        JsonNode stamped = JSON.readTree(egress.getLlmContent());
        assertEquals("INTERNAL", stamped.path("_egress").path("dataClassification").get(0).asText());
    }

    @Test
    void providerMatrixRateLimitFallbackThenDegraded() {
        ProviderRouteProfilesParser.ParseResult parsed =
                ProviderRouteProfilesParser.parse(U7DemoCapabilityProfiles.routeProfilesFixtureJson());
        assertTrue(parsed.diagnostics().isEmpty(), () -> parsed.diagnostics().toString());
        ProviderRouteProfile profile = parsed.find("u7-demo-primary-fallback").orElseThrow();

        ProviderRoundRequirements req = new ProviderRoundRequirements(
                Set.of(ProviderCapabilityToken.TOOLS, ProviderCapabilityToken.DATA_EGRESS_REGION),
                "INTERNAL",
                "US",
                false,
                4_000L,
                false);
        Map<String, ProviderEligibilityView> directory = Map.of(
                "U7DemoPrimaryProvider", full("U7DemoPrimaryProvider"),
                "U7DemoFallbackProvider", full("U7DemoFallbackProvider"));

        ProviderRetryBudget budget = new ProviderRetryBudget(1, 4, 60_000L, 120_000L, CLOCK);
        budget.recordAttemptStarted();
        LlmProviderFailureSignal rateLimited = LlmProviderFailureSignal.forHttpStatus(429, false);
        ProviderSameProviderRetryPlan same =
                ProviderRouteCoordinator.planSameProviderRetry(rateLimited, budget, false, () -> 0.5d);
        assertTrue(
                same.action() == ProviderSameProviderRetryPlan.Action.RETRY_SAME
                        || same.action() == ProviderSameProviderRetryPlan.Action.FALLBACK_CANDIDATE,
                same::toString);

        LlmProviderFailureClass cls = ProviderRouteCoordinator.classify(
                LlmProviderFailureSignal.forHttpStatus(503, false));
        assertEquals(LlmProviderFailureClass.TRANSIENT_UPSTREAM, cls);

        ProviderCircuitBreaker circuit = new ProviderCircuitBreaker(CLOCK);
        ProviderFallbackPlan plan = ProviderRouteCoordinator.planFallback(
                profile,
                req,
                directory,
                circuit,
                budget,
                List.of("U7DemoPrimaryProvider"),
                cls,
                true);
        assertEquals(ProviderFallbackPlan.Action.TRY_PROVIDER, plan.action());
        assertEquals("U7DemoFallbackProvider", plan.nextProviderThingName().orElse(""));

        ProviderFallbackPlan exhausted = ProviderRouteCoordinator.planFallback(
                profile,
                req,
                directory,
                circuit,
                budget,
                List.of("U7DemoPrimaryProvider", "U7DemoFallbackProvider"),
                LlmProviderFailureClass.TRANSIENT_UPSTREAM,
                true);
        assertEquals(ProviderFallbackPlan.Action.DEGRADED, exhausted.action());
        assertTrue(exhausted.degraded().isPresent());
        ProviderDegradedOutcome degraded = exhausted.degraded().orElseThrow();
        assertEquals(ProviderDegradedOutcome.CODE, degraded.code());
        assertTrue(degraded.evidenceStillUsable());
        assertFalse(degraded.summary().isBlank());
        assertEquals(2, degraded.triedProviders().size());
    }

    private static ServiceCapabilityMetadata parseTool(
            ObjectNode root, String name, ServiceParameterLookup params) throws Exception {
        for (JsonNode t : root.get("tools")) {
            if (name.equals(t.path("name").asText())) {
                ServiceCapabilityDescriptorParser.ParseResult r =
                        ServiceCapabilityDescriptorParser.parse((ObjectNode) t, params);
                assertFalse(r.shouldSkip(), r.skipReason());
                return r.metadata().orElseThrow();
            }
        }
        throw new AssertionError("tool missing: " + name);
    }

    private static ServiceParameterLookup stubParams(Set<String> names) {
        return new ServiceParameterLookup() {
            @Override
            public boolean hasParameter(String parameterName) {
                return names.contains(parameterName);
            }

            @Override
            public String inputShapeDigest() {
                return "";
            }
        };
    }

    private static ExtendedToolDefinition tool(
            String name, boolean hitlBypass, boolean playbookSafe, ServiceCapabilityMetadata cap) {
        return new ExtendedToolDefinition(
                name,
                "t",
                "w",
                "Thing1",
                "Svc1",
                hitlBypass,
                false,
                new ToolDefinition(name, "d", Map.of(), playbookSafe),
                cap);
    }

    private static ProviderEligibilityView full(String name) {
        return new ProviderEligibilityView(
                name,
                true,
                Set.of(
                        ProviderCapabilityToken.TOOLS,
                        ProviderCapabilityToken.HITL_CONTINUATION,
                        ProviderCapabilityToken.CONTEXT_WINDOW,
                        ProviderCapabilityToken.DATA_EGRESS_REGION,
                        ProviderCapabilityToken.STRICT_JSON_SCHEMA,
                        ProviderCapabilityToken.STRUCTURED_OUTPUT),
                Set.of("INTERNAL", "PUBLIC"),
                Set.of("US"),
                16_000,
                "APPROVED_INTERACTIVE");
    }
}
