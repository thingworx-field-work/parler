package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;

class PropertyToolsExecutorPopHistoryTest {

    @Test
    void missingTimestampColumnReturnsStructuredError() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(new FieldDefinition("value", BaseTypes.NUMBER));
        InfoTable table = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("value", new NumberPrimitive(12.0));
        table.addRow(row);

        PropertyToolsExecutor.PopHistoryExtract ex =
                PropertyToolsExecutor.extractPopNumericHistoryPoints(table, "currentDraw");
        assertEquals("POP_HISTORY_TIMESTAMP_UNRESOLVED", ex.errorCode);
        assertEquals(0, ex.points.size());
    }

    @Test
    void unparseableTimestampRowSkippedNotFabricated() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(new FieldDefinition("timestamp", BaseTypes.STRING));
        shape.addFieldDefinition(new FieldDefinition("value", BaseTypes.NUMBER));
        InfoTable table = new InfoTable(shape);
        ValueCollection bad = new ValueCollection();
        bad.put("timestamp", new com.thingworx.types.primitives.StringPrimitive("not-an-instant"));
        bad.put("value", new NumberPrimitive(1.0));
        table.addRow(bad);
        ValueCollection good = new ValueCollection();
        good.put("timestamp", new com.thingworx.types.primitives.StringPrimitive("2026-06-30T14:30:00.000Z"));
        good.put("value", new NumberPrimitive(2.0));
        table.addRow(good);

        PropertyToolsExecutor.PopHistoryExtract ex =
                PropertyToolsExecutor.extractPopNumericHistoryPoints(table, "value");
        assertNull(ex.errorCode);
        assertEquals(1, ex.points.size());
        assertEquals(Instant.parse("2026-06-30T14:30:00.000Z"), ex.points.get(0).timestamp);
        assertEquals(2.0, ex.points.get(0).value, 0.001);
    }
}
