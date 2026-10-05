package com.thingworx.things.agent.configrepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.playbook.PlaybookToolDefinitionsMerge;
import com.thingworx.things.agent.tools.ToolRegistry;

/**
 * SPR-2: capability runtime gates on the existing extended-tool registry (no second registry).
 */
class ServiceCapabilityRuntimePolicyTest {

    @Test
    void legacyWithoutCapabilityKeepsAdvertiseAndPlaybookFlags() {
        // hitlBypass=true (author hitl:false) + playbookSafe harvest
        ExtendedToolDefinition legacy = tool("legacy", true, true, null);
        assertTrue(ServiceCapabilityRuntimePolicy.isModelAdvertisable(legacy));
        assertTrue(ServiceCapabilityRuntimePolicy.isPlaybookEligible(legacy));
        assertFalse(ServiceCapabilityRuntimePolicy.requiresHitl(legacy));
        assertTrue(ServiceCapabilityRuntimePolicy.executionBlockReason(legacy).isEmpty());
    }

    @Test
    void legacyHitlTrueStillRequiresHitl() {
        // hitlBypass=false (author hitl:true) ⇒ requires HITL; not playbook-safe
        ExtendedToolDefinition legacy = tool("legacyHitl", false, false, null);
        assertTrue(ServiceCapabilityRuntimePolicy.requiresHitl(legacy));
        assertFalse(ServiceCapabilityRuntimePolicy.isPlaybookEligible(legacy));
    }

    @Test
    void executorOnlyNeverModelAdvertisable() {
        ExtendedToolDefinition et = new ExtendedToolDefinition(
                "hidden", "t", "w", "Thing1", "S1", true, true,
                new ToolDefinition("hidden", "d", Map.of(), true),
                active(ServiceCapabilityRisk.READ_ONLY));
        assertFalse(ServiceCapabilityRuntimePolicy.isModelAdvertisable(et));
    }

    @Test
    void readOnlyActiveIsAdvertisableAndPlaybookEligible() {
        ExtendedToolDefinition et = tool("read", true, true, active(ServiceCapabilityRisk.READ_ONLY));
        assertTrue(ServiceCapabilityRuntimePolicy.isModelAdvertisable(et));
        assertTrue(ServiceCapabilityRuntimePolicy.isPlaybookEligible(et));
        assertFalse(ServiceCapabilityRuntimePolicy.requiresHitl(et));
        assertTrue(ServiceCapabilityRuntimePolicy.executionBlockReason(et).isEmpty());
    }

    @Test
    void mutatingForcesHitlAndBlocksPlaybookEvenWhenAuthorSetHitlFalse() {
        ExtendedToolDefinition et = tool("mut", true, true, active(ServiceCapabilityRisk.MUTATING));
        assertTrue(ServiceCapabilityRuntimePolicy.isModelAdvertisable(et));
        assertTrue(ServiceCapabilityRuntimePolicy.requiresHitl(et));
        assertFalse(ServiceCapabilityRuntimePolicy.isPlaybookEligible(et));
        assertTrue(ServiceCapabilityRuntimePolicy.executionBlockReason(et).isEmpty());
    }

    @Test
    void destructiveAndAdminBlockedFromAdvertisePlaybookAndExecution() {
        for (ServiceCapabilityRisk risk : List.of(
                ServiceCapabilityRisk.DESTRUCTIVE, ServiceCapabilityRisk.ADMIN)) {
            ExtendedToolDefinition et = tool(risk.name(), true, true, active(risk));
            assertFalse(ServiceCapabilityRuntimePolicy.isModelAdvertisable(et));
            assertFalse(ServiceCapabilityRuntimePolicy.isPlaybookEligible(et));
            assertTrue(ServiceCapabilityRuntimePolicy.requiresHitl(et));
            assertEquals(
                    "DESTRUCTIVE/ADMIN capability blocked (no G20 admit path)",
                    ServiceCapabilityRuntimePolicy.executionBlockReason(et).orElse(""));
        }
    }

    @Test
    void disabledOrAdmissionOffNotAdvertisable() {
        ExtendedToolDefinition disabled = tool(
                "off",
                true,
                true,
                meta(ServiceCapabilityRisk.READ_ONLY, true, ServiceCapabilityAdmission.DEFAULT,
                        ServiceCapabilityRuntimeState.DISABLED));
        assertFalse(ServiceCapabilityRuntimePolicy.isModelAdvertisable(disabled));
        assertFalse(ServiceCapabilityRuntimePolicy.isPlaybookEligible(disabled));
        assertEquals("capability disabled",
                ServiceCapabilityRuntimePolicy.executionBlockReason(disabled).orElse(""));

        ExtendedToolDefinition admissionOff = tool(
                "admOff",
                true,
                true,
                meta(ServiceCapabilityRisk.READ_ONLY, true, ServiceCapabilityAdmission.OFF,
                        ServiceCapabilityRuntimeState.ACTIVE));
        assertFalse(ServiceCapabilityRuntimePolicy.isModelAdvertisable(admissionOff));
        // admission OFF is model-facing only; Playbook still allowed when otherwise eligible
        assertTrue(ServiceCapabilityRuntimePolicy.isPlaybookEligible(admissionOff));
        assertTrue(ServiceCapabilityRuntimePolicy.executionBlockReason(admissionOff).isEmpty());
    }

    @Test
    void playbookMergeForcesPlaybookSafeFalseWhenIneligible() {
        ExtendedToolDefinition destructive = tool(
                "boom", true, true, active(ServiceCapabilityRisk.DESTRUCTIVE));
        ExtendedToolRegistrySnapshot ext = ExtendedToolRegistrySnapshot.ok(List.of(destructive));
        List<ToolDefinition> merged = PlaybookToolDefinitionsMerge.merge(new ToolRegistry(), ext);
        ToolDefinition found = merged.stream()
                .filter(td -> "boom".equals(td.getName()))
                .findFirst()
                .orElseThrow();
        assertFalse(found.isPlaybookSafe());
    }

    /**
     * Direct dispatch must refuse DISABLED / DESTRUCTIVE / ADMIN before HITL
     * enqueue. {@link ServiceCapabilityRuntimePolicy#directDispatchDecision} is the ordered gate
     * {@code AgentThing} consumes; BLOCKED must win even when {@link #requiresHitl} is also true.
     */
    @Test
    void directDispatchBlocksBeforeHitlForDestructiveAdminAndDisabled() {
        for (ServiceCapabilityRisk risk : List.of(
                ServiceCapabilityRisk.DESTRUCTIVE, ServiceCapabilityRisk.ADMIN)) {
            ExtendedToolDefinition et = tool(risk.name(), true, true, active(risk));
            assertTrue(ServiceCapabilityRuntimePolicy.requiresHitl(et),
                    "high-risk still never HITL-bypasses if reached");
            assertEquals(
                    ServiceCapabilityRuntimePolicy.DirectDispatchDecision.BLOCKED,
                    ServiceCapabilityRuntimePolicy.directDispatchDecision(et));
        }

        // Disabled + author hitl:true would previously enqueue HITL before the block.
        ExtendedToolDefinition disabledNeedsHitl = tool(
                "offHitl",
                false,
                false,
                meta(ServiceCapabilityRisk.READ_ONLY, true, ServiceCapabilityAdmission.DEFAULT,
                        ServiceCapabilityRuntimeState.DISABLED));
        assertTrue(ServiceCapabilityRuntimePolicy.requiresHitl(disabledNeedsHitl));
        assertEquals(
                ServiceCapabilityRuntimePolicy.DirectDispatchDecision.BLOCKED,
                ServiceCapabilityRuntimePolicy.directDispatchDecision(disabledNeedsHitl));

        ExtendedToolDefinition mutating = tool("mut", true, true, active(ServiceCapabilityRisk.MUTATING));
        assertEquals(
                ServiceCapabilityRuntimePolicy.DirectDispatchDecision.REQUIRE_HITL,
                ServiceCapabilityRuntimePolicy.directDispatchDecision(mutating));

        ExtendedToolDefinition read = tool("read", true, true, active(ServiceCapabilityRisk.READ_ONLY));
        assertEquals(
                ServiceCapabilityRuntimePolicy.DirectDispatchDecision.EXECUTE,
                ServiceCapabilityRuntimePolicy.directDispatchDecision(read));
    }

    /**
     * HITL approve must fail closed when the active registry no longer
     * resolves the gated extended tool — raw target/service execute would skip dry-run enforce
     * and {@link ServiceCapabilityRuntimePolicy#executionBlockReason}.
     */
    @Test
    void hitlApproveFailsClosedOnRegistryMiss() {
        ServiceCapabilityMetadata mutDry = new ServiceCapabilityMetadata(
                "Create WO",
                "1",
                ServiceCapabilityRisk.MUTATING,
                true,
                "dryRun",
                ServiceIdempotencyMode.CALLER_KEY,
                "requestId",
                List.of("INTERNAL"),
                ServiceCapabilityAdmission.LAZY,
                true,
                "",
                "",
                "",
                "",
                List.of(),
                ServiceCapabilityRuntimeState.ACTIVE);
        ExtendedToolDefinition queued = tool("u7_demo_create_maintenance_work_order", false, false, mutDry);
        assertEquals(
                ServiceCapabilityRuntimePolicy.DirectDispatchDecision.REQUIRE_HITL,
                ServiceCapabilityRuntimePolicy.directDispatchDecision(queued));

        // Omitted dryRun would inject true when executeExtendedTool runs with a resolved def.
        ServiceCapabilityDryRunEnforce.Result dry =
                ServiceCapabilityDryRunEnforce.apply("{\"requestId\":\"wo-1\"}", mutDry);
        assertFalse(dry.blocked());
        assertTrue(dry.argumentsJson().contains("\"dryRun\":true")
                || dry.argumentsJson().contains("\"dryRun\": true"));

        // After registry refresh/removal, approve must not fall through to raw execute.
        assertTrue(ServiceCapabilityRuntimePolicy.hitlApproveRegistryMissReason(
                ExtendedToolRegistrySnapshot.missing(), queued.llmName()).isPresent());
        assertTrue(ServiceCapabilityRuntimePolicy.hitlApproveRegistryMissReason(
                ExtendedToolRegistrySnapshot.ok(List.of()), queued.llmName()).isPresent());
        assertTrue(ServiceCapabilityRuntimePolicy.hitlApproveRegistryMissReason(
                ExtendedToolRegistrySnapshot.ok(List.of(queued)), queued.llmName()).isEmpty());

        // Since-disabled at approval: registry hit still blocks via executionBlockReason.
        ExtendedToolDefinition disabled = tool(
                queued.llmName(),
                false,
                false,
                mutDry.withRuntimeState(ServiceCapabilityRuntimeState.DISABLED));
        assertEquals(
                "capability disabled",
                ServiceCapabilityRuntimePolicy.executionBlockReason(disabled).orElse(""));
        assertEquals(
                ServiceCapabilityRuntimePolicy.DirectDispatchDecision.BLOCKED,
                ServiceCapabilityRuntimePolicy.directDispatchDecision(disabled));
    }

    @Test
    void noSecondRegistryType() {
        org.junit.jupiter.api.Assertions.assertThrows(ClassNotFoundException.class, () -> Class.forName(
                "com.thingworx.things.agent.configrepo.ServiceCapabilityRegistry"));
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

    private static ServiceCapabilityMetadata active(ServiceCapabilityRisk risk) {
        return meta(risk, true, ServiceCapabilityAdmission.DEFAULT, ServiceCapabilityRuntimeState.ACTIVE);
    }

    private static ServiceCapabilityMetadata meta(
            ServiceCapabilityRisk risk,
            boolean enabled,
            ServiceCapabilityAdmission admission,
            ServiceCapabilityRuntimeState state) {
        return new ServiceCapabilityMetadata(
                "purpose",
                "1",
                risk,
                false,
                "",
                ServiceIdempotencyMode.NONE,
                "",
                List.of(),
                admission,
                enabled,
                "",
                "",
                "",
                "",
                List.of(),
                state);
    }
}
