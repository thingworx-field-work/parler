package com.thingworx.things.agent.cache;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.joda.time.DateTime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.tools.TagJsonCodec;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.TagCollection;
import com.thingworx.types.TagLink;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.InfoTablePrimitive;
import com.thingworx.types.primitives.LocationPrimitive;
import com.thingworx.types.primitives.LongPrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;
import com.thingworx.types.primitives.TagCollectionPrimitive;
import com.thingworx.types.primitives.structs.Location;

/**
 * Opaque TABULAR payload codec for {@link TabularArtifactHub}. Avoids platform
 * {@code InfoTable#toJSON} / {@code JSONUtilities}. Format v1:
 * {@code {v, fields:[{name,baseType,localFields?}], rows:[{...}]}}.
 *
 * <p>Nested {@link BaseTypes#INFOTABLE} cells are encoded recursively (with local DataShape) so
 * M2 {@code extract_nested} can promote them after hub round-trip.
 */
final class TabularInfotableCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_NESTING = PasswordSchemaPreflight.MAX_DEPTH;

    private TabularInfotableCodec() {}

    static byte[] encode(InfoTable table) throws Exception {
        return MAPPER.writeValueAsString(encodeTableNode(table, 1)).getBytes(StandardCharsets.UTF_8);
    }

    static InfoTable decode(byte[] bytes) throws Exception {
        JsonNode root = MAPPER.readTree(bytes);
        return decodeTableNode(root, 1);
    }

    private static ObjectNode encodeTableNode(InfoTable table, int nestingLevel) throws Exception {
        if (nestingLevel > MAX_NESTING) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.INVALID_REQUEST,
                    "Tabular codec nestingLevel exceeds maxDepth");
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("v", FORMAT_VERSION);
        ArrayNode fields = root.putArray("fields");
        List<String> colNames = new ArrayList<>();
        List<BaseTypes> colTypes = new ArrayList<>();
        DataShapeDefinition shape = table == null ? null : table.getDataShape();
        if (shape != null && shape.getFields() != null) {
            for (FieldDefinition fd : shape.getFields().values()) {
                if (fd == null || fd.getName() == null) {
                    continue;
                }
                ObjectNode f = fields.addObject();
                f.put("name", fd.getName());
                BaseTypes bt = fd.getBaseType() == null ? BaseTypes.STRING : fd.getBaseType();
                f.put("baseType", bt.name());
                if (bt == BaseTypes.INFOTABLE && fd.getLocalDataShape() != null) {
                    f.set("localFields", encodeFieldsOnly(fd.getLocalDataShape(), nestingLevel + 1));
                }
                colNames.add(fd.getName());
                colTypes.add(bt);
            }
        }
        ArrayNode rows = root.putArray("rows");
        int n = table == null || table.getRowCount() == null ? 0 : table.getRowCount();
        for (int i = 0; i < n; i++) {
            ValueCollection row = table.getRow(i);
            ObjectNode r = rows.addObject();
            for (int c = 0; c < colNames.size(); c++) {
                String col = colNames.get(c);
                BaseTypes bt = colTypes.get(c);
                Object raw = row.get(col);
                if (raw == null) {
                    r.putNull(col);
                    continue;
                }
                if (bt == BaseTypes.INFOTABLE) {
                    InfoTable nested = unwrapInfotable(raw);
                    if (nested == null) {
                        r.putNull(col);
                    } else {
                        r.set(col, encodeTableNode(nested, nestingLevel + 1));
                    }
                    continue;
                }
                if (raw instanceof IPrimitiveType) {
                    putTyped(r, col, bt, (IPrimitiveType) raw);
                } else {
                    r.put(col, String.valueOf(raw));
                }
            }
        }
        return root;
    }

    private static ArrayNode encodeFieldsOnly(DataShapeDefinition shape, int nestingLevel)
            throws Exception {
        if (nestingLevel > MAX_NESTING) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.INVALID_REQUEST,
                    "Tabular codec nestingLevel exceeds maxDepth");
        }
        ArrayNode fields = MAPPER.createArrayNode();
        if (shape == null || shape.getFields() == null) {
            return fields;
        }
        for (FieldDefinition fd : shape.getFields().values()) {
            if (fd == null || fd.getName() == null) {
                continue;
            }
            ObjectNode f = fields.addObject();
            f.put("name", fd.getName());
            BaseTypes bt = fd.getBaseType() == null ? BaseTypes.STRING : fd.getBaseType();
            f.put("baseType", bt.name());
            if (bt == BaseTypes.INFOTABLE && fd.getLocalDataShape() != null) {
                f.set("localFields", encodeFieldsOnly(fd.getLocalDataShape(), nestingLevel + 1));
            }
        }
        return fields;
    }

    private static InfoTable decodeTableNode(JsonNode root, int nestingLevel) throws Exception {
        if (nestingLevel > MAX_NESTING) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.INVALID_REQUEST,
                    "Tabular codec nestingLevel exceeds maxDepth");
        }
        if (root.path("v").asInt(0) != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported tabular artifact format version");
        }
        DataShapeDefinition shape = decodeShape(root.path("fields"), nestingLevel);
        List<String> colNames = new ArrayList<>();
        List<BaseTypes> colTypes = new ArrayList<>();
        for (JsonNode f : root.path("fields")) {
            colNames.add(f.path("name").asText(""));
            colTypes.add(BaseTypes.valueOf(f.path("baseType").asText(BaseTypes.STRING.name())));
        }
        InfoTable table = new InfoTable(shape);
        for (JsonNode r : root.path("rows")) {
            ValueCollection row = new ValueCollection();
            for (int i = 0; i < colNames.size(); i++) {
                String col = colNames.get(i);
                BaseTypes bt = colTypes.get(i);
                JsonNode cell = r.get(col);
                if (cell == null || cell.isNull()) {
                    continue;
                }
                if (bt == BaseTypes.INFOTABLE) {
                    if (cell.isObject()) {
                        row.put(col, new InfoTablePrimitive(decodeTableNode(cell, nestingLevel + 1)));
                    }
                    continue;
                }
                row.put(col, toPrimitive(bt, cell));
            }
            table.addRow(row);
        }
        return table;
    }

    private static DataShapeDefinition decodeShape(JsonNode fieldsNode, int nestingLevel)
            throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        if (fieldsNode == null || !fieldsNode.isArray()) {
            return shape;
        }
        for (JsonNode f : fieldsNode) {
            String name = f.path("name").asText("");
            BaseTypes bt = BaseTypes.valueOf(f.path("baseType").asText(BaseTypes.STRING.name()));
            FieldDefinition fd = new FieldDefinition();
            fd.setName(name);
            fd.setBaseType(bt);
            if (bt == BaseTypes.INFOTABLE && f.has("localFields")) {
                fd.setLocalDataShape(decodeShape(f.get("localFields"), nestingLevel + 1));
            }
            shape.addFieldDefinition(fd);
        }
        return shape;
    }

    private static InfoTable unwrapInfotable(Object raw) {
        if (raw instanceof InfoTable) {
            return (InfoTable) raw;
        }
        if (raw instanceof InfoTablePrimitive) {
            return ((InfoTablePrimitive) raw).getValue();
        }
        if (raw instanceof IPrimitiveType) {
            Object v = ((IPrimitiveType) raw).getValue();
            if (v instanceof InfoTable) {
                return (InfoTable) v;
            }
        }
        return null;
    }

    private static void putTyped(ObjectNode r, String col, BaseTypes bt, IPrimitiveType p) throws Exception {
        Object v = p.getValue();
        if (v == null) {
            r.putNull(col);
            return;
        }
        switch (bt) {
            case BOOLEAN:
                r.put(col, v instanceof Boolean ? (Boolean) v : Boolean.parseBoolean(String.valueOf(v)));
                return;
            case INTEGER:
            case LONG:
            case NUMBER:
                if (v instanceof Number) {
                    r.put(col, ((Number) v).doubleValue());
                } else {
                    r.put(col, Double.parseDouble(String.valueOf(v)));
                }
                return;
            case DATETIME:
                if (v instanceof DateTime) {
                    r.put(col, ((DateTime) v).getMillis());
                } else if (v instanceof Number) {
                    r.put(col, ((Number) v).longValue());
                } else {
                    r.put(col, String.valueOf(v));
                }
                return;
            case LOCATION:
                if (v instanceof Location) {
                    Location loc = (Location) v;
                    ObjectNode o = r.putObject(col);
                    o.put("latitude", loc.getLatitude());
                    o.put("longitude", loc.getLongitude());
                    o.put("elevation", loc.getElevation());
                } else {
                    r.put(col, String.valueOf(v));
                }
                return;
            case TAGS:
                TagCollection tags = null;
                if (v instanceof TagCollection) {
                    tags = (TagCollection) v;
                } else if (p instanceof TagCollectionPrimitive) {
                    tags = ((TagCollectionPrimitive) p).getValue();
                }
                if (tags != null) {
                    // Manual encode — TagCollection#toJSON pulls JSONUtilities/JodaModule.
                    ArrayNode arr = MAPPER.createArrayNode();
                    for (TagLink link : tags) {
                        if (link == null) {
                            continue;
                        }
                        ObjectNode o = arr.addObject();
                        o.put("vocabulary", link.getVocabulary() == null ? "" : link.getVocabulary());
                        o.put("vocabularyTerm",
                                link.getVocabularyTerm() == null ? "" : link.getVocabularyTerm());
                    }
                    r.set(col, arr);
                } else {
                    r.put(col, String.valueOf(v));
                }
                return;
            default:
                r.put(col, String.valueOf(v));
        }
    }

    private static IPrimitiveType toPrimitive(BaseTypes bt, JsonNode cell) throws Exception {
        switch (bt) {
            case BOOLEAN:
                return new BooleanPrimitive(cell.asBoolean());
            case INTEGER:
                // Prefer NumberPrimitive: IntegerPrimitive ctors pull ValidationException on some
                // unit-test classpaths; NUMBER is accepted by tabular/analyze consumers.
                return new NumberPrimitive(cell.asDouble());
            case LONG:
                return new LongPrimitive(Long.valueOf(cell.asLong()));
            case NUMBER:
                return new NumberPrimitive(Double.valueOf(cell.asDouble()));
            case DATETIME:
                if (cell.isNumber()) {
                    return new DatetimePrimitive(Long.valueOf(cell.asLong()));
                }
                return new DatetimePrimitive(DateTime.parse(cell.asText()));
            case LOCATION:
                if (cell.isObject()) {
                    return new LocationPrimitive(
                            Double.valueOf(cell.path("latitude").asDouble(0)),
                            Double.valueOf(cell.path("longitude").asDouble(0)),
                            Double.valueOf(cell.path("elevation").asDouble(0)));
                }
                return new LocationPrimitive(Location.fromString(cell.asText()));
            case TAGS:
                return TagJsonCodec.parseTagCollectionPrimitive(cell);
            default:
                return new StringPrimitive(cell.asText());
        }
    }
}
