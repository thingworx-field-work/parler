package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.joda.time.DateTime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.common.interfaces.IDataShapeDefinitionProvider;
import com.thingworx.common.utils.MetadataUtilities;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.collections.FieldDefinitionCollection;
import com.thingworx.system.managers.DataShapeManager;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.IntegerPrimitive;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.LongPrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;
import com.thingworx.types.primitives.InfoTablePrimitive;

import org.slf4j.Logger;
import com.thingworx.logging.LogUtilities;

/**
 * Generic LLM-friendly JSON ↔ {@link InfoTable} for service parameters typed as INFOTABLE.
 * Beyond supported cases, throws {@link IllegalArgumentException} with a clear message and
 * {@link #CUSTOM_TOOL_HINT}.
 * <p>
 * Row binding: a <b>missing</b> JSON key for a required column is distinct from a key present with JSON
 * {@code null}. Present {@code null} is treated as nullable in this conversion layer unless
 * {@link FieldDefinition#isPrimaryKey()} is true; unknown aspects default to
 * nullable (no additional not-null rules derived from {@code isRequired} here).
 */
public final class InfotableJsonCodec {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(InfotableJsonCodec.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * JUnit-only: when set on this thread, {@link MetadataUtilities#getDataShapeDefinition} uses this provider
     * instead of {@link DataShapeManager#getInstance()}. Always {@linkplain #clearTestDataShapeProviderOverride()
     * clear} in {@code finally}.
     */
    private static final ThreadLocal<IDataShapeDefinitionProvider> TEST_SHAPE_PROVIDER = new ThreadLocal<>();

    /** Test-only override for INFOTABLE DataShape resolution (same thread). */
    public static void setTestDataShapeProviderOverride(IDataShapeDefinitionProvider provider) {
        TEST_SHAPE_PROVIDER.set(provider);
    }

    /** Clears {@link #setTestDataShapeProviderOverride}; safe to call when no override is set. */
    public static void clearTestDataShapeProviderOverride() {
        TEST_SHAPE_PROVIDER.remove();
    }

    /** Passed to {@link MetadataUtilities} when {@link FieldDefinition#getLocalDataShape()} is set (unused at runtime). */
    private static final IDataShapeDefinitionProvider UNUSED_SHAPE_PROVIDER = name -> null;

    private static IDataShapeDefinitionProvider platformShapeProvider() {
        IDataShapeDefinitionProvider t = TEST_SHAPE_PROVIDER.get();
        return t != null ? t : DataShapeManager.getInstance();
    }

    /** Appended to every out-of-generic-support error (invoke_service + schema generation). */
    public static final String CUSTOM_TOOL_HINT =
            " Define a normal ThingWorx service with a supported parameter shape and register it as an LLM tool via"
                    + " `/tools/extended_tools.json` on the AgentThing `configurationRepository` FileRepository"
                    + " (see docs/agent/configuration-repository.md); build complex INFOTABLE values in script/Java there.";

    /** Top-level INFOTABLE = depth 0; each nested INFOTABLE column adds 1. Depth 3 = fourth layer → reject. */
    private static final int MAX_INFOTABLE_DEPTH = 3;

    private InfotableJsonCodec() {}

    /**
     * JSON Schema fragment for an INFOTABLE input parameter (OpenAI tools / function parameters).
     *
     * @throws IllegalArgumentException if shape cannot be resolved, has no field definitions, contains VARIANT,
     *         nested INFOTABLE exceeds depth, or nested column shape cannot be resolved
     */
    public static Map<String, Object> parameterSchemaForLlm(FieldDefinition infotableParam) {
        String paramLabel = infotableParam != null && infotableParam.getName() != null
                ? infotableParam.getName() : "(unnamed)";
        DataShapeDefinition shape = resolveDataShapeForParameter(infotableParam);
        assertShapeResolvable(shape, paramLabel, true);
        assertShapeRepresentableForLlm(shape, 0, paramLabel);

        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "array");
        String desc = infotableParam.getDescription() != null ? infotableParam.getDescription() : "";
        prop.put("description", desc + " Each array element is one table row. Column keys and types below.");
        Map<String, Object> itemProps = new LinkedHashMap<>();
        List<String> itemRequired = new ArrayList<>();
        for (FieldDefinition col : shape.getFields().values()) {
            if (col.getName() == null) {
                continue;
            }
            Map<String, Object> colSchema = columnToJsonSchemaProperty(col, 0, paramLabel);
            itemProps.put(col.getName(), colSchema);
            if (isColumnRequired(col)) {
                itemRequired.add(col.getName());
            }
        }
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "object");
        items.put("properties", itemProps);
        if (!itemRequired.isEmpty()) {
            items.put("required", itemRequired);
        }
        prop.put("items", items);
        return prop;
    }

    private static Map<String, Object> columnToJsonSchemaProperty(FieldDefinition col, int depth, String ctx) {
        BaseTypes bt = col.getBaseType() != null ? col.getBaseType() : BaseTypes.STRING;
        if (bt == BaseTypes.VARIANT) {
            throw new IllegalArgumentException("[" + ctx + "] Column \"" + col.getName()
                    + "\" is VARIANT; generic JSON tooling cannot describe or convert it." + CUSTOM_TOOL_HINT);
        }
        if (bt == BaseTypes.INFOTABLE) {
            if (depth + 1 >= MAX_INFOTABLE_DEPTH) {
                throw new IllegalArgumentException("[" + ctx + "] Nested INFOTABLE exceeds supported depth (max "
                        + MAX_INFOTABLE_DEPTH + " levels including top-level parameter table)." + CUSTOM_TOOL_HINT);
            }
            DataShapeDefinition nested = resolveNestedInfotableShape(col);
            assertShapeResolvable(nested, ctx + "." + col.getName(), false);
            assertShapeRepresentableForLlm(nested, depth + 1, ctx + "." + col.getName());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "array");
            m.put("description", (col.getDescription() != null ? col.getDescription() + " " : "")
                    + "Nested table: JSON array; each element is one row object.");
            Map<String, Object> nProps = new LinkedHashMap<>();
            List<String> nReq = new ArrayList<>();
            for (FieldDefinition c2 : nested.getFields().values()) {
                if (c2.getName() == null) {
                    continue;
                }
                nProps.put(c2.getName(), columnToJsonSchemaProperty(c2, depth + 1, ctx + "." + col.getName()));
                if (isColumnRequired(c2)) {
                    nReq.add(c2.getName());
                }
            }
            Map<String, Object> items = new LinkedHashMap<>();
            items.put("type", "object");
            items.put("properties", nProps);
            if (!nReq.isEmpty()) {
                items.put("required", nReq);
            }
            m.put("items", items);
            return m;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", baseTypeToJsonSchemaType(bt));
        if (col.getDescription() != null && !col.getDescription().isEmpty()) {
            m.put("description", col.getDescription());
        }
        return m;
    }

    private static void assertShapeResolvable(DataShapeDefinition shape, String paramLabel, boolean topLevel) {
        if (shape == null) {
            throw new IllegalArgumentException("Parameter \"" + paramLabel + "\": cannot resolve INFOTABLE row shape "
                    + "(set dataShape aspect to a valid DataShape or provide embedded field definitions)."
                    + CUSTOM_TOOL_HINT);
        }
        if (isShapeFieldDefinitionsEmpty(shape)) {
            throw new IllegalArgumentException("Parameter \"" + paramLabel + "\": DataShape has no fieldDefinitions (empty column list)."
                    + CUSTOM_TOOL_HINT);
        }
    }

    private static void assertShapeRepresentableForLlm(DataShapeDefinition shape, int depth, String ctx) {
        for (FieldDefinition col : shape.getFields().values()) {
            if (col.getName() == null) {
                continue;
            }
            BaseTypes bt = col.getBaseType() != null ? col.getBaseType() : BaseTypes.STRING;
            if (bt == BaseTypes.VARIANT) {
                throw new IllegalArgumentException("[" + ctx + "] Column \"" + col.getName()
                        + "\" is VARIANT; cannot build an LLM-fillable schema." + CUSTOM_TOOL_HINT);
            }
            if (bt == BaseTypes.INFOTABLE) {
                if (depth + 1 >= MAX_INFOTABLE_DEPTH) {
                    throw new IllegalArgumentException("[" + ctx + "] Nested INFOTABLE too deep (max "
                            + MAX_INFOTABLE_DEPTH + " levels)." + CUSTOM_TOOL_HINT);
                }
                DataShapeDefinition n = resolveNestedInfotableShape(col);
                assertShapeResolvable(n, ctx + "." + col.getName(), false);
                assertShapeRepresentableForLlm(n, depth + 1, ctx + "." + col.getName());
            }
        }
    }

    private static boolean isShapeFieldDefinitionsEmpty(DataShapeDefinition shape) {
        if (shape == null || shape.getFields() == null) {
            return true;
        }
        boolean any = false;
        for (FieldDefinition f : shape.getFields().values()) {
            if (f.getName() != null) {
                any = true;
                break;
            }
        }
        return !any;
    }

    private static String baseTypeToJsonSchemaType(BaseTypes bt) {
        if (bt == null) {
            return "string";
        }
        switch (bt) {
            case INTEGER:
            case LONG:
                return "integer";
            case NUMBER:
                return "number";
            case BOOLEAN:
                return "boolean";
            default:
                return "string";
        }
    }

    private static boolean isColumnRequired(FieldDefinition col) {
        try {
            Object aspects = col.getAspects();
            if (aspects == null) {
                return true;
            }
            Object v = aspects.getClass().getMethod("get", Object.class).invoke(aspects, "isRequired");
            if (v instanceof Boolean) {
                return (Boolean) v;
            }
            if (v != null && "false".equalsIgnoreCase(v.toString())) {
                return false;
            }
        } catch (Exception ignored) {
            // default required
        }
        return true;
    }

    /**
     * Primary key columns reject JSON {@code null} when the key is present. Uses platform
     * {@link FieldDefinition#isPrimaryKey()} (ThingWorx {@code AspectCollection} / {@code isPrimaryKey} aspect).
     * When the aspect is absent or false, present {@code null} is treated as nullable in this conversion layer.
     */
    private static boolean isPrimaryKeyField(FieldDefinition col) {
        return col != null && col.isPrimaryKey();
    }

    public static DataShapeDefinition resolveDataShapeForParameter(FieldDefinition fd) {
        if (fd == null || fd.getBaseType() != BaseTypes.INFOTABLE) {
            return null;
        }
        try {
            if (fd.getLocalDataShape() != null) {
                return MetadataUtilities.getDataShapeDefinition(UNUSED_SHAPE_PROVIDER, fd);
            }
            return MetadataUtilities.getDataShapeDefinition(platformShapeProvider(), fd);
        } catch (Exception e) {
            LOG.warn("InfotableJsonCodec: DataShape resolution failed for parameter \"{}\" (aspect=\"{}\"): {}",
                    fd.getName(), fd.getDataShapeName(), e.getMessage());
            return null;
        }
    }

    public static InfoTable jsonToInfoTable(JsonNode node, DataShapeDefinition shape, String paramName) {
        return jsonToInfoTable(node, shape, paramName, 0);
    }

    private static InfoTable jsonToInfoTable(JsonNode node, DataShapeDefinition shape, String paramName, int depth) {
        if (depth >= MAX_INFOTABLE_DEPTH) {
            throw new IllegalArgumentException("Parameter \"" + paramName + "\": INFOTABLE nesting exceeds limit ("
                    + MAX_INFOTABLE_DEPTH + " levels including top)."
                    + CUSTOM_TOOL_HINT);
        }
        assertShapeResolvable(shape, paramName, depth == 0);

        JsonNode arr = node;
        if (node != null && node.isTextual()) {
            try {
                arr = MAPPER.readTree(node.asText());
            } catch (Exception e) {
                throw new IllegalArgumentException("Parameter \"" + paramName + "\": expected JSON array of rows or a single row object; string parse failed: "
                        + e.getMessage() + CUSTOM_TOOL_HINT);
            }
        }
        if (arr != null && arr.isObject()) {
            com.fasterxml.jackson.databind.node.ArrayNode wrap = MAPPER.createArrayNode();
            wrap.add(arr);
            arr = wrap;
        }
        if (arr == null || !arr.isArray()) {
            throw new IllegalArgumentException("Parameter \"" + paramName + "\": INFOTABLE must be a JSON array (one object per row) or a single row object."
                    + CUSTOM_TOOL_HINT);
        }
        FieldDefinitionCollection cols = shape.getFields();
        InfoTable table = new InfoTable(shape);
        for (int i = 0; i < arr.size(); i++) {
            JsonNode rowNode = arr.get(i);
            if (!rowNode.isObject()) {
                throw new IllegalArgumentException("Parameter \"" + paramName + "\": row " + i + " must be a JSON object."
                        + CUSTOM_TOOL_HINT);
            }
            ValueCollection row = new ValueCollection();
            for (FieldDefinition col : cols.values()) {
                String cn = col.getName();
                if (cn == null) {
                    continue;
                }
                BaseTypes cbt = col.getBaseType() != null ? col.getBaseType() : BaseTypes.STRING;
                if (cbt == BaseTypes.VARIANT) {
                    throw new IllegalArgumentException("Parameter \"" + paramName + "\" row " + i + ": column \"" + cn
                            + "\" is VARIANT; JSON-to-InfoTable not supported."
                            + CUSTOM_TOOL_HINT);
                }
                boolean req = isColumnRequired(col);
                boolean keyPresent = rowNode.has(cn);
                JsonNode cell = keyPresent ? rowNode.get(cn) : null;
                if (!keyPresent) {
                    if (req) {
                        throw new IllegalArgumentException("Parameter \"" + paramName + "\" row " + i + ": missing required column \""
                                + cn + "\"." + CUSTOM_TOOL_HINT);
                    }
                    continue;
                }
                if (cell != null && cell.isNull()) {
                    if (isPrimaryKeyField(col)) {
                        throw new IllegalArgumentException("Parameter \"" + paramName + "\" row " + i + ": NULL_VALUE_NOT_ALLOWED for primary key column \""
                                + cn + "\"." + CUSTOM_TOOL_HINT);
                    }
                    row.put(cn, null);
                    continue;
                }
                if (cbt == BaseTypes.INFOTABLE) {
                    if (depth + 1 >= MAX_INFOTABLE_DEPTH) {
                        throw new IllegalArgumentException("Parameter \"" + paramName + "\": nested INFOTABLE column \"" + cn
                                + "\" exceeds supported depth." + CUSTOM_TOOL_HINT);
                    }
                    DataShapeDefinition nested = resolveNestedInfotableShape(col);
                    assertShapeResolvable(nested, paramName + "." + cn, false);
                    row.put(cn, new InfoTablePrimitive(jsonToInfoTable(cell, nested, paramName + "." + cn, depth + 1)));
                } else {
                    row.put(cn, cellToPrimitive(cell, cbt, paramName + " row" + i + "." + cn));
                }
            }
            table.addRow(row);
        }
        return table;
    }

    private static DataShapeDefinition resolveNestedInfotableShape(FieldDefinition col) {
        if (col == null || col.getBaseType() != BaseTypes.INFOTABLE) {
            return null;
        }
        try {
            if (col.getLocalDataShape() != null) {
                return MetadataUtilities.getDataShapeDefinition(UNUSED_SHAPE_PROVIDER, col);
            }
            return MetadataUtilities.getDataShapeDefinition(platformShapeProvider(), col);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static IPrimitiveType cellToPrimitive(JsonNode node, BaseTypes bt, String path) {
        if (bt == null) {
            bt = BaseTypes.STRING;
        }
        if (bt == BaseTypes.VARIANT) {
            throw new IllegalArgumentException(path + ": VARIANT is not supported." + CUSTOM_TOOL_HINT);
        }
        if (bt == BaseTypes.INFOTABLE) {
            throw new IllegalArgumentException(path + ": internal error (INFOTABLE in scalar path)." + CUSTOM_TOOL_HINT);
        }
        switch (bt) {
            case BLOB:
            case IMAGE:
                throw new IllegalArgumentException(path + ": column type " + bt + " cannot be passed via JSON."
                        + CUSTOM_TOOL_HINT);
            case INTEGER:
                if (node.isInt() || node.isLong()) {
                    return new IntegerPrimitive(node.intValue());
                }
                return new IntegerPrimitive(Integer.parseInt(node.asText()));
            case LONG:
                if (node.isInt() || node.isLong()) {
                    return new LongPrimitive(node.longValue());
                }
                if (node.isNumber()) {
                    return new LongPrimitive(node.longValue());
                }
                return new LongPrimitive(Long.parseLong(node.asText().trim()));
            case NUMBER:
                return new NumberPrimitive(node.isNumber() ? node.doubleValue() : Double.parseDouble(node.asText()));
            case BOOLEAN:
                if (node.isBoolean()) {
                    return new BooleanPrimitive(node.booleanValue());
                }
                return new BooleanPrimitive(Boolean.parseBoolean(node.asText()));
            case DATETIME:
                return new DatetimePrimitive(DateTime.parse(node.asText()));
            default:
                if (node.isTextual()) {
                    return new StringPrimitive(node.asText());
                }
                return new StringPrimitive(node.toString());
        }
    }
}
