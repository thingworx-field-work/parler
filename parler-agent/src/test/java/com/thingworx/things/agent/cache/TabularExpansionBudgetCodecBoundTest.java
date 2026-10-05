package com.thingworx.things.agent.cache;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.ParlerTabularChartBuilder;
import com.thingworx.things.agent.tools.TabularExpansionBudget;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.InfoTablePrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * The expansion budget must be an upper bound of what {@link TabularInfotableCodec} really writes; otherwise the
 * pre-check passes and the codec allocates the oversized tree, string and byte array before the writer refuses.
 * Every case encodes a small table with the real codec and compares.
 */
class TabularExpansionBudgetCodecBoundTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String chars(int... codeUnits) {
        StringBuilder sb = new StringBuilder();
        for (int c : codeUnits) {
            sb.append((char) c);
        }
        return sb.toString();
    }

    /** Control characters, JSON escapes, multi-byte text, a surrogate pair, a lone surrogate, line separators. */
    private static final String[] NASTY = {
            "plain", "", chars(1).repeat(50), "quote\"back\\slash/solidus", "tab\tnew\nline\r",
            chars(0xFC, 0x6E, 0xEF) + " " + chars(0x4E2D, 0x6587), "emoji " + chars(0xD83D, 0xDE00),
            "lone " + chars(0xD800) + " surrogate", chars(0x2028, 0x2029, 0x7F), "<script>&'</script>"};

    private static long bound(InfoTable table, long extraPerRow) {
        TabularExpansionBudget budget = TabularExpansionBudget.forTests(Long.MAX_VALUE, 60_000);
        assertTrue(budget.chargeInfoTable(table, extraPerRow));
        return budget.bytes();
    }

    private static void addField(DataShapeDefinition shape, String name, BaseTypes bt) {
        FieldDefinition fd = new FieldDefinition();
        fd.setName(name);
        fd.setBaseType(bt);
        shape.addFieldDefinition(fd);
    }

    @Test
    void infoTableBound_coversEscapedNamesValuesNullsNumbersDatesAndNestedTables() throws Exception {
        for (String nasty : NASTY) {
            String nameSuffix = nasty.isEmpty() ? "x" : nasty;
            DataShapeDefinition inner = new DataShapeDefinition();
            addField(inner, "in" + nameSuffix, BaseTypes.STRING);
            InfoTable nested = new InfoTable(inner);
            ValueCollection nrow = new ValueCollection();
            nrow.put("in" + nameSuffix, new StringPrimitive(nasty));
            nested.addRow(nrow);

            DataShapeDefinition shape = new DataShapeDefinition();
            addField(shape, "s" + nameSuffix, BaseTypes.STRING);
            addField(shape, "n" + nameSuffix, BaseTypes.NUMBER);
            addField(shape, "b", BaseTypes.BOOLEAN);
            addField(shape, "d", BaseTypes.DATETIME);
            addField(shape, "missing" + nameSuffix, BaseTypes.STRING);
            addField(shape, "t", BaseTypes.INFOTABLE);
            InfoTable table = new InfoTable(shape);
            double[] numbers = {0, -1.7976931348623157E308, 4.9E-324, 1e21, 123456789.123456789};
            for (double number : numbers) {
                ValueCollection row = new ValueCollection();
                row.put("s" + nameSuffix, new StringPrimitive(nasty));
                row.put("n" + nameSuffix, new NumberPrimitive(number));
                row.put("b", new BooleanPrimitive(false));
                row.put("d", new DatetimePrimitive(new DateTime(Long.MAX_VALUE / 4)));
                row.put("t", new InfoTablePrimitive(nested));
                table.addRow(row);
            }
            table.addRow(new ValueCollection());
            long real = TabularInfotableCodec.encode(table).length;
            long bound = bound(table, 0);
            assertTrue(bound >= real, "bound " + bound + " < codec " + real + " for case " + nasty.length());
        }
    }

    /** The bound must not be so loose that it refuses ordinary tables the cache would store. */
    @Test
    void ordinaryTable_boundStaysCloseToTheRealEncoding() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        addField(shape, "state", BaseTypes.STRING);
        addField(shape, "hours", BaseTypes.NUMBER);
        addField(shape, "count", BaseTypes.NUMBER);
        addField(shape, "at", BaseTypes.DATETIME);
        InfoTable table = new InfoTable(shape);
        for (int i = 0; i < 500; i++) {
            ValueCollection row = new ValueCollection();
            row.put("state", new StringPrimitive(i % 2 == 0 ? "Running" : "Down"));
            row.put("hours", new NumberPrimitive(i * 0.37));
            row.put("count", new NumberPrimitive(i));
            row.put("at", new DatetimePrimitive(new DateTime(1_790_000_000_000L + i * 60_000L)));
            table.addRow(row);
        }
        long real = TabularInfotableCodec.encode(table).length;
        long bound = bound(table, 0);
        assertTrue(bound >= real, "bound " + bound + " < codec " + real);
        assertTrue(bound <= real * 3 / 2, "bound " + bound + " is more than 1.5x the codec's " + real);
    }

    @Test
    void unionLabelBound_coversAnEscapedLabelOnEveryRow() throws Exception {
        for (String nasty : NASTY) {
            String labelColumn = "day" + nasty;
            DataShapeDefinition shape = new DataShapeDefinition();
            addField(shape, "v", BaseTypes.NUMBER);
            InfoTable input = new InfoTable(shape);
            DataShapeDefinition outShape = new DataShapeDefinition();
            addField(outShape, "v", BaseTypes.NUMBER);
            addField(outShape, labelColumn, BaseTypes.STRING);
            InfoTable output = new InfoTable(outShape);
            for (int i = 0; i < 7; i++) {
                ValueCollection in = new ValueCollection();
                in.put("v", new NumberPrimitive(i));
                input.addRow(in);
                ValueCollection o = new ValueCollection();
                o.put("v", new NumberPrimitive(i));
                o.put(labelColumn, new StringPrimitive(nasty));
                output.addRow(o);
            }
            long real = TabularInfotableCodec.encode(output).length;
            long bound = TabularExpansionBudget.labelFieldHeaderBytes(labelColumn)
                    + bound(input, TabularExpansionBudget.labelCellBytes(labelColumn, nasty));
            assertTrue(bound >= real, "bound " + bound + " < codec " + real + " for case " + nasty.length());
        }
    }

    @Test
    void jsonRowsBound_coversTheTableTheBuilderMakes_includingSparseRowsAndContainers() throws Exception {
        for (String nasty : NASTY) {
            String wide = "k" + (nasty.isEmpty() ? "x" : nasty);
            ArrayNode rows = MAPPER.createArrayNode();
            ObjectNode first = rows.addObject();
            first.put(wide, nasty);
            first.put("num", 1.5e300);
            first.put("flag", true);
            first.set("obj", MAPPER.createObjectNode().put("a\"/", nasty));
            rows.addObject();
            rows.addObject().put(wide, nasty + nasty).put("num", "not a number " + nasty).putNull("flag");
            rows.addObject().put("notInRowZero", "dropped by the builder");
            List<String> columns = new ArrayList<>();
            first.fieldNames().forEachRemaining(columns::add);
            TabularExpansionBudget budget = TabularExpansionBudget.forTests(Long.MAX_VALUE, 60_000);
            assertTrue(budget.chargeJsonRows(rows, columns));
            long real = TabularInfotableCodec.encode(ParlerTabularChartBuilder.infoTableFromJsonRows(rows)).length;
            assertTrue(budget.bytes() >= real,
                    "bound " + budget.bytes() + " < codec " + real + " for case " + nasty.length());
        }
    }
}
