package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.llm.ToolSchemaSizer;

/**
 * M3 Slice 3 / R11 close: true tools-prefix aggregate vs the ~75k plan target
 * ({@code ToolSchemaSizer.totalSchemaChars}), not a per-slice B16/B17 offset claim.
 */
class M3Slice3R11TrueAggregateCloseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Plan R2 tools-prefix target (~75k chars). */
    private static final int TOOLS_PREFIX_TARGET_CHARS = 75_000;

    @Test
    void builtInAdvertisedTrueAggregateUnderToolsPrefixTarget() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        List<ToolDefinition> defs = new ArrayList<>(reg.getAllDefinitions());
        assertEquals(28, defs.size(), "update when BuiltInTools.registerAll changes");

        int openaiTotal = ToolSchemaSizer.totalSchemaChars("openai-chat-completions-v1", defs);
        int anthropicTotal = ToolSchemaSizer.totalSchemaChars("anthropic-messages-v1", defs);

        long descUtf8 = 0;
        long schemaUtf8 = 0;
        for (ToolDefinition d : defs) {
            descUtf8 += d.getDescription().getBytes(StandardCharsets.UTF_8).length;
            schemaUtf8 += MAPPER.writeValueAsString(d.getParametersSchema()).getBytes(StandardCharsets.UTF_8).length;
        }
        int rootEnum = ThingworxRootEntityTypes.sortedRootEntityTypeNames().size();

        LinkedHashMap<String, Integer> perTool = ToolSchemaSizer.perToolSchemaChars(
                "openai-chat-completions-v1", defs);
        List<Map.Entry<String, Integer>> top = new ArrayList<>(perTool.entrySet());
        top.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed());
        StringBuilder topFive = new StringBuilder();
        for (int i = 0; i < Math.min(5, top.size()); i++) {
            if (i > 0) {
                topFive.append(", ");
            }
            topFive.append(top.get(i).getKey()).append('=').append(top.get(i).getValue());
        }

        String evidence = String.format(
                "R11 true-aggregate toolCount=%d openai.totalSchemaChars=%d anthropic.totalSchemaChars=%d "
                        + "target=%d footprint descUtf8=%d schemaUtf8=%d rootEntityTypeEnumSize=%d top5=[%s]",
                defs.size(), openaiTotal, anthropicTotal, TOOLS_PREFIX_TARGET_CHARS,
                descUtf8, schemaUtf8, rootEnum, topFive);
        System.out.println("M3Slice3R11TrueAggregateCloseTest: " + evidence);

        assertTrue(openaiTotal > 0, evidence);
        assertTrue(anthropicTotal > 0, evidence);
        assertTrue(openaiTotal < TOOLS_PREFIX_TARGET_CHARS,
                "openai tools-prefix aggregate must stay under ~75k: " + evidence);
        assertTrue(anthropicTotal < TOOLS_PREFIX_TARGET_CHARS,
                "anthropic tools-prefix aggregate must stay under ~75k: " + evidence);
        // Footprint pair is a secondary ledger; still under the same soft ceiling on this tree.
        assertTrue(descUtf8 + schemaUtf8 < TOOLS_PREFIX_TARGET_CHARS,
                "merged desc+schema footprint under ~75k: " + evidence);
        assertEquals(34, rootEnum, evidence);
    }
}
