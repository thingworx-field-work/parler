package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.types.BaseTypes;

/**
 * B17: natural-time grammar/mutual-exclusion prose is emitted once on the harvested tool
 * description; synthetic field schemas keep short pointers only.
 */
class CustomToolB17SharedNaturalTimeProseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sharedSuffixIsPresentOnceOnToolDescriptionAndNotOnFieldSchemas() throws Exception {
        ServiceDefinition sd = new ServiceDefinition("_tool_exportHistory", "test");
        sd.getParameters().addFieldDefinition(new FieldDefinition("startDate", "", BaseTypes.DATETIME));
        sd.getParameters().addFieldDefinition(new FieldDefinition("endDate", "", BaseTypes.DATETIME));

        ToolDefinition def = CustomToolHarvester.toToolDefinitionForExtendedTool(
                sd, "export_history", "Export history window.", "Export", false);

        String marker = "docs/agent/time-interpretation.md";
        assertTrue(def.getDescription().contains(marker), def.getDescription());
        assertEquals(1, countOccurrences(def.getDescription(), marker));

        String schemaJson = MAPPER.writeValueAsString(def.getParametersSchema());
        assertFalse(schemaJson.contains(marker),
                "field schemas must not re-emit the shared time-interpretation prose: " + schemaJson);
        assertFalse(schemaJson.contains("Mutually exclusive"), schemaJson);
        assertTrue(schemaJson.contains("See tool description for natural-time contract"), schemaJson);

        CustomToolDateTimePairResolver.PairDetection pair =
                CustomToolDateTimePairResolver.PairDetection.of("startDate", "endDate", false);
        String suffix = CustomToolDateTimePairResolver.sharedNaturalTimeToolSuffix(pair);
        assertTrue(suffix.length() >= 200 && suffix.length() <= 400,
                "shared suffix should stay near its ~290-char characterization; was " + suffix.length());
    }

    @Test
    void augmentSchemaFieldDescriptionsStayShort() {
        Map<String, Object> properties = new LinkedHashMap<>();
        CustomToolDateTimePairResolver.augmentSchema(properties,
                CustomToolDateTimePairResolver.PairDetection.of("startDate", "endDate", false));
        @SuppressWarnings("unchecked")
        Map<String, Object> cp = (Map<String, Object>) properties.get("calendarPhrase");
        @SuppressWarnings("unchecked")
        Map<String, Object> rd = (Map<String, Object>) properties.get("relativeDuration");
        assertTrue(((String) cp.get("description")).length() < 120);
        assertTrue(((String) rd.get("description")).length() < 120);
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int from = 0;
        while (true) {
            int i = haystack.indexOf(needle, from);
            if (i < 0) {
                return n;
            }
            n++;
            from = i + needle.length();
        }
    }
}
