package com.thingworx.things.agent.llm;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.tools.AnalyzeEntitySetToolSchema;
import com.thingworx.things.agent.tools.BuildChartFromTabularResultToolSchema;
import com.thingworx.things.agent.tools.InvokeServiceToolSchemaFragment;
import com.thingworx.things.agent.tools.TabulateCachedResultToolSchema;

/**
 * Offline guard: strict LLM hosts (Azure OpenAI chat-completions) reject tool definitions whose JSON Schema
 * uses {@code type: array} without {@code items}.
 */
class LlmJsonSchemaCompatStaticSchemasTest {

    @Test
    void analyzeEntitySet_parametersSchema_arraysDeclareItems() {
        LlmJsonSchemaCompat.assertArraysDeclareItems("analyze_entity_set", AnalyzeEntitySetToolSchema.parametersSchema());
    }

    @Test
    void tabulateCachedResult_parametersSchema_arraysDeclareItems() {
        LlmJsonSchemaCompat.assertArraysDeclareItems("tabulate_cached_result", TabulateCachedResultToolSchema.parametersSchema());
    }

    @Test
    void invokeService_parametersSchema_arraysDeclareItems() {
        LlmJsonSchemaCompat.assertArraysDeclareItems("invoke_service",
                InvokeServiceToolSchemaFragment.invokeServiceToolDefinition().getParametersSchema());
    }

    @Test
    void buildChartFromTabularResult_parametersSchema_arraysDeclareItems() {
        LlmJsonSchemaCompat.assertArraysDeclareItems("build_chart_from_tabular_result",
                BuildChartFromTabularResultToolSchema.parametersSchema());
    }

    @Test
    void summarizeCachedResult_percentileColumnsArray_declaresItems() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("cacheId", Map.of("type", "string"));
        props.put("percentileColumns", Map.of(
                "type", "array",
                "items", Map.of("type", "string")));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[] {"cacheId"});
        LlmJsonSchemaCompat.assertArraysDeclareItems("summarize_cached_result", schema);
    }

    @Test
    void compatHelper_rejectsBareArray() {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("type", "object");
        bad.put("properties", Map.of("x", Map.of("type", "array", "description", "no items")));
        assertThrows(IllegalArgumentException.class,
                () -> LlmJsonSchemaCompat.assertArraysDeclareItems("synthetic", bad));
    }

    @Test
    void compatHelper_rejectsRootOneOf() {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("type", "object");
        bad.put("properties", Map.of());
        bad.put("oneOf", java.util.List.of(Map.of("required", java.util.List.of("a"))));
        assertThrows(IllegalArgumentException.class,
                () -> LlmJsonSchemaCompat.assertNoRootSchemaCombinators("synthetic", bad));
        assertThrows(IllegalArgumentException.class,
                () -> LlmJsonSchemaCompat.assertCompatible("synthetic", bad));
    }

    @Test
    void buildChartAndAnalyze_passFullCompatibleCheck() {
        LlmJsonSchemaCompat.assertCompatible("build_chart_from_tabular_result",
                BuildChartFromTabularResultToolSchema.parametersSchema());
        LlmJsonSchemaCompat.assertCompatible("analyze_entity_set", AnalyzeEntitySetToolSchema.parametersSchema());
        LlmJsonSchemaCompat.assertCompatible("tabulate_cached_result", TabulateCachedResultToolSchema.parametersSchema());
    }

    /** Bug 005 Tier 2.1 — LLM-facing `having` description must steer away from sampleRows extrapolation. */
    @Test
    void tabulateCachedResult_havingDescriptionSteersAwayFromSampleRowsExtrapolation() {
        Map<String, Object> schema = TabulateCachedResultToolSchema.parametersSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> having = (Map<String, Object>) props.get("having");
        String desc = (String) having.get("description");
        assertTrue(desc.contains("grouped output"), desc);
        assertTrue(desc.contains("Prefer `having`"), desc);
        assertTrue(desc.contains("sampleRows"), desc);
        assertTrue(desc.contains("totalRows"), desc);
        assertTrue(desc.contains("equality or threshold"), desc);
    }

    /** Measure filters schema steers away from `{"type":"TRUE"}` placeholders. */
    @Test
    void tabulateCachedResult_measureFiltersDescription_mentionsUnconditionalOmitPlaceholders() throws Exception {
        Map<String, Object> schema = TabulateCachedResultToolSchema.parametersSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> measures = (Map<String, Object>) props.get("measures");
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) measures.get("items");
        @SuppressWarnings("unchecked")
        Map<String, Object> measureProps = (Map<String, Object>) items.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> filters = (Map<String, Object>) measureProps.get("filters");
        String desc = (String) filters.get("description");
        assertTrue(desc.contains("omit this field entirely"), desc);
        assertTrue(desc.contains("TRUE"), desc);
        assertTrue(desc.contains("MATCH_ALL"), desc);
    }
}
