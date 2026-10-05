package com.thingworx.things.agent.playbook;

import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.PromptContextCacheSnapshot;
import com.thingworx.things.agent.configrepo.ExtendedToolDefinition;
import com.thingworx.things.agent.configrepo.ExtendedToolRegistrySnapshot;
import com.thingworx.things.agent.configrepo.ParlerPackageVersion;
import com.thingworx.things.agent.configrepo.ServiceCapabilityRuntimePolicy;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.tools.ToolRegistry;

/** Builds {@code playbookRuntime} for {@code GetAgentRuntimeSnapshot}. */
public final class PlaybookRuntimeSnapshotBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PlaybookRuntimeSnapshotBuilder() {}

    public static ObjectNode build(AgentThing agent, PromptContextCacheSnapshot snap) {
        String extVer = ParlerPackageVersion.fromAnchorClass(AgentThing.class);
        ToolRegistry registry = agent != null ? agent.toolRegistry() : null;
        ExtendedToolRegistrySnapshot ext =
                snap != null ? snap.getExtendedToolRegistry() : ExtendedToolRegistrySnapshot.missing();
        return build(registry, ext, extVer);
    }

    public static ObjectNode build(ToolRegistry registry, ExtendedToolRegistrySnapshot ext, String agentVersion) {
        ObjectNode rt = MAPPER.createObjectNode();
        if (agentVersion == null || agentVersion.isBlank()) {
            rt.putNull("agentVersion");
        } else {
            rt.put("agentVersion", agentVersion);
        }
        rt.put("schemaVersion", PlaybookIds.SCHEMA_V1);
        ArrayNode kinds = rt.putArray("nodeKinds");
        PlaybookValidator.supportedNodeKinds().stream().sorted().forEach(kinds::add);
        ArrayNode deriveOps = rt.putArray("deriveOps");
        PlaybookValidator.supportedDeriveOps().stream().sorted().forEach(deriveOps::add);

        Set<String> builtInNames = new java.util.HashSet<>();
        ArrayNode builtIn = rt.putArray("builtInTools");
        if (registry != null) {
            for (ToolDefinition td : registry.getAllDefinitions()) {
                if (td.isPlaybookSafe() && builtInNames.add(td.getName())) {
                    ObjectNode row = builtIn.addObject();
                    row.put("name", td.getName());
                    row.put("playbookSafe", true);
                }
            }
        }
        ArrayNode extended = rt.putArray("extendedTools");
        if (ext != null && !ext.isFileInvalid()) {
            for (ExtendedToolDefinition et : ext.allByName().values()) {
                if (!ServiceCapabilityRuntimePolicy.isPlaybookEligible(et)) {
                    continue;
                }
                ObjectNode row = extended.addObject();
                row.put("name", et.llmName());
                row.put("playbookSafe", true);
                row.put("hitl", ServiceCapabilityRuntimePolicy.requiresHitl(et));
                row.put("executorOnly", et.executorOnly());
                et.capability().ifPresent(cap -> {
                    cap.risk().ifPresent(r -> row.put("risk", r.name()));
                    row.put("dryRunSupported", cap.dryRunSupported());
                    cap.idempotencyMode().ifPresent(m -> row.put("idempotencyMode", m.name()));
                });
            }
        }

        PlaybookBindingCapabilities bindings = PlaybookInfotableBindingPolicy.bindingCapabilities();
        ObjectNode bind = rt.putObject("bindings");
        bind.put("table", bindings.table());
        bind.put("infotable", bindings.infotable());
        bind.put("infotableForInvokeService", bindings.infotableForInvokeService());

        ObjectNode limits = rt.putObject("limits");
        limits.put("maxFanOutConcurrency", PlaybookGenericOpsConstants.MAX_FAN_OUT_CONCURRENCY);
        limits.put("maxGenericInputRows", PlaybookGenericOpsConstants.MAX_GENERIC_INPUT_ROWS);
        limits.put("maxGenericOutputRows", PlaybookGenericOpsConstants.MAX_GENERIC_OUTPUT_ROWS);
        limits.put("maxGenericTargets", PlaybookGenericOpsConstants.MAX_GENERIC_TARGETS);
        limits.put("maxCollectGapsItems", PlaybookGenericOpsConstants.MAX_COLLECT_GAPS_ITEMS);
        limits.put("maxPackagedPlaybooks", PlaybookIds.MAX_PACKAGED_PLAYBOOKS);

        PlaybookRuntimeEvidenceCapabilities ev = PlaybookRuntimeEvidenceCapabilities.current();
        ObjectNode evidence = rt.putObject("evidence");
        evidence.put("nodeEvidenceLines", ev.nodeEvidenceLines());
        evidence.put("toolTableProjection", ev.toolTableProjection());
        evidence.put("rootScalarProjection", ev.rootScalarProjection());
        evidence.put("artifactForwarding", ev.artifactForwarding());
        return rt;
    }
}
