package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Provider-safe JSON Schema for {@code build_chart_from_tabular_result}. Uses {@link BuildChartFromTabularResultToolSchema}
 * so the test classpath does not need to load {@link BuiltInTools}.
 */
class BuildChartFromTabularResultToolSchemaTest {

    @Test
    void parameters_rootIsObjectWithoutRootOneOf() {
        Map<String, Object> schema = BuildChartFromTabularResultToolSchema.parametersSchema();
        assertEquals("object", schema.get("type"));
        assertFalse(schema.containsKey("oneOf"), "root oneOf can cause provider-side tool request rejection");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertNotNull(props);
        assertTrue(props.containsKey("kind"));
        assertTrue(props.containsKey("intent"));
        Object req = schema.get("required");
        assertTrue(req instanceof String[], "required must be String[] from schema map");
        assertArrayEquals(new String[]{"source"}, (String[]) req,
                "C2b-1: xColumn is required per kind (histogram sources are already binned), not at the root");
        @SuppressWarnings("unchecked")
        List<String> kinds = (List<String>) ((Map<String, Object>) props.get("kind")).get("enum");
        assertEquals(List.of("line", "bar", "scatter", "pie", "histogram", "boxplot", "heatmap"), kinds);
        assertTrue(String.valueOf(((Map<?, ?>) props.get("seriesColumn")).get("description")).contains("heatmap"));
        @SuppressWarnings("unchecked")
        Map<String, Object> histogramMode = (Map<String, Object>) props.get("histogramMode");
        assertEquals(List.of("count", "density"), histogramMode.get("enum"));
        assertTrue(String.valueOf(histogramMode.get("description")).contains("histogram"));
        assertTrue(String.valueOf(((Map<?, ?>) props.get("xColumn")).get("description")).contains("omitted for histogram and boxplot"));
        assertTrue(String.valueOf(((Map<?, ?>) props.get("yReferenceLines")).get("description")).contains("boxplot"));
        assertTrue(String.valueOf(((Map<?, ?>) props.get("yReferenceLines")).get("description")).contains("heatmap"));
    }

    @Test
    void orientation_isBarOnlyEnumWithVerticalDefault() {
        @SuppressWarnings("unchecked")
        Map<String, Object> props =
                (Map<String, Object>) BuildChartFromTabularResultToolSchema.parametersSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> orientation = (Map<String, Object>) props.get("orientation");
        assertNotNull(orientation, "orientation parameter is declared (chart-enhancement §7.3)");
        assertEquals("string", orientation.get("type"));
        assertEquals(java.util.List.of("vertical", "horizontal"), orientation.get("enum"));
        String d = (String) orientation.get("description");
        assertTrue(d.contains("bar"), "description states the bar-only rule");
        assertTrue(d.contains("default vertical"));
        assertTrue(d.contains("INVALID_PARAMETERS"));
        Object req = BuildChartFromTabularResultToolSchema.parametersSchema().get("required");
        assertArrayEquals(new String[]{"source"}, (String[]) req, "orientation stays optional");
        @SuppressWarnings("unchecked")
        Map<String, Object> stackMode = (Map<String, Object>) props.get("stackMode");
        assertNotNull(stackMode, "stackMode parameter is declared (chart-enhancement §7.4 C2a-2)");
        assertEquals(java.util.List.of("grouped", "stacked", "percent"), stackMode.get("enum"));
        String sd = (String) stackMode.get("description");
        assertTrue(sd.contains("bar only") && sd.contains("at least two series") && sd.contains("STACK_PERCENT_NEGATIVE"));
        @SuppressWarnings("unchecked")
        Map<String, Object> memberKey = (Map<String, Object>) props.get("groupMemberKey");
        assertNotNull(memberKey, "groupMemberKey parameter is declared (chart-enhancement §8.5 C3b-1)");
        assertTrue(String.valueOf(memberKey.get("description")).contains("declare_chart_group"));
    }

    @Test
    void xColumnAndYColumnDescriptionsAlignWithRequiredArray_noPhaseOnlyOmitCaveats() {
        @SuppressWarnings("unchecked")
        Map<String, Object> props =
                (Map<String, Object>) BuildChartFromTabularResultToolSchema.parametersSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> xCol = (Map<String, Object>) props.get("xColumn");
        @SuppressWarnings("unchecked")
        Map<String, Object> yCol = (Map<String, Object>) props.get("yColumn");
        String xd = (String) xCol.get("description");
        String yd = (String) yCol.get("description");
        assertFalse(xd.contains("may omit"), "xColumn must not contradict required: [source, xColumn]");
        assertFalse(xd.contains("phase-only"), "schema text stays aligned with strict required list");
        assertFalse(yd.contains("phase-only"));
        assertFalse(yd.contains("intent fallbacks"));
    }
}
