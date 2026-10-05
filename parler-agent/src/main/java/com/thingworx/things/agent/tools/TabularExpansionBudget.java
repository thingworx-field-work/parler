package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.execution.BudgetVector;
import com.thingworx.things.agent.execution.RunInvocationContext;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.TagCollection;
import com.thingworx.types.TagLink;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.structs.Location;

import org.joda.time.DateTime;

import java.util.ArrayList;
import java.util.List;

/**
 * One shared volume and time budget for an operation that builds a new cached table (JSON root-table
 * promotion, {@code union_rows}). It bounds the bytes the cache codec ({@code TabularInfotableCodec}) will write
 * <b>before</b> anything is built, copied or encoded. The codec builds a JSON tree, a string and a byte array
 * before the artifact writer checks its own limit, so the writer's refusal comes after the allocation; this
 * bound comes before it.
 * <p>
 * The figure is an <b>upper bound</b> of the codec output, not an estimate: every column name is written on
 * every row, a missing value is written as {@code null}, and every string is charged at its JSON-escaped size
 * (a control character is six bytes). {@code TabularExpansionBudgetCodecBoundTest} holds the bound against the
 * real codec. A value whose encoding cannot be bounded refuses the operation.
 * <p>
 * The limits are the existing {@link BudgetVector} storage and wall-time values of the current invocation;
 * no smaller number is introduced here. The budget is created once per operation and never reset per input.
 */
public final class TabularExpansionBudget {

    /** {@code {"v":1,"fields":[],"rows":[]}} with slack. */
    static final int TABLE_HEADER_BYTES = 40;
    /** {@code {"name":"","baseType":"","localFields":},} around an escaped name; base type names are short. */
    static final int FIELD_HEADER_BYTES = 72;
    /** {@code {},} around one row. */
    static final int ROW_BYTES = 3;
    /** Two quotes, a colon and a comma around one escaped cell name. */
    static final int CELL_NAME_OVERHEAD_BYTES = 4;
    /** Longest JSON number the codec writes: a double such as {@code -1.7976931348623157E308}, or a long. */
    static final int NUMBER_BYTES = 26;
    static final int NULL_BYTES = 4;
    /** {@code false}, or {@code "false"} in a STRING column. */
    static final int BOOLEAN_BYTES = 7;
    static final int LONG_BYTES = 22;
    /** {@code {"latitude":,"longitude":,"elevation":}} plus three numbers. */
    static final int LOCATION_BYTES = 44 + 3 * NUMBER_BYTES;
    /** {@code {"vocabulary":"","vocabularyTerm":""},} around two escaped strings. */
    static final int TAG_LINK_OVERHEAD_BYTES = 44;
    /** Rows between two wall-time checks inside a loop. */
    static final int TIME_CHECK_ROW_INTERVAL = 1024;
    private static final int MAX_NESTED_DEPTH = 4;

    private final long maxBytes;
    private final long deadlineNanos;
    private long bytes;

    private TabularExpansionBudget(long maxBytes, long maxWallTimeMillis) {
        this.maxBytes = maxBytes;
        this.deadlineNanos = System.nanoTime() + maxWallTimeMillis * 1_000_000L;
    }

    static TabularExpansionBudget start() {
        RunInvocationContext inv = AgentToolContext.getRunInvocationContext();
        BudgetVector budget = inv != null ? inv.budget() : BudgetVector.defaultsForTabular();
        return new TabularExpansionBudget(budget.toArtifactIoLimits().maxBytes(), budget.maxWallTimeMillis());
    }

    public static TabularExpansionBudget forTests(long maxBytes, long maxWallTimeMillis) {
        return new TabularExpansionBudget(maxBytes, maxWallTimeMillis);
    }

    public long bytes() {
        return bytes;
    }

    long maxBytes() {
        return maxBytes;
    }

    /** @return {@code false} once the running total exceeds the byte limit */
    boolean add(long more) {
        bytes = more > Long.MAX_VALUE - bytes ? Long.MAX_VALUE : bytes + more;
        return bytes <= maxBytes;
    }

    /** The one deadline of the whole operation; callers re-check it at every boundary, not only while measuring. */
    public boolean timeExceeded() {
        return System.nanoTime() - deadlineNanos > 0;
    }

    /**
     * Charges a JSON root table as {@code infoTableFromJsonRows} will build it: the columns are row 0's fields,
     * a field absent from a row is a {@code null} cell, a field that is not in row 0 is dropped.
     *
     * @return {@code false} when the byte or the time limit is exceeded
     */
    public boolean chargeJsonRows(JsonNode rows, List<String> columnNames) {
        if (timeExceeded() || !add(TABLE_HEADER_BYTES)) {
            return false;
        }
        long[] cellNameBytes = new long[columnNames.size()];
        long namesPerRow = ROW_BYTES;
        for (int c = 0; c < cellNameBytes.length; c++) {
            long escaped = escapedJsonLength(columnNames.get(c));
            cellNameBytes[c] = escaped + CELL_NAME_OVERHEAD_BYTES;
            namesPerRow += cellNameBytes[c];
            if (!add(escaped + FIELD_HEADER_BYTES)) {
                return false;
            }
        }
        int rowCount = rows.size();
        if (rowCount > maxBytes / namesPerRow || !add(namesPerRow * rowCount)) {
            return false;
        }
        for (int i = 0; i < rowCount; i++) {
            if (i % TIME_CHECK_ROW_INTERVAL == 0 && timeExceeded()) {
                return false;
            }
            JsonNode row = rows.get(i);
            for (String col : columnNames) {
                JsonNode value = row == null ? null : row.get(col);
                if (!add(jsonValueBytes(value))) {
                    return false;
                }
            }
        }
        return !timeExceeded();
    }

    /**
     * Charges one InfoTable as the codec will write it, plus what the operation adds to every row.
     *
     * @param extraBytesPerRow bytes the operation adds to every row (for a union: the label cell, see
     *                         {@link #labelCellBytes})
     * @return {@code false} when the byte or the time limit is exceeded, or a value cannot be bounded
     */
    public boolean chargeInfoTable(InfoTable table, long extraBytesPerRow) {
        return chargeInfoTable(table, extraBytesPerRow, 1);
    }

    private boolean chargeInfoTable(InfoTable table, long extraBytesPerRow, int depth) {
        if (depth > MAX_NESTED_DEPTH || timeExceeded() || !add(TABLE_HEADER_BYTES)) {
            return false;
        }
        List<String> columnNames = new ArrayList<>();
        List<BaseTypes> columnTypes = new ArrayList<>();
        long namesPerRow = ROW_BYTES + extraBytesPerRow;
        DataShapeDefinition shape = table == null ? null : table.getDataShape();
        if (shape != null && shape.getFields() != null) {
            for (FieldDefinition fd : shape.getFields().values()) {
                if (fd == null || fd.getName() == null) {
                    continue;
                }
                long escaped = escapedJsonLength(fd.getName());
                columnNames.add(fd.getName());
                columnTypes.add(fd.getBaseType() == null ? BaseTypes.STRING : fd.getBaseType());
                namesPerRow += escaped + CELL_NAME_OVERHEAD_BYTES;
                if (!add(escaped + FIELD_HEADER_BYTES) || !chargeLocalShape(fd.getLocalDataShape(), depth + 1)) {
                    return false;
                }
            }
        }
        int rowCount = table == null || table.getRowCount() == null ? 0 : table.getRowCount();
        if (rowCount > maxBytes / namesPerRow || !add(namesPerRow * rowCount)) {
            return false;
        }
        for (int i = 0; i < rowCount; i++) {
            if (i % TIME_CHECK_ROW_INTERVAL == 0 && timeExceeded()) {
                return false;
            }
            ValueCollection row = table.getRow(i);
            for (int c = 0; c < columnNames.size(); c++) {
                IPrimitiveType<?, ?> prim = row == null ? null : row.getPrimitive(columnNames.get(c));
                Object value = prim == null ? null : prim.getValue();
                if (!chargeValue(value, columnTypes.get(c), depth)) {
                    return false;
                }
            }
        }
        return !timeExceeded();
    }

    private boolean chargeLocalShape(DataShapeDefinition local, int depth) {
        if (local == null || local.getFields() == null) {
            return true;
        }
        if (depth > MAX_NESTED_DEPTH) {
            return false;
        }
        for (FieldDefinition fd : local.getFields().values()) {
            if (fd == null || fd.getName() == null) {
                continue;
            }
            if (!add(escapedJsonLength(fd.getName()) + FIELD_HEADER_BYTES)
                    || !chargeLocalShape(fd.getLocalDataShape(), depth + 1)) {
                return false;
            }
        }
        return true;
    }

    /** Mirrors {@code TabularInfotableCodec.putTyped}: what it writes depends on the column type and the value. */
    private boolean chargeValue(Object value, BaseTypes columnType, int depth) {
        if (value == null) {
            return add(NULL_BYTES);
        }
        if (value instanceof InfoTable) {
            return chargeInfoTable((InfoTable) value, 0, depth + 1);
        }
        if (value instanceof Boolean) {
            return add(BOOLEAN_BYTES);
        }
        if (value instanceof Number) {
            return add(numberBytes(((Number) value).doubleValue()));
        }
        if (value instanceof DateTime) {
            // Epoch millis as a long, or the ISO text when the column is not DATETIME.
            return add(Math.max(LONG_BYTES, escapedJsonLength(String.valueOf(value)) + 2));
        }
        if (value instanceof TagCollection) {
            long total = 2;
            for (TagLink link : (TagCollection) value) {
                if (link != null) {
                    total += TAG_LINK_OVERHEAD_BYTES + escapedJsonLength(link.getVocabulary())
                            + escapedJsonLength(link.getVocabularyTerm());
                }
            }
            return add(total);
        }
        long text = escapedJsonLength(String.valueOf(value)) + 2;
        if (value instanceof Location) {
            return add(Math.max(text, LOCATION_BYTES));
        }
        // Text in a numeric column is parsed and written as a double, which can be longer than the text.
        boolean numericColumn = columnType == BaseTypes.NUMBER || columnType == BaseTypes.INTEGER
                || columnType == BaseTypes.LONG;
        return add(numericColumn ? Math.max(text, NUMBER_BYTES) : text);
    }

    /** Bytes of a double as the codec's JSON writer prints it ({@link Double#toString}), with slack. */
    private static long numberBytes(double d) {
        return Double.toString(d).length() + 2L;
    }

    /** Bytes one union label cell adds to every row: escaped name and value, quotes, colon and comma. */
    public static long labelCellBytes(String labelColumn, String labelValue) {
        return escapedJsonLength(labelColumn) + CELL_NAME_OVERHEAD_BYTES + escapedJsonLength(labelValue) + 2;
    }

    /** Header bytes the label column adds once per union. */
    public static long labelFieldHeaderBytes(String labelColumn) {
        return escapedJsonLength(labelColumn) + FIELD_HEADER_BYTES;
    }

    /**
     * Mirrors {@code ParlerTabularChartBuilder.infoTableFromJsonRows}: any text makes the column STRING, so text is
     * written as text; a number or boolean lands in a NUMBER, BOOLEAN or STRING column, so the longest of its
     * forms is charged; a container becomes escaped text.
     */
    private static long jsonValueBytes(JsonNode value) {
        if (value == null || value.isNull()) {
            return NULL_BYTES;
        }
        if (value.isNumber()) {
            return Math.max(numberBytes(value.asDouble()), value.asText().length() + 2L);
        }
        if (value.isBoolean()) {
            return BOOLEAN_BYTES;
        }
        String text = value.isContainerNode() ? value.toString() : value.asText();
        return escapedJsonLength(text) + 2;
    }

    /**
     * Upper bound of the UTF-8 bytes of {@code s} as a JSON string body: control characters as {@code \\u00XX}
     * (six bytes), quote, backslash and solidus as two, everything else at its UTF-8 size.
     */
    public static long escapedJsonLength(String s) {
        if (s == null) {
            return 0;
        }
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f || c == 0x2028 || c == 0x2029) {
                n += 6;
            } else if (c == '"' || c == '\\' || c == '/') {
                n += 2;
            } else if (c < 0x80) {
                n += 1;
            } else if (c < 0x800) {
                n += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < s.length()
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                n += 4;
                i++;
            } else if (Character.isSurrogate(c)) {
                n += 6;
            } else {
                n += 3;
            }
        }
        return n;
    }
}
