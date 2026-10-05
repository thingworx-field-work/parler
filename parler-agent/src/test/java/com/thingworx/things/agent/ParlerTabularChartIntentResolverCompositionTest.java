package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/** Regression: `intent: composition` stays mapped to grouped `bar` with descending sort flag (prompt-to-chart). */
class ParlerTabularChartIntentResolverCompositionTest {

    @Test
    void composition_two_rows_resolves_to_bar_with_desc_sort() {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fx = new FieldDefinition();
        fx.setName("cat");
        fx.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fx);
        FieldDefinition fy = new FieldDefinition();
        fy.setName("val");
        fy.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fy);
        InfoTable t = new InfoTable(shape);
        ValueCollection r1 = new ValueCollection();
        r1.put("cat", new StringPrimitive("a"));
        r1.put("val", new NumberPrimitive(1.0));
        t.addRow(r1);
        ValueCollection r2 = new ValueCollection();
        r2.put("cat", new StringPrimitive("b"));
        r2.put("val", new NumberPrimitive(2.0));
        t.addRow(r2);

        ParlerTabularChartIntentResolver.IntentOutcome o =
                ParlerTabularChartIntentResolver.resolve("composition", t, "cat", "val", null);
        assertFalse(o.fallback);
        assertEquals("bar", o.kind);
        assertTrue(o.sortBarPrimaryYDesc);
    }
}
