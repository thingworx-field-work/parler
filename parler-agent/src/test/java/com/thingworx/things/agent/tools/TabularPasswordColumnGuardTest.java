package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.BaseTypes;

/** Offline tests for {@link TabularPasswordColumnGuard} (no {@link com.thingworx.types.InfoTable}). */
class TabularPasswordColumnGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static void addField(DataShapeDefinition dsd, String name, BaseTypes bt, int ordinal) {
        FieldDefinition fd = new FieldDefinition();
        fd.setName(name);
        fd.setBaseType(bt);
        fd.setOrdinal(ordinal);
        dsd.addFieldDefinition(fd);
    }

    private static DataShapeDefinition shapeWithSecret() {
        DataShapeDefinition dsd = new DataShapeDefinition();
        addField(dsd, "label", BaseTypes.STRING, 0);
        addField(dsd, "score", BaseTypes.NUMBER, 1);
        addField(dsd, "secretCol", BaseTypes.PASSWORD, 2);
        return dsd;
    }

    @Test
    void tabulate_sortBy_secret_is_violation() {
        DataShapeDefinition ds = shapeWithSecret();
        ObjectNode root = MAPPER.createObjectNode();
        root.put("sortBy", "secretCol");
        TabularPasswordColumnGuard.Violation v = TabularPasswordColumnGuard.tabulate(ds, root, "sort_topn");
        assertNotNull(v);
        assertEquals("sortBy", v.field);
        assertEquals("secretCol", v.columnName);
    }

    @Test
    void tabulate_groupBy_secret_is_violation() {
        DataShapeDefinition ds = shapeWithSecret();
        ObjectNode root = MAPPER.createObjectNode();
        root.put("groupBy", "secretCol");
        TabularPasswordColumnGuard.Violation v = TabularPasswordColumnGuard.tabulate(ds, root, "group_count");
        assertNotNull(v);
        assertEquals("groupBy", v.field);
        assertEquals("secretCol", v.columnName);
    }

    @Test
    void tabulate_aggregateSecret_sum_is_violation() {
        DataShapeDefinition ds = shapeWithSecret();
        ObjectNode root = MAPPER.createObjectNode();
        root.put("groupBy", "label");
        root.put("aggregateColumn", "secretCol");
        root.put("fn", "sum");
        TabularPasswordColumnGuard.Violation v = TabularPasswordColumnGuard.tabulate(ds, root, "group_aggregate");
        assertNotNull(v);
        assertEquals("aggregateColumn", v.field);
    }

    @Test
    void tabulate_aggregateSecret_count_skipped() throws Exception {
        DataShapeDefinition ds = shapeWithSecret();
        ObjectNode root = MAPPER.createObjectNode();
        root.put("groupBy", "label");
        root.put("aggregateColumn", "secretCol");
        root.put("fn", "count");
        assertNull(TabularPasswordColumnGuard.tabulate(ds, root, "group_aggregate"));
    }

    @Test
    void percentile_explicit_secret_is_violation() {
        DataShapeDefinition ds = shapeWithSecret();
        TabularPasswordColumnGuard.Violation v = TabularPasswordColumnGuard.percentileExplicit(ds,
                java.util.List.of("secretCol"));
        assertNotNull(v);
        assertEquals("percentileColumns", v.field);
    }

    @Test
    void chart_x_secret_is_violation() {
        DataShapeDefinition ds = shapeWithSecret();
        TabularPasswordColumnGuard.Violation v = TabularPasswordColumnGuard.chartAxes(ds, "secretCol", "score",
                java.util.Collections.emptyList());
        assertNotNull(v);
        assertEquals("xColumn", v.field);
    }

    @Test
    void chart_y_secret_is_violation() {
        DataShapeDefinition ds = shapeWithSecret();
        TabularPasswordColumnGuard.Violation v = TabularPasswordColumnGuard.chartAxes(ds, "label", "secretCol",
                java.util.Collections.emptyList());
        assertNotNull(v);
        assertEquals("yColumn", v.field);
    }

    @Test
    void chart_series_y_secret_is_violation() {
        DataShapeDefinition ds = shapeWithSecret();
        TabularPasswordColumnGuard.Violation v = TabularPasswordColumnGuard.chartAxes(ds, "label", null,
                java.util.List.of("secretCol"));
        assertNotNull(v);
        assertEquals("series.yColumn", v.field);
    }

    @Test
    void tabulate_decision_legacy_column_key_in_filters_detects_password() {
        DataShapeDefinition ds = shapeWithSecret();
        ObjectNode root = MAPPER.createObjectNode();
        root.put("mode", "filter_count");
        ObjectNode filters = root.putObject("filters");
        filters.put("op", "eq");
        filters.put("column", "secretCol");
        filters.put("value", "x");
        TabularPasswordColumnGuard.Violation v = TabularPasswordColumnGuard.tabulateDecision(ds, root, "filter_count");
        assertNotNull(v);
        assertEquals("filters", v.field);
        assertEquals("secretCol", v.columnName);
    }

    @Test
    void tabulate_group_metric_measure_weightColumn_secret_is_violation() {
        DataShapeDefinition ds = shapeWithSecret();
        ObjectNode root = MAPPER.createObjectNode();
        root.putArray("groupBy");
        ArrayNode measures = root.putArray("measures");
        ObjectNode m = measures.addObject();
        m.put("name", "wa");
        m.put("op", "weighted_avg");
        m.put("column", "score");
        m.put("weightColumn", "secretCol");
        TabularPasswordColumnGuard.Violation v = TabularPasswordColumnGuard.tabulateDecision(ds, root, "group_metric");
        assertNotNull(v);
        assertEquals("measures.weightColumn", v.field);
        assertEquals("secretCol", v.columnName);
    }

    @Test
    void tabulate_group_metric_measure_orderBy_secret_is_violation() {
        DataShapeDefinition ds = shapeWithSecret();
        ObjectNode root = MAPPER.createObjectNode();
        root.putArray("groupBy");
        ArrayNode measures = root.putArray("measures");
        ObjectNode m = measures.addObject();
        m.put("name", "fv");
        m.put("op", "first");
        m.put("column", "score");
        m.put("orderBy", "secretCol");
        TabularPasswordColumnGuard.Violation v = TabularPasswordColumnGuard.tabulateDecision(ds, root, "group_metric");
        assertNotNull(v);
        assertEquals("measures.orderBy", v.field);
        assertEquals("secretCol", v.columnName);
    }
}
