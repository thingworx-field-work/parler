package com.thingworx.things.agent.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.LlmRoutingGuide;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.tools.InvokeServiceToolSchemaFragment;
import com.thingworx.things.agent.tools.LoadToolSchemasExecutor;
import com.thingworx.things.agent.tools.TabulateCachedResultToolSchema;
import com.thingworx.things.agent.tools.ToolAdmissionPolicy;

/** Loads CC-1.2 semantic checklist and asserts post-reduction tool prose. */
public final class M1aSemanticPreservationSupport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int LAZY_BLURB_MAX = 160;
    private static final String ROUTING_GUIDE_MARKER = "## Asset identity and scope";

    private M1aSemanticPreservationSupport() {}

    public static JsonNode loadChecklist() throws Exception {
        try (InputStream in = M1aSemanticPreservationSupport.class.getResourceAsStream(
                "/context-compaction/m1a/semantic-preservation-checklist.json")) {
            assertNotNull(in, "missing semantic-preservation-checklist.json");
            return JSON.readTree(in);
        }
    }

    public static ToolDefinition findTool(List<ToolDefinition> tools, String name) {
        return tools.stream()
                .filter(t -> t != null && name.equals(t.getName()))
                .findFirst()
                .orElse(null);
    }

    public static void assertPriorityToolSemantics(List<ToolDefinition> offTools, JsonNode checklist) {
        JsonNode toolsNode = checklist.get("tools");
        assertNotNull(toolsNode);
        for (String name : M1aEditableDescriptionMetrics.PRIORITY_TOOL_NAMES) {
            assertToolByName(offTools, checklist, name);
        }
    }

    public static void assertAppendixIndependentSemantics(List<ToolDefinition> tools, JsonNode checklist) {
        JsonNode toolsNode = checklist.get("tools");
        assertNotNull(toolsNode);
        for (String name : M1aEditableDescriptionMetrics.PRIORITY_TOOL_NAMES) {
            JsonNode entry = toolsNode.get(name);
            if (entry == null || !entry.has("appendixIndependentMustContain")) {
                continue;
            }
            ToolDefinition tool = findTool(tools, name);
            assertNotNull(tool, "missing tool " + name);
            String standalone = collectStandaloneEditableText(tool);
            for (JsonNode fragment : entry.get("appendixIndependentMustContain")) {
                String needle = fragment.asText();
                assertTrue(standalone.contains(needle),
                        () -> name + " must retain \"" + needle + "\" without routing guide appendix: "
                                + abbreviate(standalone));
            }
        }
    }

    public static void assertRoutingGuideAppendSwitch() {
        String userPrompt = "Operator system prompt.";
        String appendOff = LlmRoutingGuide.composeSystemPrompt(userPrompt, false);
        String appendOn = LlmRoutingGuide.composeSystemPrompt(userPrompt, true);
        assertEquals(userPrompt, appendOff,
                "append=false must not inject routing guide into system prompt");
        assertTrue(appendOn.length() > appendOff.length(),
                "append=true must lengthen system prompt with routing guide");
        assertTrue(appendOn.contains(ROUTING_GUIDE_MARKER),
                "append=true must include bundled routing guide marker");
        assertFalse(appendOff.contains(ROUTING_GUIDE_MARKER),
                "append=false must not include routing guide text");

        String guideOnly = LlmRoutingGuide.composeSystemPrompt("", true);
        assertTrue(guideOnly.contains(ROUTING_GUIDE_MARKER));
        assertFalse(guideOnly.contains("Operator system prompt."));
    }

    public static void assertToolByName(List<ToolDefinition> tools, JsonNode checklist, String name) {
        ToolDefinition tool = findTool(tools, name);
        assertNotNull(tool, "missing tool " + name);
        JsonNode entry = checklist.get("tools").get(name);
        assertNotNull(entry, "checklist missing " + name);
        assertToolEntry(tool, entry);
    }

    public static void assertLazyCatalogBlurbs(ToolAdmissionPolicy.LazyResult lazy, JsonNode checklist) {
        JsonNode toolsNode = checklist.get("tools");
        assertNotNull(toolsNode);
        for (ToolAdmissionPolicy.CatalogEntry entry : lazy.catalog()) {
            JsonNode spec = toolsNode.get(entry.name());
            if (spec == null || !spec.has("lazyBlurbMustContain")) {
                continue;
            }
            String blurb = entry.blurb();
            assertNotNull(blurb);
            assertTrue(lazyBlurbMatchesChecklist(entry.name(), blurb, checklist),
                    () -> "lazy blurb failed positive checklist for " + entry.name() + ": " + blurb);
        }
    }

    public static boolean lazyBlurbMatchesChecklist(String toolName, String blurb, JsonNode checklist) {
        if (blurb == null || blurb.isBlank()) {
            return false;
        }
        if (blurb.length() > LAZY_BLURB_MAX) {
            return false;
        }
        JsonNode spec = checklist.get("tools").get(toolName);
        if (spec == null) {
            return true;
        }
        if (spec.has("lazyBlurbMustContain")) {
            for (JsonNode fragment : spec.get("lazyBlurbMustContain")) {
                if (!blurb.contains(fragment.asText())) {
                    return false;
                }
            }
        }
        if (spec.has("lazyBlurbMustNotContain")) {
            for (JsonNode fragment : spec.get("lazyBlurbMustNotContain")) {
                if (blurb.contains(fragment.asText())) {
                    return false;
                }
            }
        }
        return true;
    }

    public static void assertTabulateFilterPredicateKeys() {
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) TabulateCachedResultToolSchema.parametersSchema()
                .get("properties");
        String filtersDesc = String.valueOf(((Map<?, ?>) props.get("filters")).get("description"));
        assertTrue(filtersDesc.contains("\"value\""), filtersDesc);
        assertTrue(filtersDesc.contains("\"from\""), filtersDesc);
        assertTrue(filtersDesc.contains("\"values\""), filtersDesc);
        assertTrue(filtersDesc.contains("\"filters\""), filtersDesc);
    }

    public static void assertInvokeServiceParametersNestedRule() {
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) InvokeServiceToolSchemaFragment
                .invokeServiceParametersSchemaMap().get("properties");
        String parametersDesc = String.valueOf(((Map<?, ?>) props.get("parameters")).get("description"));
        assertTrue(parametersDesc.toLowerCase(Locale.ROOT).contains("nested"), parametersDesc);
        assertTrue(parametersDesc.contains("parameters"), parametersDesc);
    }

    public static void assertDeferredPriorityToolsLoadEnvelopeAndSubsequentLazyRound(
            List<String> deferredPriority, JsonNode checklist) throws Exception {
        ToolAdmissionPolicy.LazyResult firstRound = M1aBaselineSupport.buildLazyFirstRoundResult();
        Set<String> catalogNames = firstRound.catalog().stream()
                .map(ToolAdmissionPolicy.CatalogEntry::name)
                .collect(java.util.stream.Collectors.toSet());
        for (String name : deferredPriority) {
            assertTrue(catalogNames.contains(name), () -> name + " must be deferred in lazy first round");
            assertFalse(firstRound.advertised().stream().anyMatch(t -> name.equals(t.getName())),
                    () -> name + " must not be advertised before load");
        }

        Map<String, ToolDefinition> byName = M1aBaselineSupport.indexOffModeToolsByName();
        LoadToolSchemasExecutor.EnvelopeResult envelope =
                LoadToolSchemasExecutor.buildEnvelope(deferredPriority, byName);
        assertEquals(deferredPriority, envelope.resolvedNames,
                "load_tool_schemas envelope must resolve all deferred priority tools");

        JsonNode root = JSON.readTree(envelope.json);
        assertEquals("ok", root.path("status").asText());
        assertEquals(deferredPriority.size(), root.path("loaded").size());
        assertTrue(root.path("not_found").isEmpty());

        for (String name : deferredPriority) {
            JsonNode entry = findEnvelopeEntry(root, name);
            assertNotNull(entry, "missing loaded entry for " + name);
            ToolDefinition registryDef = byName.get(name);
            assertNotNull(registryDef, "registry missing " + name);
            assertEquals(registryDef.getDescription(), entry.path("description").asText(),
                    () -> name + " envelope description must match registry");
            ToolDefinition fromEnvelope = toolFromEnvelopeEntry(entry);
            assertToolByName(List.of(fromEnvelope), checklist, name);
        }

        LinkedHashMap<String, ToolDefinition> registered = new LinkedHashMap<>();
        for (String name : deferredPriority) {
            registered.put(name, byName.get(name));
        }
        ToolAdmissionPolicy.LazyResult afterLoad = M1aBaselineSupport.buildLazyResultWithRegistered(
                registered.keySet());
        for (String name : deferredPriority) {
            ToolDefinition advertised = findTool(afterLoad.advertised(), name);
            assertNotNull(advertised, () -> name + " must be advertised after registration");
            assertFalse(afterLoad.catalog().stream().anyMatch(e -> name.equals(e.name())),
                    () -> name + " must leave catalog after registration");
            assertEquals(byName.get(name).getDescription(), advertised.getDescription(),
                    () -> name + " subsequent-round description must match registry");
            assertToolByName(List.of(advertised), checklist, name);
        }
    }

    @SuppressWarnings("unchecked")
    public static ToolDefinition toolFromEnvelopeEntry(JsonNode entry) {
        String name = entry.path("name").asText();
        String description = entry.path("description").asText();
        Map<String, Object> schema = JSON.convertValue(entry.path("input_schema"), Map.class);
        return new ToolDefinition(name, description, schema, true);
    }

    private static JsonNode findEnvelopeEntry(JsonNode root, String name) {
        for (JsonNode entry : root.path("loaded")) {
            if (name.equals(entry.path("name").asText())) {
                return entry;
            }
        }
        return null;
    }

    public static String collectStandaloneEditableText(ToolDefinition tool) {
        StringBuilder sb = new StringBuilder();
        if (tool.getDescription() != null) {
            sb.append(tool.getDescription());
        }
        appendSchemaDescriptions(tool.getParametersSchema(), sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendSchemaDescriptions(Object node, StringBuilder sb) {
        if (node instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) node;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    continue;
                }
                String key = (String) entry.getKey();
                Object value = entry.getValue();
                if ("description".equals(key) && value instanceof String) {
                    sb.append((String) value);
                    continue;
                }
                appendSchemaDescriptions(value, sb);
            }
        } else if (node instanceof List<?>) {
            for (Object item : (List<?>) node) {
                appendSchemaDescriptions(item, sb);
            }
        }
    }

    private static void assertToolEntry(ToolDefinition tool, JsonNode entry) {
        String top = Objects.toString(tool.getDescription(), "");
        assertFragments(top, entry.get("topLevelMustContain"), tool.getName() + " top-level");
        assertPhrases(top, entry.get("topLevelMustContainPhrase"), tool.getName() + " top-level");

        if (entry.has("schemaPropertyMustContain")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) tool.getParametersSchema().get("properties");
            entry.get("schemaPropertyMustContain").properties().forEach(field -> {
                Object prop = props.get(field.getKey());
                assertNotNull(prop, tool.getName() + " missing property " + field.getKey());
                String desc = String.valueOf(((Map<?, ?>) prop).get("description"));
                assertFragments(desc, field.getValue(), tool.getName() + "." + field.getKey());
            });
        }
    }

    private static void assertFragments(String haystack, JsonNode fragments, String context) {
        if (fragments == null || !fragments.isArray()) {
            return;
        }
        for (JsonNode fragment : fragments) {
            String needle = fragment.asText();
            assertTrue(haystack.contains(needle),
                    () -> context + " must contain \"" + needle + "\"");
        }
    }

    private static void assertPhrases(String haystack, JsonNode phrases, String context) {
        if (phrases == null || !phrases.isArray()) {
            return;
        }
        for (JsonNode phrase : phrases) {
            String needle = phrase.asText();
            assertTrue(haystack.contains(needle),
                    () -> context + " must contain phrase \"" + needle + "\"");
        }
    }

    private static String abbreviate(String text) {
        if (text.length() <= 240) {
            return text;
        }
        return text.substring(0, 240) + "...";
    }
}
