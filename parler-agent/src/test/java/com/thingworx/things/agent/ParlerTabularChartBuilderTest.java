package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

import com.thingworx.things.agent.ParlerTabularChartBuilder.BuildException;

class ParlerTabularChartBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void lineScatter_longTable_twoSeries_datetimeX_requestedTimeRange() throws Exception {
        InfoTable t = longLineTable();
        addLineRow(t, "2025-09-03T00:00:00Z", "ORD-Contacting-01", 10.0);
        addLineRow(t, "2025-09-04T00:00:00Z", "ORD-Contacting-01", 12.0);
        addLineRow(t, "2025-09-03T00:00:00Z", "ORD-Contacting-02", 8.0);
        addLineRow(t, "2025-09-04T00:00:00Z", "ORD-Contacting-02", 9.0);
        String rtr = "{\"start\":\"2025-09-03T00:00:00Z\",\"end\":\"2025-09-14T23:59:59Z\"}";
        var chart = ParlerTabularChartBuilder.buildChartBlock(t, "line", "Date", "RunningPct", null, null, null, null,
                null, MAPPER.readTree(rtr), false, "EquipmentID", null, -1);
        assertEquals("line", chart.getString("kind"));
        assertEquals(2, chart.getJSONArray("series").length());
        assertEquals("ORD-Contacting-01", chart.getJSONArray("series").getJSONObject(0).getString("name"));
        assertEquals("ORD-Contacting-02", chart.getJSONArray("series").getJSONObject(1).getString("name"));
        assertTrue(chart.has("requested_time_range"));
        assertEquals("long_table_pivot(seriesColumn)",
                chart.getJSONObject("source").getString("transformSummary"));
        assertEquals(4, chart.getJSONObject("source").getInt("pointCount"));
    }

    @Test
    void lineScatter_longTable_duplicateSeriesCategory_throws() {
        InfoTable t = longLineTable();
        addLineRow(t, "2025-09-03T00:00:00Z", "ORD-Contacting-01", 10.0);
        addLineRow(t, "2025-09-03T00:00:00Z", "ORD-Contacting-01", 11.0);
        BuildException ex = assertThrows(BuildException.class,
                () -> ParlerTabularChartBuilder.buildChartBlock(t, "line", "Date", "RunningPct", null, null, null, null,
                        null, null, false, "EquipmentID", null, -1));
        assertEquals("DUPLICATE_SERIES_CATEGORY", ex.code);
    }

    @Test
    void lineScatter_longTable_tooManySeries_throws() {
        InfoTable t = longLineTable();
        for (int s = 0; s < 7; s++) {
            addLineRow(t, "2025-09-03T00:00:00Z", "S" + s, (double) s);
        }
        BuildException ex = assertThrows(BuildException.class,
                () -> ParlerTabularChartBuilder.buildChartBlock(t, "line", "Date", "RunningPct", null, null, null, null,
                        null, null, false, "EquipmentID", null, -1));
        assertEquals("TOO_MANY_SERIES", ex.code);
    }

    @Test
    void scatter_longTable_sparsePerSeries_noZeroFill() throws Exception {
        InfoTable t = longLineTable();
        addLineRow(t, "2025-09-03T00:00:00Z", "A", 1.0);
        addLineRow(t, "2025-09-04T00:00:00Z", "A", 2.0);
        addLineRow(t, "2025-09-03T00:00:00Z", "B", 3.0);
        var chart = ParlerTabularChartBuilder.buildChartBlock(t, "scatter", "Date", "RunningPct", null, null, null,
                null, null, null, false, "EquipmentID", null, -1);
        assertEquals("scatter", chart.getString("kind"));
        var seriesB = chart.getJSONArray("series").getJSONObject(1);
        assertEquals(1, seriesB.getJSONArray("x").length());
        assertEquals(1, seriesB.getJSONArray("y").length());
    }

    @Test
    void groupedBar_longTable_duplicateSeriesCategory_sameY_throws() {
        InfoTable t = longBarTable();
        ValueCollection r1 = new ValueCollection();
        r1.put("cat", new StringPrimitive("A"));
        r1.put("ser", new StringPrimitive("S1"));
        r1.put("y", new NumberPrimitive(5.0));
        t.addRow(r1);
        ValueCollection r2 = new ValueCollection();
        r2.put("cat", new StringPrimitive("A"));
        r2.put("ser", new StringPrimitive("S1"));
        r2.put("y", new NumberPrimitive(5.0));
        t.addRow(r2);
        BuildException ex = assertThrows(BuildException.class,
                () -> ParlerTabularChartBuilder.buildChartBlock(t, "bar", "cat", "y", null, null, null, null, null, null,
                        false, "ser", null, -1));
        assertEquals("DUPLICATE_SERIES_CATEGORY", ex.code);
    }

    @Test
    void groupedBar_longTable_duplicateSeriesCategory_differentY_throws() {
        InfoTable t = longBarTable();
        ValueCollection r1 = new ValueCollection();
        r1.put("cat", new StringPrimitive("A"));
        r1.put("ser", new StringPrimitive("S1"));
        r1.put("y", new NumberPrimitive(5.0));
        t.addRow(r1);
        ValueCollection r2 = new ValueCollection();
        r2.put("cat", new StringPrimitive("A"));
        r2.put("ser", new StringPrimitive("S1"));
        r2.put("y", new NumberPrimitive(6.0));
        t.addRow(r2);
        BuildException ex = assertThrows(BuildException.class,
                () -> ParlerTabularChartBuilder.buildChartBlock(t, "bar", "cat", "y", null, null, null, null, null, null,
                        false, "ser", null, -1));
        assertEquals("DUPLICATE_SERIES_CATEGORY", ex.code);
    }

    @Test
    void horizontalBar_wideTable_emitsOrientationOnlyWhenHorizontal_andKeepsRankTieOrder() throws Exception {
        InfoTable t = wideBarTable();
        addWideRow(t, "gate-3", 5.0);
        addWideRow(t, "gate-1", 9.0);
        addWideRow(t, "gate-2", 9.0);
        addWideRow(t, "gate-4", 1.0);
        var horizontal = ParlerTabularChartBuilder.buildChartBlock(t, "bar", "cat", "y", null, null, null, null,
                null, null, true, null, null, -1, "horizontal");
        assertEquals("bar", horizontal.getString("kind"));
        assertEquals("horizontal", horizontal.getString("orientation"));
        var xs = horizontal.getJSONArray("series").getJSONObject(0).getJSONArray("x");
        // HB-4: stable descending sort keeps source order for ties; the first category is the top row.
        assertEquals("gate-1", xs.getString(0));
        assertEquals("gate-2", xs.getString(1));
        assertEquals("gate-3", xs.getString(2));
        assertEquals("gate-4", xs.getString(3));
        var vertical = ParlerTabularChartBuilder.buildChartBlock(t, "bar", "cat", "y", null, null, null, null,
                null, null, true, null, null, -1, "vertical");
        assertTrue(!vertical.has("orientation"), "vertical payloads stay byte-identical: no orientation key");
        var absent = ParlerTabularChartBuilder.buildChartBlock(t, "bar", "cat", "y", null, null, null, null,
                null, null, true, null, null, -1, null);
        assertEquals(vertical.toString(), absent.toString());
        assertEquals(horizontal.getJSONArray("series").toString(), vertical.getJSONArray("series").toString(),
                "orientation never changes the series data");
    }

    @Test
    void horizontalBar_longTable_emitsOrientation() throws Exception {
        InfoTable t = longBarTable();
        addLongRow(t, "A", "s1", 1.0);
        addLongRow(t, "A", "s2", 2.0);
        addLongRow(t, "B", "s1", 3.0);
        var chart = ParlerTabularChartBuilder.buildChartBlock(t, "bar", "cat", "y", null, null, null, null,
                null, null, false, "ser", null, -1, "horizontal");
        assertEquals("horizontal", chart.getString("orientation"));
        assertEquals("grouped_bar(seriesColumn)", chart.getJSONObject("source").getString("transformSummary"));
        assertEquals(1, chart.getJSONObject("source").getInt("filledMissingCombinations"));
    }

    @Test
    void stackMode_writtenOnlyWhenStackedOrPercent_andRejectionsMatchTheDesign() throws Exception {
        InfoTable t = longBarTable();
        addLongRow(t, "A", "s1", 1.0);
        addLongRow(t, "A", "s2", 2.0);
        addLongRow(t, "B", "s1", 3.0);
        addLongRow(t, "B", "s2", -1.0);
        var grouped = ParlerTabularChartBuilder.buildChartBlock(t, "bar", "cat", "y", null, null, null, null,
                null, null, false, "ser", null, -1, null, null);
        var explicitGrouped = ParlerTabularChartBuilder.buildChartBlock(t, "bar", "cat", "y", null, null, null, null,
                null, null, false, "ser", null, -1, null, "grouped");
        assertTrue(!grouped.has("stackMode"), "grouped is the default and is never written");
        assertEquals(grouped.toString(), explicitGrouped.toString(), "an explicit grouped payload is byte-identical");
        var stacked = ParlerTabularChartBuilder.buildChartBlock(t, "bar", "cat", "y", null, null, null, null,
                null, null, false, "ser", null, -1, "horizontal", "stacked");
        assertEquals("stacked", stacked.getString("stackMode"));
        assertEquals("horizontal", stacked.getString("orientation"), "stackMode combines with orientation");
        assertEquals(grouped.getJSONArray("series").toString(), stacked.getJSONArray("series").toString(),
                "stacking never changes the series data");
        BuildException negative = assertThrows(BuildException.class,
                () -> ParlerTabularChartBuilder.buildChartBlock(t, "bar", "cat", "y", null, null, null, null,
                        null, null, false, "ser", null, -1, null, "percent"));
        assertEquals("STACK_PERCENT_NEGATIVE", negative.code);
        InfoTable nonNeg = longBarTable();
        addLongRow(nonNeg, "A", "s1", 1.0);
        addLongRow(nonNeg, "A", "s2", 0.0);
        var percent = ParlerTabularChartBuilder.buildChartBlock(nonNeg, "bar", "cat", "y", null, null, null, null,
                null, null, false, "ser", null, -1, null, "percent");
        assertEquals("percent", percent.getString("stackMode"));
        InfoTable wide = wideBarTable();
        addWideRow(wide, "1", 1.0);
        addWideRow(wide, "2", 2.0);
        BuildException single = assertThrows(BuildException.class,
                () -> ParlerTabularChartBuilder.buildChartBlock(wide, "bar", "cat", "y", null, null, null, null,
                        null, null, false, null, null, -1, null, "stacked"));
        assertEquals("INVALID_PARAMETERS", single.code, "a single series cannot stack");
        BuildException line = assertThrows(BuildException.class,
                () -> ParlerTabularChartBuilder.buildChartBlock(t, "line", "cat", "y", null, null, null, null,
                        null, null, false, "ser", null, -1, null, "stacked"));
        assertEquals("INVALID_PARAMETERS", line.code);
        for (String supplied : new String[]{"", " ", "Stacked", "percent ", "100%"}) {
            BuildException e = assertThrows(BuildException.class,
                    () -> ParlerTabularChartBuilder.buildChartBlock(t, "bar", "cat", "y", null, null, null, null,
                            null, null, false, "ser", null, -1, null, supplied), supplied);
            assertEquals("INVALID_PARAMETERS", e.code, supplied);
        }
    }

    @Test
    void orientation_rejectedForNonBarKindsAndUnknownValues() {
        InfoTable t = wideBarTable();
        addWideRow(t, "1", 1.0);
        addWideRow(t, "2", 2.0);
        BuildException line = assertThrows(BuildException.class,
                () -> ParlerTabularChartBuilder.buildChartBlock(t, "line", "cat", "y", null, null, null, null,
                        null, null, false, null, null, -1, "horizontal"));
        assertEquals("INVALID_PARAMETERS", line.code);
        BuildException bad = assertThrows(BuildException.class,
                () -> ParlerTabularChartBuilder.buildChartBlock(t, "bar", "cat", "y", null, null, null, null,
                        null, null, false, null, null, -1, "sideways"));
        assertEquals("INVALID_PARAMETERS", bad.code);
        for (String supplied : new String[]{"", " ", "Horizontal", " horizontal", "VERTICAL"}) {
            BuildException e = assertThrows(BuildException.class,
                    () -> ParlerTabularChartBuilder.buildChartBlock(t, "bar", "cat", "y", null, null, null, null,
                            null, null, false, null, null, -1, supplied), supplied);
            assertEquals("INVALID_PARAMETERS", e.code, "supplied value must match the enum exactly: " + supplied);
        }
    }

    @Test
    void pie_duplicateNonZeroSliceLabel_throws() {
        InfoTable t = pieTable();
        ValueCollection r1 = new ValueCollection();
        r1.put("lab", new StringPrimitive("dup"));
        r1.put("val", new NumberPrimitive(3.0));
        t.addRow(r1);
        ValueCollection r2 = new ValueCollection();
        r2.put("lab", new StringPrimitive("dup"));
        r2.put("val", new NumberPrimitive(4.0));
        t.addRow(r2);
        BuildException ex = assertThrows(BuildException.class,
                () -> ParlerTabularChartBuilder.buildChartBlock(t, "pie", "lab", "val", null, null, null, null, null,
                        null, false, null, null, -1));
        assertEquals("DUPLICATE_SLICE_LABEL", ex.code);
    }

    private static InfoTable longLineTable() {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("Date");
        fd.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fd);
        FieldDefinition fe = new FieldDefinition();
        fe.setName("EquipmentID");
        fe.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fe);
        FieldDefinition fy = new FieldDefinition();
        fy.setName("RunningPct");
        fy.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fy);
        return new InfoTable(shape);
    }

    private static void addLineRow(InfoTable t, String date, String equip, double pct) {
        ValueCollection r = new ValueCollection();
        r.put("Date", new StringPrimitive(date));
        r.put("EquipmentID", new StringPrimitive(equip));
        r.put("RunningPct", new NumberPrimitive(pct));
        t.addRow(r);
    }

    private static InfoTable longBarTable() {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fc = new FieldDefinition();
        fc.setName("cat");
        fc.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fc);
        FieldDefinition fs = new FieldDefinition();
        fs.setName("ser");
        fs.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fs);
        FieldDefinition fy = new FieldDefinition();
        fy.setName("y");
        fy.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fy);
        return new InfoTable(shape);
    }

    private static void addLongRow(InfoTable t, String cat, String ser, double y) {
        ValueCollection r = new ValueCollection();
        r.put("cat", new StringPrimitive(cat));
        r.put("ser", new StringPrimitive(ser));
        r.put("y", new NumberPrimitive(y));
        t.addRow(r);
    }

    private static InfoTable wideBarTable() {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fc = new FieldDefinition();
        fc.setName("cat");
        fc.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fc);
        FieldDefinition fy = new FieldDefinition();
        fy.setName("y");
        fy.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fy);
        return new InfoTable(shape);
    }

    private static void addWideRow(InfoTable t, String cat, double y) {
        ValueCollection r = new ValueCollection();
        r.put("cat", new StringPrimitive(cat));
        r.put("y", new NumberPrimitive(y));
        t.addRow(r);
    }

    private static InfoTable pieTable() {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fl = new FieldDefinition();
        fl.setName("lab");
        fl.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fl);
        FieldDefinition fv = new FieldDefinition();
        fv.setName("val");
        fv.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fv);
        return new InfoTable(shape);
    }
}
