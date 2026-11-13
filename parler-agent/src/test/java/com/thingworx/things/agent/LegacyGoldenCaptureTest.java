package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.InvokeServiceExecutor;
import com.thingworx.things.agent.tools.ResultFixtures;
import com.thingworx.types.BaseTypes;

/**
 * Step 0 of topic round 6: captures the legacy goldens on the unmodified tree. Runs only when
 * {@code PARLER_CAPTURE_LEGACY_GOLDENS=1}; every precondition is asserted <b>before</b> any golden is written, so a
 * fixture that does not reach the behaviour it claims fails the capture instead of producing an oracle.
 */
class LegacyGoldenCaptureTest {

    static final String DIR = "src/test/resources/tool-result/legacy";

    @Test
    void capture_legacy_goldens() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("PARLER_CAPTURE_LEGACY_GOLDENS")),
                "legacy golden capture runs only on request");
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
        try {
            Map<String, String> goldens = new LinkedHashMap<>();
            Map<String, String> inputs = inputs();
            for (Map.Entry<String, String> e : inputs.entrySet()) {
                goldens.put(e.getKey() + ".gateway",
                        ToolResultEgressGateway.compactForLlmAppend(ResultFixtures.TOOL, "call-1", e.getValue(), null)
                                .getLlmContent());
            }
            AgentToolContext.setConversationId("legacy-golden-capture");
            goldens.put("r-a-string.formatter", InvokeServiceExecutor.formatDirectServiceResultForLlm(
                    ResultFixtures.jsonServiceResult(ResultFixtures.recordsArray()),
                    ResultFixtures.serviceReturning(BaseTypes.JSON), null));
            goldens.put("r-d.approved", approvedRow(inputs.get("r-d")));
            goldens.put("r-h.approved", approvedRow(inputs.get("r-h")));
            goldens.put("r-c.approved", approvedRow(inputs.get("r-c")));

            assertPreconditions(inputs, goldens);

            Path dir = Paths.get(DIR);
            Files.createDirectories(dir);
            for (Map.Entry<String, String> e : goldens.entrySet()) {
                Files.writeString(dir.resolve(e.getKey() + ".json"), e.getValue(), StandardCharsets.UTF_8);
            }
        } finally {
            com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
            AgentToolContext.clear();
        }
    }

    static Map<String, String> inputs() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("r-a-string", ResultFixtures.stringEnvelope(ResultFixtures.recordsArray()));
        in.put("r-a-node", ResultFixtures.nodeEnvelope(ResultFixtures.recordsArray()));
        in.put("r-a0-string", ResultFixtures.stringEnvelope(ResultFixtures.recordsMap()));
        in.put("r-a0-node", ResultFixtures.nodeEnvelope(ResultFixtures.recordsMap()));
        in.put("r-b-node", ResultFixtures.nodeEnvelope(ResultFixtures.thirtyStrings()));
        in.put("r-b-string", ResultFixtures.stringEnvelope(ResultFixtures.thirtyStrings()));
        in.put("r-c", ResultFixtures.wideTable());
        in.put("r-d", ResultFixtures.longShortTable(ResultFixtures.FIXED_CACHE_ID));
        in.put("r-e", ResultFixtures.textObject());
        in.put("r-f", ResultFixtures.errorEnvelope());
        in.put("r-g", ResultFixtures.plainText());
        in.put("r-h", ResultFixtures.longWideCellTable(ResultFixtures.FIXED_CACHE_ID));
        in.put("r-i", ResultFixtures.escapingHeavyTable());
        in.put("r-j", ResultFixtures.schemaOnly());
        in.put("page-0001", ResultFixtures.page0001());
        in.put("page-0002", ResultFixtures.page0002());
        return in;
    }

    private static String approvedRow(String raw) {
        List<ChatMessage> msgs = new ArrayList<>();
        HitlSyntheticToolResultAppender.appendDurable(msgs, "approved-1", raw, null, null, "DemoAgent",
                ResultFixtures.TOOL);
        return msgs.get(0).getContent();
    }

    private static void assertPreconditions(Map<String, String> in, Map<String, String> g) {
        String ra = in.get("r-a-string");
        assertTrue(ra.length() >= 4_500 && ra.length() <= 8_000, "R-A string envelope size " + ra.length());
        JsonNode raOut = ResultFixtures.read(g.get("r-a-string.gateway"));
        assertTrue(raOut.path("truncated").asBoolean(false), "R-A golden has root truncated:true");
        assertEquals(2_003, raOut.path("result").asText().length(), "R-A result cut at 2,000 (+ellipsis)");

        assertEquals(in.get("r-a-node"), g.get("r-a-node.gateway"), "R-A node form unchanged");
        assertEquals(4, ResultFixtures.read(g.get("r-a-node.gateway")).path("result").path("records").size());
        assertEquals(in.get("r-a0-string"), g.get("r-a0-string.gateway"), "R-A0 string unchanged");
        assertEquals(in.get("r-a0-node"), g.get("r-a0-node.gateway"), "R-A0 node unchanged");

        assertEquals(20, ResultFixtures.read(g.get("r-b-node.gateway")).path("result").path("items").size(),
                "R-B raw object sampled to 20");
        assertTrue(in.get("r-b-string").length() < 2_000, "R-B string envelope under 2,000");
        assertEquals(in.get("r-b-string"), g.get("r-b-string.gateway"), "R-B string unchanged");

        assertEquals(2_003, ResultFixtures.read(g.get("r-e.gateway")).path("summary").asText().length(),
                "R-E cut at the text cap");
        String re = in.get("r-e");
        assertTrue(re.length() > 3_200 && re.length() < 3_600 && re.contains("["), "R-E size " + re.length());

        JsonNode rc = ResultFixtures.read(g.get("r-c.gateway"));
        assertTrue(rc.has("columns") && !rc.has("rows"), "R-C golden has columns and no row");
        assertTrue(in.get("r-c").length() > 8_192, "R-C above the soft cap");

        assertEquals(in.get("r-d"), g.get("r-d.gateway"), "R-D unchanged");
        assertEquals(20, ResultFixtures.read(g.get("r-d.gateway")).path("sampleRows").size());

        assertFalse(ResultFixtures.read(g.get("r-h.gateway")).has("sampleRows"), "R-H golden has no row");

        String rj = g.get("r-j.gateway");
        assertEquals(in.get("r-j"), rj, "R-J raw result returned");
        assertTrue(rj.length() > 8_192, "R-J above 8,192");

        String ri = g.get("r-i.gateway");
        assertTrue(ri.length() > 8_192, "R-I above 8,192 after reduction");
        JsonNode riNode = ResultFixtures.read(ri);
        assertFalse(riNode.has("rows"), "R-I rows gone");
        assertEquals(100, riNode.path("columns").size(), "R-I column names kept");

        String p2 = in.get("page-0002");
        assertTrue(p2.length() >= 9_000 && p2.length() <= 12_000, "page-0002 envelope " + p2.length());
        assertEquals(2_003, ResultFixtures.read(g.get("page-0002.gateway")).path("markdown").asText().length(),
                "page-0002 cut at 2,000");
        assertTrue(in.get("page-0001").length() < 3_500, "page-0001 envelope");
        // Observed, not predicted: the real page envelope always holds arrays (sectionPath, sourceLinks,
        // warnings), so the unmodified gateway enters compaction and cuts a 3,000-character page at 2,000.
        assertEquals(2_003, ResultFixtures.read(g.get("page-0001.gateway")).path("markdown").asText().length(),
                "page-0001 cut at 2,000 at baseline");

        assertEquals(in.get("r-g"), g.get("r-g.gateway"), "R-G plain text unchanged");
        assertTrue(ResultFixtures.read(g.get("r-f.gateway")).path("truncated").asBoolean(false),
                "R-F error rule cut");

        assertTrue(ResultFixtures.read(g.get("r-a-string.formatter")).path("result").isTextual(),
                "string mode formatter: one string field");
        assertEquals(in.get("r-d"), g.get("r-d.approved"), "approved row is the raw string");
        assertEquals(in.get("r-h"), g.get("r-h.approved"), "approved over-cap row is the raw string");
    }
}
