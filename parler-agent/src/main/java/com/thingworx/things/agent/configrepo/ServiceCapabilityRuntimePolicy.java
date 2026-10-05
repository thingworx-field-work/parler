package com.thingworx.things.agent.configrepo;

import java.util.List;
import java.util.Optional;

/**
 * SPR-2 runtime gates for G13 capability metadata on the existing extended-tool registry.
 * Does not grant RBAC; ThingWorx {@code SecurityContext} remains authoritative at invocation.
 *
 * <p>Legacy entries without {@link ExtendedToolDefinition#capability()} keep pre-U7 advertise /
 * Playbook behavior ({@code executorOnly} + {@code ToolDefinition.isPlaybookSafe()}).
 * DESTRUCTIVE/ADMIN stay blocked until a reviewed G20 admit path exists (none today).
 */
public final class ServiceCapabilityRuntimePolicy {

    public static final String BLOCK_CODE = "CAPABILITY_POLICY_BLOCKED";

    /**
     * Direct extended-tool dispatch decision. Callers MUST evaluate this (or
     * {@link #executionBlockReason}) before any HITL enqueue so DISABLED / DESTRUCTIVE /
     * ADMIN never become approval-pending actions.
     */
    public enum DirectDispatchDecision {
        /** Refuse immediately with {@link #BLOCK_CODE}; do not enqueue HITL. */
        BLOCKED,
        /** Enqueue Parler HITL, then execute only after approval. */
        REQUIRE_HITL,
        /** Execute without HITL enqueue. */
        EXECUTE
    }

    private ServiceCapabilityRuntimePolicy() {}

    /**
     * Ordered gate for {@code AgentThing} direct extended-tool dispatch: block before HITL.
     * Playbook paths use {@link #isPlaybookEligible} / {@link #executionBlockReason} instead.
     */
    public static DirectDispatchDecision directDispatchDecision(ExtendedToolDefinition et) {
        if (executionBlockReason(et).isPresent()) {
            return DirectDispatchDecision.BLOCKED;
        }
        if (requiresHitl(et)) {
            return DirectDispatchDecision.REQUIRE_HITL;
        }
        return DirectDispatchDecision.EXECUTE;
    }

    /** Whether the tool may appear on the merged model-facing LLM tool list. */
    public static boolean isModelAdvertisable(ExtendedToolDefinition et) {
        if (et == null || et.executorOnly()) {
            return false;
        }
        Optional<ServiceCapabilityMetadata> cap = et.capability();
        if (cap.isEmpty()) {
            return true;
        }
        ServiceCapabilityMetadata m = cap.get();
        if (!m.enabled() || m.runtimeState() == ServiceCapabilityRuntimeState.DISABLED) {
            return false;
        }
        if (m.admission().orElse(ServiceCapabilityAdmission.DEFAULT) == ServiceCapabilityAdmission.OFF) {
            return false;
        }
        return !isHighRiskBlocked(m.risk().orElse(null));
    }

    /**
     * Whether the tool may be bound/executed by a static Playbook. Requires the harvested
     * playbookSafe flag and capability-level enablement/risk gates. Capability-bearing
     * {@code MUTATING} tools are ineligible in U7 v1 (Playbook HITL pause/resume is U8).
     */
    public static boolean isPlaybookEligible(ExtendedToolDefinition et) {
        if (et == null || et.toolDefinition() == null || !et.toolDefinition().isPlaybookSafe()) {
            return false;
        }
        Optional<ServiceCapabilityMetadata> cap = et.capability();
        if (cap.isEmpty()) {
            return true;
        }
        ServiceCapabilityMetadata m = cap.get();
        if (!m.enabled() || m.runtimeState() == ServiceCapabilityRuntimeState.DISABLED) {
            return false;
        }
        ServiceCapabilityRisk risk = m.risk().orElse(null);
        if (risk == ServiceCapabilityRisk.MUTATING || isHighRiskBlocked(risk)) {
            return false;
        }
        return true;
    }

    /**
     * Whether the call must pass through the existing Parler HITL enqueue path.
     * Legacy entries keep {@link ExtendedToolDefinition#hitlBypass()}. Capability-bearing
     * {@code MUTATING} tools always require HITL even if the author set {@code hitl:false}.
     */
    public static boolean requiresHitl(ExtendedToolDefinition et) {
        if (et == null) {
            return false;
        }
        Optional<ServiceCapabilityMetadata> cap = et.capability();
        if (cap.isPresent()) {
            ServiceCapabilityRisk risk = cap.get().risk().orElse(null);
            if (risk == ServiceCapabilityRisk.MUTATING) {
                return true;
            }
            if (isHighRiskBlocked(risk)) {
                // High-risk tools are execution-blocked; if reached, still never bypass HITL.
                return true;
            }
        }
        return !et.hitlBypass();
    }

    /** Non-empty when invocation must be refused before the ThingWorx Service call. */
    public static Optional<String> executionBlockReason(ExtendedToolDefinition et) {
        if (et == null) {
            return Optional.empty();
        }
        Optional<ServiceCapabilityMetadata> cap = et.capability();
        if (cap.isEmpty()) {
            return Optional.empty();
        }
        ServiceCapabilityMetadata m = cap.get();
        if (!m.enabled() || m.runtimeState() == ServiceCapabilityRuntimeState.DISABLED) {
            return Optional.of("capability disabled");
        }
        if (isHighRiskBlocked(m.risk().orElse(null))) {
            return Optional.of("DESTRUCTIVE/ADMIN capability blocked (no G20 admit path)");
        }
        return Optional.empty();
    }

    /**
     * HITL-approved extended-tool execution must re-resolve the tool from the active registry so
     * dry-run enforce and {@link #executionBlockReason} can be reapplied.
     * Registry miss fails closed — raw target/service execute would skip those gates.
     *
     * @return empty when {@code toolName} resolves; otherwise a {@link #BLOCK_CODE} reason
     */
    public static Optional<String> hitlApproveRegistryMissReason(
            ExtendedToolRegistrySnapshot registry, String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.of("extended tool name missing on HITL approval");
        }
        if (registry == null || registry.find(toolName).isEmpty()) {
            return Optional.of(
                    "extended tool not in active registry; cannot reapply capability policy");
        }
        return Optional.empty();
    }

    public static boolean isHighRiskBlocked(ServiceCapabilityRisk risk) {
        return risk == ServiceCapabilityRisk.DESTRUCTIVE || risk == ServiceCapabilityRisk.ADMIN;
    }

    public static List<String> declaredBusinessErrors(ExtendedToolDefinition et) {
        if (et == null) {
            return List.of();
        }
        return et.capability().map(ServiceCapabilityMetadata::businessErrors).orElse(List.of());
    }

    public static Optional<ServiceCapabilityRisk> risk(ExtendedToolDefinition et) {
        if (et == null) {
            return Optional.empty();
        }
        return et.capability().flatMap(ServiceCapabilityMetadata::risk);
    }
}
