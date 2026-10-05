package com.thingworx.things.agent.tools;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.types.primitives.JSONPrimitive;

/**
 * Converts tool-call JSON for {@code BaseTypes.QUERY} into {@link JSONPrimitive}. No ThingWorx {@code LogUtilities}
 * here so rejection-path unit tests can run without the full platform stack. Textual-object recovery logging is owned
 * by {@link InvokeServiceExecutor} (debug) — this mapper is silent on the parse/validate path.
 */
public final class QueryJsonPrimitiveMapper {

    /**
     * Decimal-string pagination fields: plain positive decimal integer syntax only (no leading zero, no fraction).
     * Used by {@link #parseStrictPositiveInt}.
     */
    private static final Pattern STRICT_POSITIVE_INT_TEXT = Pattern.compile("[1-9][0-9]*");

    private QueryJsonPrimitiveMapper() {}

    /**
     * Parses tool-call JSON into {@link JSONObject} using the same root-shape rules as {@link #toJsonPrimitive} — does
     * not construct {@link JSONPrimitive} (offline-testable). {@link QueryEntitiesExecutor} and {@link #toJsonPrimitive}
     * share this so QUERY parse rules stay single-sourced.
     */
    public static JSONObject parseQueryObject(String paramName, JsonNode node, ObjectMapper mapper)
            throws IllegalArgumentException {
        if (mapper == null) {
            throw new IllegalArgumentException("mapper is null");
        }
        try {
            if (node == null || node.isNull()) {
                throw new IllegalArgumentException("Parameter \"" + paramName + "\": QUERY value is null");
            }
            if (node.isArray() || node.isNumber() || node.isBoolean()) {
                throw new IllegalArgumentException(
                        "Parameter \"" + paramName + "\": QUERY must be a JSON object {...}, not an array or primitive");
            }
            final String json;
            if (node.isTextual()) {
                String text = node.asText().trim();
                if (text.isEmpty() || text.charAt(0) != '{') {
                    throw new IllegalArgumentException(
                            "Parameter \"" + paramName + "\": QUERY textual value must be a JSON object starting with '{'");
                }
                json = text;
            } else if (node.isObject()) {
                json = mapper.writeValueAsString(node);
            } else {
                throw new IllegalArgumentException(
                        "Parameter \"" + paramName + "\": QUERY must be a JSON object or textual JSON object");
            }
            return new JSONObject(json);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Parameter \"" + paramName
                            + "\": invalid QUERY JSON (pass a structured {...} object per get_service_definition): "
                            + e.getMessage());
        }
    }

    /**
     * Validates QUERY JSON per {@code docs/agent/query-construction.md} before the platform sees it. Safe to call on any
     * {@link JSONObject} parsed from the tool call — does not construct {@link JSONPrimitive} (unit-testable offline).
     * <p>
     * <b>Current coverage:</b> {@code pagination} shape (must be object when present) and strictly positive integer
     * {@code pageSize} / {@code pageNumber}. Other §3 rules remain platform/parser behavior unless extended here.
     *
     * @param paramLabel label for errors (e.g. actual parameter name from {@code ServiceDefinition})
     */
    public static void validateQueryObject(JSONObject query, String paramLabel) {
        if (query == null) {
            return;
        }
        if (!query.has("pagination") || query.isNull("pagination")) {
            return;
        }
        Object raw = query.get("pagination");
        if (!(raw instanceof JSONObject)) {
            String kind = raw == null ? "null" : raw.getClass().getSimpleName();
            throw new IllegalArgumentException(
                    "Parameter \""
                            + paramLabel
                            + "\": pagination must be a JSON object with pageSize/pageNumber fields, not "
                            + kind);
        }
        JSONObject p = (JSONObject) raw;
        assertPaginationPositive(p, "pageSize", paramLabel);
        assertPaginationPositive(p, "pageNumber", paramLabel);
    }

    /**
     * Convenience for tests or contexts without a {@code ServiceDefinition} parameter name; production call sites
     * should use {@link #validateQueryObject(JSONObject, String)} so errors name the real parameter.
     */
    public static void validateQueryObject(JSONObject query) {
        validateQueryObject(query, "query");
    }

    /**
     * Requires strictly positive integers: JSON integral numbers (reject fractional), or strings matching {@code [1-9][0-9]*}
     * (courtesy for stringified tool JSON — rejects decimals and {@code "01"}).
     */
    private static void assertPaginationPositive(JSONObject pagination, String field, String paramLabel) {
        if (!pagination.has(field) || pagination.isNull(field)) {
            return;
        }
        Object raw = pagination.get(field);
        String path = "pagination." + field;
        parseStrictPositiveInt(raw, path, paramLabel);
    }

    /**
     * Positive integer values only: integral JSON numbers (including {@code 10.0}-style doubles), or decimal-string
     * literals matching {@code [1-9][0-9]*} — regex screens shape; {@code Long.parseLong} + range check screens
     * magnitude (overflow beyond {@code int}).
     */
    private static void parseStrictPositiveInt(Object raw, String pathForError, String paramLabel) {
        if (raw instanceof Boolean) {
            throw new IllegalArgumentException(
                    "Parameter \"" + paramLabel + "\": " + pathForError + " must be a positive integer, not boolean");
        }
        if (raw instanceof JSONArray || raw instanceof JSONObject) {
            throw new IllegalArgumentException(
                    "Parameter \"" + paramLabel + "\": " + pathForError + " must be a positive integer, not object/array");
        }
        if (raw instanceof String) {
            String s = ((String) raw).trim();
            if (!STRICT_POSITIVE_INT_TEXT.matcher(s).matches()) {
                throw new IllegalArgumentException(
                        "Parameter \""
                                + paramLabel
                                + "\": "
                                + pathForError
                                + " must be a positive integer (reject decimals/non-integers; see query-construction.md)");
            }
            try {
                long lv = Long.parseLong(s);
                if (lv <= 0 || lv > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException(
                            "Parameter \""
                                    + paramLabel
                                    + "\": "
                                    + pathForError
                                    + " must fit in a positive 32-bit int (see query-construction.md)");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Parameter \""
                                + paramLabel
                                + "\": "
                                + pathForError
                                + " must be a positive integer (see query-construction.md)");
            }
            return;
        }
        if (raw instanceof Integer || raw instanceof Short || raw instanceof Byte) {
            int v = ((Number) raw).intValue();
            if (v <= 0) {
                throw new IllegalArgumentException(
                        "Parameter \""
                                + paramLabel
                                + "\": "
                                + pathForError
                                + " must be > 0 when set (ThingWorx QUERY pagination rejects zero; see query-construction.md)");
            }
            return;
        }
        if (raw instanceof Long) {
            long lv = (Long) raw;
            if (lv <= 0 || lv > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "Parameter \""
                                + paramLabel
                                + "\": "
                                + pathForError
                                + " must be a positive 32-bit integer (see query-construction.md)");
            }
            return;
        }
        if (raw instanceof BigInteger) {
            BigInteger bi = (BigInteger) raw;
            if (bi.signum() <= 0 || bi.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                throw new IllegalArgumentException(
                        "Parameter \""
                                + paramLabel
                                + "\": "
                                + pathForError
                                + " must be a positive 32-bit integer (see query-construction.md)");
            }
            return;
        }
        if (raw instanceof BigDecimal) {
            BigDecimal bd = (BigDecimal) raw;
            try {
                int v = bd.intValueExact();
                if (v <= 0) {
                    throw new IllegalArgumentException(
                            "Parameter \""
                                    + paramLabel
                                    + "\": "
                                    + pathForError
                                    + " must be > 0 when set (ThingWorx QUERY pagination rejects zero; see query-construction.md)");
                }
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException(
                        "Parameter \""
                                + paramLabel
                                + "\": "
                                + pathForError
                                + " must be a positive integer (reject fractional numbers; see query-construction.md)");
            }
            return;
        }
        if (raw instanceof Number) {
            double d = ((Number) raw).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException(
                        "Parameter \"" + paramLabel + "\": " + pathForError + " must be a finite positive integer");
            }
            if (d <= 0 || d > Integer.MAX_VALUE || d != Math.rint(d)) {
                throw new IllegalArgumentException(
                        "Parameter \""
                                + paramLabel
                                + "\": "
                                + pathForError
                                + " must be a positive integer (reject fractional numbers; see query-construction.md)");
            }
            return;
        }
        throw new IllegalArgumentException(
                "Parameter \"" + paramLabel + "\": " + pathForError + " must be a positive integer");
    }

    /**
     * @param paramName tool parameter name (for error messages)
     * @param node Jackson node — structured object or textual JSON object {@code {...}}
     * @param mapper same mapper as {@link InvokeServiceExecutor}
     * @return QUERY primitive
     * @throws IllegalArgumentException if shape is not a JSON object at the root
     */
    public static JSONPrimitive toJsonPrimitive(String paramName, JsonNode node, ObjectMapper mapper)
            throws IllegalArgumentException {
        JSONObject jo = parseQueryObject(paramName, node, mapper);
        validateQueryObject(jo, paramName);
        try {
            return new JSONPrimitive(jo);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Parameter \"" + paramName
                            + "\": invalid QUERY JSON (pass a structured {...} object per get_service_definition): "
                            + e.getMessage());
        }
    }
}
