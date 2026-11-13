package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.ResultBudget;
import com.thingworx.things.agent.ToolResultEgressGateway;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;

/**
 * P39 (topic round 6, R25): a table that is too large keeps whole rows and a usable handle, or says it has none.
 * Observes the formatter output of the built-in path and the gateway output (the first model append).
 */
class ToolResultTableRecoveryTest {

    private static final int CAP = ResultBudget.DEFAULT_SOFT_CAP_CHARS;

    ToolResultTableRecoveryTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
        AgentToolContext.setConversationId("p39-table-recovery");
    }

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    private static String builtIn(InfoTable table) throws Exception {
        return InvokeServiceExecutor.formatToolResult(ResultFixtures.infotableWrapper(table),
                ResultFixtures.serviceReturning(BaseTypes.INFOTABLE), null,
                InvokeServiceParameterNormalizer.Repair.none(), null, false, ResultBudget.defaults());
    }

    private static String egress(String raw) {
        return ToolResultEgressGateway.compactForLlmAppend(ResultFixtures.TOOL, "c", raw, null, List.of(),
                ResultBudget.defaults()).getLlmContent();
    }

    /** The fill is greedy: every kept row is whole and the next source row would not have fitted. */
    private static void assertGreedyWholeRows(JsonNode source, String out, String rowsKey) {
        JsonNode o = ResultFixtures.read(out);
        assertTrue(out.length() <= CAP, "" + out.length());
        ArrayNode kept = (ArrayNode) o.path(rowsKey);
        ArrayNode all = (ArrayNode) source.path(rowsKey);
        for (int i = 0; i < kept.size(); i++) {
            assertEquals(all.get(i), kept.get(i), "row " + i + " whole, in source order, no cut cell");
        }
        assertEquals(kept.size(), o.path("returnedRows").asInt());
        assertFalse(o.has("rowsPartial"));
        assertTrue(o.path("rowsOmitted").asBoolean());
        assertTrue(o.path("completenessNote").asText().startsWith("This result is a sample of " + kept.size() + " of "));
        if (kept.size() < all.size()) {
            ObjectNode plusOne = (ObjectNode) o.deepCopy();
            ((ArrayNode) plusOne.get(rowsKey)).add(all.get(kept.size()));
            assertTrue(ResultFixtures.write(plusOne).length() > CAP, "the next row would not fit");
        }
    }

    @Test
    void r_c_on_the_built_in_path_has_a_cache_and_the_whole_rows_that_fit() throws Exception {
        String formatted = builtIn(ResultFixtures.infotable(ResultFixtures.wideColumns(), 3, 120));
        JsonNode f = ResultFixtures.read(formatted);
        assertFalse(f.path("cacheId").asText("").isBlank(), "R25a: a cache before reduction");
        String out = egress(formatted);
        assertGreedyWholeRows(f, out, "rows");
        JsonNode o = ResultFixtures.read(out);
        assertEquals(f.path("cacheId").asText(), o.path("cacheId").asText());
        assertEquals(30, o.path("columns").size());
        assertEquals(3, o.path("rowCount").asInt());
        String note = o.path("completenessNote").asText();
        assertTrue(note.indexOf("tabulate_cached_result") >= 0
                && note.indexOf("tabulate_cached_result") < note.indexOf("fetch_cached_result"));
    }

    @Test
    void the_formatter_reserve_decides_the_cache_near_the_cap() throws Exception {
        assertTrue(cacheAt(CAP - 1_000), "cap - 1,000 + 1,536 reserve exceeds the cap");
        assertFalse(cacheAt(CAP - 2_000), "cap - 2,000 + 1,536 fits");
    }

    /** Builds a one-column table whose formatter output is within 60 characters of {@code target}. */
    private static boolean cacheAt(int target) throws Exception {
        int cell = 100;
        String formatted = builtIn(ResultFixtures.infotable(List.of("v"), 10, cell));
        int perCell = 10;
        cell = cell + (target - formatted.length()) / perCell;
        formatted = builtIn(ResultFixtures.infotable(List.of("v"), 10, cell));
        JsonNode f = ResultFixtures.read(formatted);
        int size = formatted.length() - (f.has("cacheId") ? (",\"cacheId\":\"" + f.path("cacheId").asText() + "\"").length() : 0);
        assertTrue(Math.abs(size - target) <= 60, "table size " + size + " near " + target);
        return f.has("cacheId");
    }

    @Test
    void a_table_without_a_cache_that_must_drop_rows_says_recovery_unavailable() {
        String raw = ResultFixtures.wideTable();
        String out = egress(raw);
        JsonNode o = ResultFixtures.read(out);
        assertGreedyWholeRows(ResultFixtures.read(raw), out, "rows");
        assertEquals("unavailable", o.path("recovery").asText());
        assertFalse(o.has("cacheId"));
        assertFalse(o.path("completenessNote").asText().contains("cache"), "never points at a missing cache");
    }

    @Test
    void r_h_keeps_the_whole_rows_that_fit_with_its_cache_and_the_note() {
        String raw = ResultFixtures.longWideCellTable(ResultFixtures.FIXED_CACHE_ID);
        String out = egress(raw);
        assertGreedyWholeRows(ResultFixtures.read(raw), out, "sampleRows");
        JsonNode o = ResultFixtures.read(out);
        assertEquals(ResultFixtures.FIXED_CACHE_ID, o.path("cacheId").asText());
        assertEquals(40, o.path("totalRows").asInt());
        assertTrue(o.path("returnedRows").asInt() > 0);
    }

    @Test
    void recovery_is_exercised_an_omitted_row_is_read_by_cache_id() throws Exception {
        String formatted = builtIn(ResultFixtures.longShortInfotable());
        JsonNode f = ResultFixtures.read(formatted);
        assertEquals("INFOTABLE_LARGE", f.path("resultKind").asText());
        assertEquals(20, f.path("sampleRows").size(), "row-33 did not arrive");
        String cacheId = f.path("cacheId").asText();
        String page = InvokeServiceExecutor.executeFetchCachedResult(new ToolCall("fetch-1", "fetch_cached_result",
                "{\"cacheId\":\"" + cacheId + "\",\"offset\":32,\"limit\":1}"));
        assertEquals("row-33", ResultFixtures.read(page).path("rows").get(0).path("id").asText(), page);
    }

    @Test
    void recovery_by_projection_returns_every_row_with_only_the_named_columns() throws Exception {
        String formatted = builtIn(ResultFixtures.infotable(ResultFixtures.wideColumns(), 3, 120));
        String cacheId = ResultFixtures.read(formatted).path("cacheId").asText();
        String args = "{\"cacheId\":\"" + cacheId + "\",\"mode\":\"filter_sort_topn\","
                + "\"sorts\":[{\"fieldName\":\"col01\",\"isAscending\":true}],\"fields\":[\"col01\",\"col02\"]}";
        String tab = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("tab-1", "tabulate_cached_result", args));
        JsonNode t = ResultFixtures.read(tab);
        JsonNode rows = t.has("rows") ? t.path("rows") : t.path("sampleRows");
        assertEquals(3, rows.size(), tab);
        for (JsonNode r : rows) {
            assertEquals(2, r.size(), "only the projected columns: " + r);
            assertTrue(r.has("col01") && r.has("col02"));
        }
    }

    @Test
    void a_cache_that_cannot_be_prepared_is_reported_not_promised() throws Exception {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.HookedCache hooked =
                com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshHookedInMemoryCache();
        hooked.onNextCreates(() -> {
            throw new IllegalStateException("synthetic cache failure");
        });
        String formatted = builtIn(ResultFixtures.infotable(ResultFixtures.wideColumns(), 3, 120));
        assertFalse(ResultFixtures.read(formatted).has("cacheId"));
        JsonNode o = ResultFixtures.read(egress(formatted));
        assertEquals("unavailable", o.path("recovery").asText());
        assertFalse(o.has("cacheId"));
        assertFalse(o.path("completenessNote").asText().contains("cache"));
    }

    @Test
    void a_table_under_the_cap_is_unchanged() throws Exception {
        String formatted = builtIn(ResultFixtures.infotable(List.of("a", "b", "c", "d"), 3, 20));
        assertFalse(ResultFixtures.read(formatted).has("cacheId"));
        assertEquals(formatted, egress(formatted));
    }
}
