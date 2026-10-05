package com.thingworx.things.agent.compaction;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.llm.ToolSchemaSizer;
import com.thingworx.things.agent.tools.BuiltInTools;
import com.thingworx.things.agent.tools.LoadToolSchemasExecutor;
import com.thingworx.things.agent.tools.ToolAdmissionPolicy;
import com.thingworx.things.agent.tools.ToolAdmissionSignals;
import com.thingworx.things.agent.tools.ToolRegistry;

import java.nio.charset.StandardCharsets;

/** Fixed M1a census inputs (CC-1.3 step 1) for test baseline export and pinning. */
public final class M1aBaselineSupport {

    public static final String OPENAI_API_SHAPE = "openai-chat-completions-v1";
    public static final String ANTHROPIC_API_SHAPE = "anthropic-messages-v1";
    private static final String ROUTING_GUIDE_RESOURCE =
            "/com/thingworx/things/agent/llm_tool_routing_guide.txt";

    private static final ObjectMapper JSON = new ObjectMapper();

    private M1aBaselineSupport() {}

    public static Map<String, Object> fixtureProvenance() throws JsonProcessingException {
        Map<String, Object> deployment = loadDeploymentFixture();
        Map<String, Object> lazySignals = new LinkedHashMap<>();
        lazySignals.put("taxonomyReady", deployment.get("taxonomyReady"));
        lazySignals.put("loadedSkills", deployment.get("modelFacingSkillAdmission"));
        lazySignals.put("loadedPlaybook", deployment.get("playbookLoaded"));
        lazySignals.put("documentScopeActive", false);
        lazySignals.put("slashActive", false);
        lazySignals.put("registeredTools", deployment.get("taxonomyRegisteredToolNames"));
        lazySignals.put("requiredTools", List.of());
        lazySignals.put("requiredBuckets", List.of());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("builtInRegistration", "BuiltInTools.registerAll(reg, false, false)");
        out.put("deploymentFixtureResource", "context-compaction/m1a/deployment-fixture.json");
        out.put("deploymentFixture", deployment);
        out.put("lazyAdmissionSignals", lazySignals);
        out.put("routingGuideSource", "bundled llm_tool_routing_guide.txt (append=true census)");
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadDeploymentFixture() {
        try (InputStream in = M1aBaselineSupport.class.getResourceAsStream(
                "/context-compaction/m1a/deployment-fixture.json")) {
            if (in == null) {
                throw new IllegalStateException("missing deployment-fixture.json");
            }
            return JSON.readValue(in, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load deployment-fixture.json", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<ToolDefinition> buildFixtureExtendedTools() {
        Map<String, Object> deployment = loadDeploymentFixture();
        Object merged = deployment.get("extendedToolsMerged");
        if (!(merged instanceof List)) {
            return List.of();
        }
        List<?> list = (List<?>) merged;
        if (list.isEmpty()) {
            return List.of();
        }
        List<ToolDefinition> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) item;
            String name = String.valueOf(raw.get("name"));
            String description = String.valueOf(raw.get("description"));
            Object schemaObj = raw.get("parametersSchema");
            if (!(schemaObj instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = (Map<String, Object>) schemaObj;
            boolean playbookSafe = Boolean.TRUE.equals(raw.get("playbookSafe"));
            out.add(new ToolDefinition(name, description, schema, playbookSafe));
        }
        return out;
    }

    public static List<ToolDefinition> buildOffModeTools() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false, false);
        List<ToolDefinition> tools = new ArrayList<>(reg.getAllDefinitions());
        tools.addAll(buildFixtureExtendedTools());
        return tools;
    }

    public static ToolAdmissionSignals buildLazyAdmissionSignals() {
        Map<String, Object> deployment = loadDeploymentFixture();
        return new ToolAdmissionSignals(
                null,
                false,
                false,
                Boolean.TRUE.equals(deployment.get("taxonomyReady")),
                false,
                Boolean.TRUE.equals(deployment.get("playbookLoaded")),
                registeredToolNames(deployment),
                java.util.Collections.emptySet());
    }

    public static ToolAdmissionPolicy.LazyResult buildLazyFirstRoundResult() {
        return ToolAdmissionPolicy.lazy(buildOffModeTools(), buildLazyAdmissionSignals(),
                java.util.Collections.emptySet());
    }

    public static ToolAdmissionPolicy.LazyResult buildLazyResultWithRegistered(java.util.Set<String> registered) {
        return ToolAdmissionPolicy.lazy(buildOffModeTools(), buildLazyAdmissionSignals(), registered);
    }

    public static java.util.Map<String, ToolDefinition> indexOffModeToolsByName() {
        java.util.LinkedHashMap<String, ToolDefinition> out = new java.util.LinkedHashMap<>();
        for (ToolDefinition td : buildOffModeTools()) {
            if (td != null && td.getName() != null) {
                out.put(td.getName(), td);
            }
        }
        return out;
    }

    public static List<ToolDefinition> buildLazyFirstRoundTools() {
        ToolAdmissionPolicy.LazyResult lazy = buildLazyFirstRoundResult();
        List<ToolDefinition> out = new ArrayList<>(lazy.advertised());
        if (!lazy.catalog().isEmpty()) {
            out.add(buildLoadToolSchemasDef(lazy.catalog()));
        }
        return out;
    }

    public static int routingGuideChars() {
        try (InputStream in = M1aBaselineSupport.class.getResourceAsStream(ROUTING_GUIDE_RESOURCE)) {
            if (in == null) {
                return 0;
            }
            return in.readAllBytes().length;
        } catch (Exception e) {
            return 0;
        }
    }

    public static int workflowHostContextUtf8Chars() throws JsonProcessingException {
        Map<String, Object> deployment = loadDeploymentFixture();
        Object ctx = deployment.get("workflowHostContext");
        if (ctx == null) {
            return 0;
        }
        return JSON.writeValueAsString(ctx).getBytes(StandardCharsets.UTF_8).length;
    }

    public static int taxonomyAdmissionContextUtf8Chars() {
        Map<String, Object> deployment = loadDeploymentFixture();
        Object text = deployment.get("taxonomyAdmissionContext");
        if (text == null) {
            return 0;
        }
        return String.valueOf(text).getBytes(StandardCharsets.UTF_8).length;
    }

    public static Map<String, Object> snapshotCombination(String apiShapeId, String admissionMode,
            List<ToolDefinition> tools, ToolAdmissionPolicy.LazyResult lazyResult)
            throws JsonProcessingException {
        LinkedHashMap<String, Integer> perToolSchema = ToolSchemaSizer.perToolSchemaChars(apiShapeId, tools);
        LinkedHashMap<String, Integer> perToolEditable = M1aEditableDescriptionMetrics.perToolEditableChars(tools);

        LinkedHashMap<String, String> paramProjections = new LinkedHashMap<>();
        for (ToolDefinition tool : tools) {
            if (tool == null || tool.getName() == null) {
                continue;
            }
            paramProjections.put(tool.getName(),
                    M1aDescriptionProjection.structuralProjectionJson(tool.getParametersSchema()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apiShapeId", apiShapeId);
        out.put("admissionMode", admissionMode);
        out.put("toolCount", tools.size());
        out.put("toolNamesOrdered", tools.stream().map(ToolDefinition::getName).collect(Collectors.toList()));
        out.put("toolSchemaChars", ToolSchemaSizer.totalSchemaChars(apiShapeId, tools));
        out.put("perToolSchemaChars", perToolSchema);
        out.put("perToolEditableChars", perToolEditable);
        out.put("sevenPriorityEditableTotal", M1aEditableDescriptionMetrics.sevenPriorityToolsEditableTotal(tools));
        out.put("parameterStructureProjections", paramProjections);
        if ("lazy".equals(admissionMode) && lazyResult != null) {
            String catalogRendered = ToolAdmissionPolicy.renderCatalog(lazyResult.catalog());
            List<Map<String, String>> catalogEntries = new ArrayList<>();
            for (ToolAdmissionPolicy.CatalogEntry entry : lazyResult.catalog()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("name", entry.name());
                row.put("blurb", entry.blurb());
                catalogEntries.add(row);
            }
            out.put("lazyCatalog", Map.of(
                    "rendered", catalogRendered,
                    "entries", catalogEntries,
                    "advertisedNames", lazyResult.advertised().stream().map(ToolDefinition::getName).collect(Collectors.toList())));
            ToolDefinition meta = tools.stream()
                    .filter(t -> "load_tool_schemas".equals(t.getName()))
                    .findFirst()
                    .orElse(null);
            out.put("loadToolSchemasMetaEditableChars",
                    meta != null ? M1aEditableDescriptionMetrics.toolSchemaEditableChars(meta) : 0);
        }
        return out;
    }

    private static java.util.Set<String> registeredToolNames(Map<String, Object> deployment) {
        Object names = deployment.get("taxonomyRegisteredToolNames");
        if (!(names instanceof List)) {
            return java.util.Collections.emptySet();
        }
        List<?> list = (List<?>) names;
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (Object item : list) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    public static Map<String, Object> fullBaselineDocument() throws JsonProcessingException {
        List<Map<String, Object>> combinations = new ArrayList<>();
        List<ToolDefinition> offTools = buildOffModeTools();
        combinations.add(snapshotCombination(OPENAI_API_SHAPE, "off", offTools, null));
        combinations.add(snapshotCombination(ANTHROPIC_API_SHAPE, "off", offTools, null));
        ToolAdmissionPolicy.LazyResult lazy = buildLazyFirstRoundResult();
        List<ToolDefinition> lazyTools = buildLazyFirstRoundTools();
        combinations.add(snapshotCombination(OPENAI_API_SHAPE, "lazy", lazyTools, lazy));
        combinations.add(snapshotCombination(ANTHROPIC_API_SHAPE, "lazy", lazyTools, lazy));

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("baselineVersion", 4);
        doc.put("fixtureProvenance", fixtureProvenance());
        doc.put("priorityToolNames", M1aEditableDescriptionMetrics.PRIORITY_TOOL_NAMES);
        doc.put("routingGuideChars", routingGuideChars());
        doc.put("workflowHostContextUtf8Chars", workflowHostContextUtf8Chars());
        doc.put("taxonomyAdmissionContextUtf8Chars", taxonomyAdmissionContextUtf8Chars());
        doc.put("combinations", combinations);
        return doc;
    }

    public static String fullBaselineJson() throws JsonProcessingException {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(fullBaselineDocument());
    }

    private static ToolDefinition buildLoadToolSchemasDef(List<ToolAdmissionPolicy.CatalogEntry> catalog) {
        String catalogText = ToolAdmissionPolicy.renderCatalog(catalog);
        String description = LoadToolSchemasExecutor.metaToolDescriptionPrefix() + catalogText;
        Map<String, Object> names = new LinkedHashMap<>();
        names.put("type", "array");
        names.put("items", Map.of("type", "string"));
        names.put("description", "Exact tool names to load, taken from the Available tools catalog above.");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("names", names);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("names"));
        return new ToolDefinition("load_tool_schemas", description, schema);
    }
}
