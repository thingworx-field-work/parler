package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

public class AlertQueryFilterBuilderTest {

    @Test
    public void summaryTypedOnly() throws Exception {
        JSONObject q = AlertQueryFilterBuilder.buildSummaryQuery("HiTemp", "Above", 2, 8, null);
        assertNotNull(q);
        assertTrue(q.has("filters"));
        JSONObject f = q.getJSONObject("filters");
        assertEquals("AND", f.getString("type"));
        assertEquals(4, f.getJSONArray("filters").length());
    }

    @Test
    public void historyPropertyEq() throws Exception {
        JSONObject q = AlertQueryFilterBuilder.buildHistoryQuery(null, "Temperature", null, null, null, null);
        assertNotNull(q);
        JSONObject inner = q.getJSONObject("filters");
        assertEquals("EQ", inner.getString("type"));
        assertEquals("sourceProperty", inner.getString("fieldName"));
        assertEquals("Temperature", inner.getString("value"));
    }

    @Test
    public void mergeAdvancedFiltersAndSorts() throws Exception {
        String adv = "{\"filters\":{\"type\":\"GT\",\"fieldName\":\"priority\",\"value\":0},\"sorts\":[{\"fieldName\":\"timestamp\",\"isAscending\":false}]}";
        JSONObject q = AlertQueryFilterBuilder.buildHistoryQuery("A", null, null, null, null, adv);
        assertNotNull(q);
        assertTrue(q.has("sorts"));
        JSONObject filters = q.getJSONObject("filters");
        assertEquals("AND", filters.getString("type"));
        assertEquals(2, filters.getJSONArray("filters").length());
    }

    @Test
    public void emptyTypedNoAdvanced() throws Exception {
        assertNull(AlertQueryFilterBuilder.buildSummaryQuery(null, null, null, null, null));
    }

    @Test
    public void sortsOnlyFromAdvanced() throws Exception {
        String adv = "{\"sorts\":[{\"fieldName\":\"timestamp\",\"isAscending\":true}]}";
        JSONObject q = AlertQueryFilterBuilder.buildSummaryQuery(null, null, null, null, adv);
        assertNotNull(q);
        assertFalse(q.has("filters"));
        assertTrue(q.has("sorts"));
    }

    @Test
    public void summarySortTimestampAscWithoutFilters() throws Exception {
        JSONObject q = AlertQueryFilterBuilder.buildSummaryQuery(null, null, null, null, null, "timestamp_asc");
        assertNotNull(q);
        assertFalse(q.has("filters"));
        assertEquals("timestamp", q.getJSONArray("sorts").getJSONObject(0).getString("fieldName"));
        assertTrue(q.getJSONArray("sorts").getJSONObject(0).getBoolean("isAscending"));
    }

    @Test
    public void summarySortMergedWithTypedFilters() throws Exception {
        JSONObject q = AlertQueryFilterBuilder.buildSummaryQuery("A", null, null, null, null, "priority_desc");
        assertTrue(q.has("filters"));
        assertEquals("priority", q.getJSONArray("sorts").getJSONObject(0).getString("fieldName"));
        assertFalse(q.getJSONArray("sorts").getJSONObject(0).getBoolean("isAscending"));
    }

    @Test
    public void summarySortConflictsWithAdvancedSorts() {
        String adv = "{\"sorts\":[{\"fieldName\":\"timestamp\",\"isAscending\":false}]}";
        assertThrows(IllegalArgumentException.class,
                () -> AlertQueryFilterBuilder.buildSummaryQuery(null, null, null, null, adv, "timestamp_asc"));
    }

    @Test
    public void unknownSummarySortThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> AlertQueryFilterBuilder.buildSummaryQuery(null, null, null, null, null, "by_name"));
    }
}
