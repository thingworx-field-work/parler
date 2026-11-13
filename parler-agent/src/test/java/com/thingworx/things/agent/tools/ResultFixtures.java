package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * The one synthetic fixture recipe of topic round 6 (design section 6). Every value is invented; the sizes are set
 * by recipe and asserted by the tests that use them, never assumed.
 */
public final class ResultFixtures {

    public static final String TOOL = "demo-evidence-tool";
    public static final String FIXED_CACHE_ID = "demo-cache-fixture";

    private static final ObjectMapper M = new ObjectMapper();

    private ResultFixtures() {
    }

    // ---- R-A / R-A0: four records of nine fields ----

    private static ObjectNode record(int r) {
        ObjectNode rec = M.createObjectNode();
        for (int f = 1; f <= 9; f++) {
            rec.put("field" + f, value("r" + r + "-f" + f + "-", 120));
        }
        return rec;
    }

    /** R-A raw value: {@code {records:[r1..r4]}}. */
    public static ObjectNode recordsArray() {
        ObjectNode root = M.createObjectNode();
        ArrayNode arr = root.putArray("records");
        for (int r = 1; r <= 4; r++) {
            arr.add(record(r));
        }
        return root;
    }

    /** R-A0 raw value: the same data with no array, {@code {records:{r1..r4}}}. */
    public static ObjectNode recordsMap() {
        ObjectNode root = M.createObjectNode();
        ObjectNode map = root.putObject("records");
        for (int r = 1; r <= 4; r++) {
            map.set("r" + r, record(r));
        }
        return root;
    }

    /** R-B raw value: an object with an array of 30 short strings. */
    public static ObjectNode thirtyStrings() {
        ObjectNode root = M.createObjectNode();
        ArrayNode arr = root.putArray("items");
        for (int i = 1; i <= 30; i++) {
            arr.add(value("item-" + i + "-", 40));
        }
        return root;
    }

    /** {@code string} mode JSON envelope: the value serialized into one string field. */
    public static String stringEnvelope(JsonNode value) {
        ObjectNode ok = M.createObjectNode();
        ok.put("status", "success");
        ok.put("resultKind", "JSON");
        ok.put("result", write(value));
        return write(ok);
    }

    /** {@code node} mode JSON envelope: the value as a JSON object under {@code result}. */
    public static String nodeEnvelope(JsonNode value) {
        ObjectNode ok = M.createObjectNode();
        ok.put("status", "success");
        ok.put("resultKind", "JSON");
        ok.set("result", value);
        return write(ok);
    }

    // ---- R-E, R-F, R-G ----

    /** R-E: one 3,000-character text field containing {@code [}, about 3,400 characters in all. */
    public static String textObject() {
        ObjectNode root = M.createObjectNode();
        root.put("status", "success");
        root.put("resultKind", "JSON");
        root.put("summary", bracketText(3_000));
        ObjectNode meta = root.putObject("meta");
        for (int i = 1; i <= 6; i++) {
            meta.put("attribute" + i, value("meta-" + i + "-", 40));
        }
        return write(root);
    }

    /** R-F: an error envelope with a 3,000-character message containing {@code [}. */
    public static String errorEnvelope() {
        ObjectNode root = M.createObjectNode();
        root.put("status", "error");
        root.put("code", "DEMO_FAILURE");
        root.put("message", bracketText(3_000));
        return write(root);
    }

    /** R-G: plain text of 3,000 characters. */
    public static String plainText() {
        return value("Plain demo evidence text. ", 3_000);
    }

    // ---- tables (gateway-level JSON in the formatter's shape) ----

    private static ArrayNode columnsMeta(List<String> names) {
        ArrayNode cols = M.createArrayNode();
        for (String n : names) {
            ObjectNode c = cols.addObject();
            c.put("name", n);
            c.put("baseType", "STRING");
        }
        return cols;
    }

    private static List<String> names(String prefix, int count) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            out.add(String.format("%s%02d", prefix, i));
        }
        return out;
    }

    private static ObjectNode tableRow(List<String> cols, int row, int cellChars) {
        ObjectNode o = M.createObjectNode();
        for (String c : cols) {
            o.put(c, value(c + "-" + row + "-", cellChars));
        }
        return o;
    }

    /** R-C: an inline INFOTABLE of 3 rows by 30 columns, cells of about 120 characters. */
    public static String wideTable() {
        List<String> cols = names("col", 30);
        ObjectNode root = M.createObjectNode();
        root.put("status", "success");
        root.put("resultKind", "INFOTABLE");
        root.put("rowCount", 3);
        root.set("columns", columnsMeta(cols));
        ArrayNode rows = root.putArray("rows");
        for (int r = 1; r <= 3; r++) {
            rows.add(tableRow(cols, r, 120));
        }
        return write(root);
    }

    /** R-D: INFOTABLE_LARGE of 40 rows by 4 short distinct columns; {@code id} holds {@code row-<i>}. */
    public static String longShortTable(String cacheId) {
        ObjectNode root = M.createObjectNode();
        root.put("status", "success");
        root.put("resultKind", "INFOTABLE_LARGE");
        root.put("totalRows", 40);
        root.set("columns", columnsMeta(List.of("id", "c1", "c2", "c3")));
        ArrayNode rows = root.putArray("sampleRows");
        for (int i = 1; i <= 20; i++) {
            rows.add(shortRow(i));
        }
        if (cacheId != null) {
            root.put("cacheId", cacheId);
        }
        root.put("hint", "Use fetch_cached_result only to display or browse a page.");
        return write(root);
    }

    private static ObjectNode shortRow(int i) {
        ObjectNode o = M.createObjectNode();
        o.put("id", "row-" + i);
        o.put("c1", "c1-" + i);
        o.put("c2", "c2-" + i);
        o.put("c3", "c3-" + i);
        return o;
    }

    /** R-H: INFOTABLE_LARGE of 40 rows by 4 columns with cells of about 400 characters. */
    public static String longWideCellTable(String cacheId) {
        List<String> cols = List.of("id", "c1", "c2", "c3");
        ObjectNode root = M.createObjectNode();
        root.put("status", "success");
        root.put("resultKind", "INFOTABLE_LARGE");
        root.put("totalRows", 40);
        root.set("columns", columnsMeta(cols));
        ArrayNode rows = root.putArray("sampleRows");
        for (int i = 1; i <= 20; i++) {
            rows.add(tableRow(cols, i, 400));
        }
        if (cacheId != null) {
            root.put("cacheId", cacheId);
        }
        root.put("hint", "Use fetch_cached_result only to display or browse a page.");
        return write(root);
    }

    /** R-I column names: four digits followed by 60 backslashes. */
    public static List<String> escapingHeavyNames() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            out.add(String.format("%04d", i) + "\\".repeat(60));
        }
        return out;
    }

    /** R-I: an inline INFOTABLE of 3 rows by 100 columns whose names are escaping-heavy. */
    public static String escapingHeavyTable() {
        List<String> cols = escapingHeavyNames();
        ObjectNode root = M.createObjectNode();
        root.put("status", "success");
        root.put("resultKind", "INFOTABLE");
        root.put("rowCount", 3);
        root.set("columns", columnsMeta(cols));
        ArrayNode rows = root.putArray("rows");
        for (int r = 1; r <= 3; r++) {
            ObjectNode o = rows.addObject();
            for (int c = 0; c < cols.size(); c++) {
                o.put(cols.get(c), "v" + r + "-" + c);
            }
        }
        return write(root);
    }

    /** R-J: 300 schema entries and no {@code rows}; not table-shaped, never sampled, kept by every pass. */
    public static String schemaOnly() {
        ObjectNode root = M.createObjectNode();
        root.put("status", "success");
        ArrayNode cols = root.putArray("columns");
        for (int i = 1; i <= 300; i++) {
            ObjectNode c = cols.addObject();
            c.put("name", value(String.format("schema-%03d-", i), 40));
            c.put("baseType", "STRING");
        }
        root.put("hint", "Schema of the demo evidence source.");
        return write(root);
    }

    // ---- document pages (the get_document_chunk shape) ----

    public static String plainPageMarkdown() {
        return value("Demo guide page text. ", 3_000);
    }

    /** 5,500 characters, every one a quote, a backslash or a newline. */
    public static String escapingPageMarkdown() {
        StringBuilder sb = new StringBuilder(5_500);
        char[] cycle = {'"', '\\', '\n'};
        for (int i = 0; i < 5_500; i++) {
            sb.append(cycle[i % cycle.length]);
        }
        return sb.toString();
    }

    public static String page(String chunkId, String markdown) {
        ObjectNode root = M.createObjectNode();
        root.put("status", "success");
        root.put("degraded", false);
        root.put("docId", "demo-guide");
        root.put("chunkId", chunkId);
        root.put("heading", "Demo article");
        root.putArray("sectionPath").add("Demo article");
        root.put("contentType", "page");
        root.put("pageStart", 1);
        root.put("pageEnd", 1);
        root.put("markdown", markdown);
        ArrayNode links = root.putArray("sourceLinks");
        ObjectNode link = links.addObject();
        link.put("repository", "TestDocs");
        link.put("path", "/document-knowledge/demo-guide/source/original.pdf");
        root.putArray("warnings");
        return write(root);
    }

    public static String page0001() {
        return page("page-0001", plainPageMarkdown());
    }

    public static String page0002() {
        return page("page-0002", escapingPageMarkdown());
    }

    // ---- SDK values for the formatter ----

    /** A platform service wrapper table whose {@code result} cell holds the value as an {@code org.json.JSONObject}. */
    public static InfoTable jsonServiceResult(JsonNode value) {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(new FieldDefinition("result", "", BaseTypes.JSON));
        InfoTable outer = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("result", new JsonObjectCell(new JSONObject(write(value))));
        outer.addRow(row);
        return outer;
    }

    /** A wrapper table whose {@code result} cell holds a plain string, for a STRING service. */
    public static InfoTable stringServiceResult(String value) {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(new FieldDefinition("result", "", BaseTypes.STRING));
        InfoTable outer = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("result", new StringPrimitive(value));
        outer.addRow(row);
        return outer;
    }

    public static ServiceDefinition serviceReturning(BaseTypes type) {
        try {
            ServiceDefinition sd = new ServiceDefinition("DemoEvidence", "fixture");
            FieldDefinition rt = new FieldDefinition("result", "", type);
            sd.getClass().getMethod("setResultType", FieldDefinition.class).invoke(sd, rt);
            return sd;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** An InfoTable of {@code rows} by the named STRING columns, cells built like {@link #tableRow}. */
    public static InfoTable infotable(List<String> cols, int rows, int cellChars) {
        DataShapeDefinition shape = new DataShapeDefinition();
        for (String c : cols) {
            shape.addFieldDefinition(new FieldDefinition(c, "", BaseTypes.STRING));
        }
        InfoTable it = new InfoTable(shape);
        for (int r = 1; r <= rows; r++) {
            ValueCollection row = new ValueCollection();
            for (String c : cols) {
                row.put(c, new StringPrimitive(value(c + "-" + r + "-", cellChars)));
            }
            it.addRow(row);
        }
        return it;
    }

    /** R-D as an SDK table: {@code id} = {@code row-<i>}, {@code c1..c3} = {@code c<n>-<i>}. */
    public static InfoTable longShortInfotable() {
        DataShapeDefinition shape = new DataShapeDefinition();
        for (String c : List.of("id", "c1", "c2", "c3")) {
            shape.addFieldDefinition(new FieldDefinition(c, "", BaseTypes.STRING));
        }
        InfoTable it = new InfoTable(shape);
        for (int i = 1; i <= 40; i++) {
            ValueCollection row = new ValueCollection();
            row.put("id", new StringPrimitive("row-" + i));
            row.put("c1", new StringPrimitive("c1-" + i));
            row.put("c2", new StringPrimitive("c2-" + i));
            row.put("c3", new StringPrimitive("c3-" + i));
            it.addRow(row);
        }
        return it;
    }

    /** A platform service wrapper whose result is the table itself (as Java services return INFOTABLE). */
    public static InfoTable infotableWrapper(InfoTable table) {
        return table;
    }

    public static List<String> wideColumns() {
        return names("col", 30);
    }

    // ---- helpers ----

    /** Deterministic text of exactly {@code len} characters starting with {@code prefix}. */
    public static String value(String prefix, int len) {
        StringBuilder sb = new StringBuilder(len);
        sb.append(prefix);
        int i = 0;
        while (sb.length() < len) {
            sb.append((char) ('a' + (i++ % 26)));
        }
        return sb.substring(0, len);
    }

    private static String bracketText(int len) {
        StringBuilder sb = new StringBuilder(len);
        int i = 0;
        while (sb.length() < len) {
            sb.append("Evidence line [").append(i++).append("] observed value; ");
        }
        return sb.substring(0, len);
    }

    public static String write(JsonNode n) {
        try {
            return M.writeValueAsString(n);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static JsonNode read(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
