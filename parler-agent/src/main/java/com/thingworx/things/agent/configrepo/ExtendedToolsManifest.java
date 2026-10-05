package com.thingworx.things.agent.configrepo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.entities.RootEntity;
import com.thingworx.entities.interfaces.IServiceProvider;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.Thing;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.tools.CustomToolHarvester;
import com.thingworx.things.agent.tools.ProtectedValuePolicy;
import com.thingworx.things.agent.skillregistry.RepositoryReader;

/**
 * Loads {@code /tools/extended_tools.json} into LLM tool definitions (configuration repository topic).
 */
public final class ExtendedToolsManifest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern LLM_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    private ExtendedToolsManifest() {}

    private static ExtendedToolRegistrySnapshot invalid(Logger log, String agentThingName, String reason) {
        if (log != null) {
            log.error("[{}] configurationRepository: extended_tools.json invalid — {}", agentThingName, reason);
        }
        return ExtendedToolRegistrySnapshot.invalid();
    }

    /** S9: log and skip one bad entry without invalidating the whole file. */
    private static void skipEntry(Logger log, String agentThingName, String toolName, String reason) {
        if (log != null) {
            log.warn("[{}] configurationRepository: extended tool {} skipped — {}", agentThingName, toolName, reason);
        }
    }

    public static ExtendedToolRegistrySnapshot load(
            RepositoryReader reader,
            String agentThingName,
            IServiceProvider agentAsProvider,
            Set<String> reservedBuiltInToolNames,
            Logger log) {
        RepositoryTextLoads.Result tr =
                RepositoryTextLoads.loadText(reader, ConfigurationRepositoryPaths.EXTENDED_TOOLS);
        if (tr.kind() == RepositoryTextLoads.Kind.MISSING || tr.kind() == RepositoryTextLoads.Kind.EMPTY) {
            return ExtendedToolRegistrySnapshot.missing();
        }
        if (tr.kind() == RepositoryTextLoads.Kind.READ_ERROR) {
            if (log != null) {
                log.error("[{}] configurationRepository: extended_tools.json read failed: {}", agentThingName,
                        tr.errorMessage());
            }
            return ExtendedToolRegistrySnapshot.invalid();
        }
        String raw = tr.text();
        try {
            JsonNode root = MAPPER.readTree(raw);
            if (root == null || !root.isObject()) {
                return invalid(log, agentThingName, "root is not a JSON object");
            }
            if (root.path("version").asInt(-1) != 1) {
                return invalid(log, agentThingName, "unsupported or missing version (expected 1)");
            }
            JsonNode tools = root.get("tools");
            if (tools == null || !tools.isArray()) {
                return invalid(log, agentThingName, "tools must be a JSON array");
            }
            List<ExtendedToolDefinition> list = new ArrayList<>();
            Set<String> seenNames = new HashSet<>();
            Set<String> builtIn = reservedBuiltInToolNames != null ? reservedBuiltInToolNames : Set.of();
            // S9: structural file defects invalidate the whole manifest; per-entry defects skip that
            // entry only so one bad tool does not wipe sibling entries.
            for (JsonNode tn : tools) {
                if (tn == null || !tn.isObject()) {
                    skipEntry(log, agentThingName, "(unnamed)", "entry is not a JSON object");
                    continue;
                }
                String name = textRequired(tn, "name");
                if (name == null || !LLM_NAME.matcher(name).matches()) {
                    skipEntry(log, agentThingName, name != null ? name : "(unnamed)",
                            "invalid or missing tool name");
                    continue;
                }
                if (seenNames.contains(name) || builtIn.contains(name)) {
                    skipEntry(log, agentThingName, name, "duplicate tool name or conflict with built-in");
                    continue;
                }
                seenNames.add(name);
                String whenToUse = textRequired(tn, "whenToUse");
                if (whenToUse == null) {
                    skipEntry(log, agentThingName, name, "missing whenToUse");
                    continue;
                }
                whenToUse = whenToUse.trim();
                if (whenToUse.isEmpty()) {
                    skipEntry(log, agentThingName, name, "empty whenToUse");
                    continue;
                }
                String title = tn.has("title") && tn.get("title").isTextual() ? tn.get("title").asText().trim() : "";
                JsonNode target = tn.get("target");
                if (target == null || !target.isObject()) {
                    skipEntry(log, agentThingName, name, "missing target object");
                    continue;
                }
                String entityNameCfg = textRequired(target, "entityName");
                String serviceName = textRequired(target, "serviceName");
                if (entityNameCfg == null || serviceName == null) {
                    skipEntry(log, agentThingName, name, "missing target.entityName or serviceName");
                    continue;
                }
                boolean hitlBypass = false;
                if (tn.has("hitl")) {
                    if (!tn.get("hitl").isBoolean()) {
                        skipEntry(log, agentThingName, name, "hitl must be boolean when present");
                        continue;
                    }
                    hitlBypass = !tn.get("hitl").asBoolean();
                }
                boolean playbookSafeRequested = false;
                if (tn.has("playbookSafe")) {
                    if (!tn.get("playbookSafe").isBoolean()) {
                        skipEntry(log, agentThingName, name, "playbookSafe must be boolean when present");
                        continue;
                    }
                    playbookSafeRequested = tn.get("playbookSafe").asBoolean();
                }
                if (playbookSafeRequested && !hitlBypass && log != null) {
                    if (!tn.has("hitl")) {
                        log.warn("[{}] configurationRepository: extended tool {} has playbookSafe=true but hitl is "
                                + "omitted (defaults to human-in-the-loop); tool will not be playbook-eligible",
                                agentThingName, name);
                    } else if (tn.get("hitl").asBoolean()) {
                        log.warn("[{}] configurationRepository: extended tool {} has playbookSafe=true with hitl=true; "
                                + "tool will not be playbook-eligible",
                                agentThingName, name);
                    }
                }
                boolean executorOnly = false;
                if (tn.has("executorOnly")) {
                    if (!tn.get("executorOnly").isBoolean()) {
                        skipEntry(log, agentThingName, name, "executorOnly must be boolean when present");
                        continue;
                    }
                    executorOnly = tn.get("executorOnly").asBoolean();
                }
                if (log != null && hitlBypass && !playbookSafeRequested && !tn.has("playbookSafe")) {
                    log.info("[{}] configurationRepository: extended tool {} declares hitl=false but playbookSafe is "
                            + "omitted; tool will not be playbook-eligible unless playbookSafe=true is set",
                            agentThingName, name);
                }
                boolean effectivePlaybookSafe = playbookSafeRequested && hitlBypass;
                // SPR-1: JSON-only capability validation before Thing resolve (fail closed on bad
                // shapes / forbidden U8/U11 fields without requiring a live target).
                ServiceCapabilityDescriptorParser.ParseResult earlyCapability =
                        ServiceCapabilityDescriptorParser.parse(tn, null);
                if (earlyCapability.shouldSkip()) {
                    skipEntry(log, agentThingName, name, earlyCapability.skipReason());
                    continue;
                }
                String resolvedEntity = resolveEntityName(agentThingName, entityNameCfg);
                if (resolvedEntity == null) {
                    skipEntry(log, agentThingName, name, "target Thing not found: " + entityNameCfg);
                    continue;
                }
                RootEntity ent = PlatformAccess.findProgrammatic(resolvedEntity,
                        RelationshipTypes.ThingworxRelationshipTypes.Thing);
                if (!(ent instanceof Thing)) {
                    skipEntry(log, agentThingName, name, "not a Thing: " + resolvedEntity);
                    continue;
                }
                Thing targetThing = (Thing) ent;
                ServiceDefinition sd = CustomToolHarvester.findServiceDefinition(targetThing, serviceName);
                if (sd == null) {
                    skipEntry(log, agentThingName, name,
                            "service " + serviceName + " not on " + resolvedEntity);
                    continue;
                }
                if (ProtectedValuePolicy.shouldOmitExtendedToolFromDiscovery(sd)) {
                    skipEntry(log, agentThingName, name, "PASSWORD protection");
                    continue;
                }
                ServiceParameterLookup params = new ServiceDefinitionParameterLookup(sd);
                ServiceCapabilityDescriptorParser.ParseResult capabilityParse =
                        ServiceCapabilityDescriptorParser.parse(tn, params);
                if (capabilityParse.shouldSkip()) {
                    skipEntry(log, agentThingName, name, capabilityParse.skipReason());
                    continue;
                }
                ToolDefinition td;
                try {
                    td = CustomToolHarvester.toToolDefinitionForExtendedTool(sd, name, whenToUse, title,
                            effectivePlaybookSafe);
                } catch (Exception e) {
                    skipEntry(log, agentThingName, name, "schema skip: " + e.getMessage());
                    continue;
                }
                list.add(new ExtendedToolDefinition(name, title, whenToUse, resolvedEntity, serviceName, hitlBypass,
                        executorOnly, td, capabilityParse.metadata().orElse(null)));
            }
            return ExtendedToolRegistrySnapshot.ok(list);
        } catch (Exception e) {
            if (log != null) {
                log.error("[{}] configurationRepository: extended_tools.json invalid: {}", agentThingName,
                        e.getMessage(), e);
            }
            return ExtendedToolRegistrySnapshot.invalid();
        }
    }

    private static String textRequired(JsonNode o, String field) {
        if (!o.has(field) || !o.get(field).isTextual()) {
            return null;
        }
        String s = o.get(field).asText();
        if (s == null) {
            return null;
        }
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static String resolveEntityName(String agentThingName, String configured) {
        if (configured == null) {
            return null;
        }
        if ("me".equals(configured)) {
            return agentThingName;
        }
        return configured.trim().isEmpty() ? null : configured.trim();
    }
}
