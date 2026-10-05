package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.InfoTablePrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/** The strict named-VTQ read returns only what the user may read; absent properties stay absent. */
class PropertyValueReadsTest {

    @Test
    void unwrapsReadablePropertiesAndLeavesOmittedOnesAbsent() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(new FieldDefinition("Temperature", "", BaseTypes.INFOTABLE));
        InfoTable result = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("Temperature", new InfoTablePrimitive(vtq(new NumberPrimitive(71.5))));
        result.addRow(row);

        Map<String, IPrimitiveType> values = PropertyValueReads.valuesFromNamedVtqTable(result);

        assertEquals(1, values.size());
        assertEquals(71.5, values.get("Temperature").getValue());
        assertTrue(!values.containsKey("SecretSetpoint"));
    }

    @Test
    void emptyResultMeansNothingReadable() {
        assertTrue(PropertyValueReads.valuesFromNamedVtqTable(new InfoTable()).isEmpty());
        assertTrue(PropertyValueReads.valuesFromNamedVtqTable(null).isEmpty());
    }

    private static InfoTable vtq(IPrimitiveType value) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(new FieldDefinition("value", "", value.getBaseType()));
        shape.addFieldDefinition(new FieldDefinition("time", "", BaseTypes.DATETIME));
        shape.addFieldDefinition(new FieldDefinition("quality", "", BaseTypes.STRING));
        InfoTable table = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("value", value);
        row.put("time", new DatetimePrimitive(new DateTime(0L)));
        row.put("quality", new StringPrimitive("GOOD"));
        table.addRow(row);
        return table;
    }
}
