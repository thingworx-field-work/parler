package com.thingworx.things.agent.tools;

import java.util.Iterator;
import java.util.Map;

import org.joda.time.DateTime;
import org.json.JSONArray;
import org.json.JSONObject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.IntegerPrimitive;
import com.thingworx.types.primitives.JSONPrimitive;
import com.thingworx.types.primitives.LongPrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;
import com.thingworx.types.primitives.VariantPrimitive;

/**
 * JSON-node → ThingWorx {@link IPrimitiveType} coercion for {@code invoke_service} scalar parameters
 * (INFOTABLE shaping is handled separately by {@link InfotableJsonCodec}).
 *
 * <p>Kept separate from {@link InvokeServiceExecutor} for two reasons:</p>
 * <ol>
 *   <li><b>Decouple the time-defense wiring from {@code LogUtilities}.</b> The defense for
 *       {@link BaseTypes#DATETIME} (and the §5 step 3 fallback for JSON / VARIANT slots whose name matches
 *       §8 DATETIME-like keys) now runs <em>before</em> any wrapping {@code try / catch} so the typed
 *       {@link UnsupportedRelativeLiteralException} reaches {@link InvokeServiceExecutor}'s catch site
 *       intact. Running the defense inside the wrapping catch would mask the wire error code
 *       {@code UNSUPPORTED_RELATIVE_LITERAL}.</li>
 *   <li><b>Make the wiring offline-testable.</b> {@link InvokeServiceExecutor} requires
 *       {@code LogUtilities.getInstance()} during static init, which is not available in plain JUnit.
 *       This helper has no such dependency.</li>
 * </ol>
 *
 * <p>Object-shaped JSON / VARIANT values are scanned at the top level for §8 DATETIME-like keys with textual
 * values (the spec's "untyped JSON / VARIANT bags, apply §8 name heuristics on known DATETIME-like keys").
 * Nested objects are intentionally left for follow-on telemetry-driven work.</p>
 *
 * <p>See {@code docs/agent/time-interpretation.md} §13 step 3 / §5 step 3.</p>
 */
final class InvokeServiceArgumentCoercion {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InvokeServiceArgumentCoercion() {}

    /**
     * Coerce {@code node} to a ThingWorx {@link IPrimitiveType} appropriate for {@code baseType}.
     *
     * @throws UnsupportedRelativeLiteralException if the pre-parse defense rejects raw text in a
     *         {@link BaseTypes#DATETIME} slot, or in a {@link BaseTypes#JSON} / {@link BaseTypes#VARIANT}
     *         slot whose name matches the §8 DATETIME-like list (textual value), or whose object value
     *         carries a top-level §8-named key with rejected textual content (typed so the executor can
     *         route it).
     * @throws IllegalArgumentException for other coercion failures.
     */
    static IPrimitiveType coerce(String paramName, JsonNode node, BaseTypes baseType) {
        BaseTypes bt = baseType == null ? BaseTypes.STRING : baseType;
        // Defense runs BEFORE the wrapping try / catch so the typed exception is not rewrapped.
        if (bt == BaseTypes.DATETIME) {
            InvokeServiceDatetimeLiteralDefense.throwIfRejected(paramName, node == null ? null : node.asText());
        } else if (bt == BaseTypes.JSON || bt == BaseTypes.VARIANT) {
            // §5 step 3 fallback split: scalar textual value OR top-level object-key scan.
            if (node != null && node.isTextual()
                    && InvokeServiceDatetimeLiteralDefense.isDatetimeLikeName(paramName)) {
                InvokeServiceDatetimeLiteralDefense.throwIfRejected(paramName, node.asText());
            } else if (node != null && node.isObject()) {
                // Walk one level into bag-shaped values. For each top-level
                // key matching the §8 DATETIME-like list, defend the textual value (ignore non-text values
                // and nested objects — that is intentional v1 conservatism, not silent permission).
                for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    String key = entry.getKey();
                    JsonNode value = entry.getValue();
                    if (value != null && value.isTextual()
                            && InvokeServiceDatetimeLiteralDefense.isDatetimeLikeName(key)) {
                        // Path-style label so the rejected wire message identifies which key inside the bag
                        // failed (e.g. "payload.startDate").
                        InvokeServiceDatetimeLiteralDefense.throwIfRejected(paramName + "." + key, value.asText());
                    }
                }
            }
        }
        try {
            switch (bt) {
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
                    return new NumberPrimitive(
                            node.isNumber() ? node.doubleValue() : Double.parseDouble(node.asText()));
                case BOOLEAN:
                    if (node.isBoolean()) {
                        return new BooleanPrimitive(node.booleanValue());
                    }
                    return new BooleanPrimitive(Boolean.parseBoolean(node.asText()));
                case DATETIME:
                    // Defense already ran above; safe to parse.
                    return new DatetimePrimitive(DateTime.parse(node.asText().trim()));
                case NOTHING:
                    return new StringPrimitive("");
                case TAGS:
                    try {
                        return TagJsonCodec.parseTagCollectionPrimitive(node);
                    } catch (Exception e) {
                        throw new IllegalArgumentException(
                                "Parameter \"" + paramName + "\": invalid TAGS: " + e.getMessage());
                    }
                case QUERY:
                    return QueryJsonPrimitiveMapper.toJsonPrimitive(paramName, node, MAPPER);
                case JSON:
                    return BaseTypes.ConvertToPrimitive(jsonNodeToPlatformObject(node), BaseTypes.JSON);
                case VARIANT:
                    if (node.isObject() || node.isArray()) {
                        // Structured VARIANT tool input is intentionally treated as JSON data, not as the
                        // platform's optional {baseType,value} VARIANT envelope. That avoids LLM/Mashup payloads
                        // accidentally reinterpreting ordinary JSON bags as typed TWX primitives, and also supports
                        // top-level arrays (which the platform VARIANT converter does not accept directly).
                        return new VariantPrimitive((JSONPrimitive) BaseTypes.ConvertToPrimitive(
                                jsonNodeToPlatformObject(node), BaseTypes.JSON));
                    }
                    return BaseTypes.ConvertToPrimitive(jsonNodeToPlatformObject(node), BaseTypes.VARIANT);
                case XML:
                    return BaseTypes.ConvertToPrimitive(jsonNodeToPlatformObject(node), BaseTypes.XML);
                case GUID:
                    return BaseTypes.ConvertToPrimitive(jsonNodeToPlatformObject(node), BaseTypes.GUID);
                case LOCATION:
                    return BaseTypes.ConvertToPrimitive(jsonNodeToPlatformObject(node), BaseTypes.LOCATION);
                case PASSWORD:
                case STRING:
                case TEXT:
                case HYPERLINK:
                case IMAGELINK:
                case HTML:
                default:
                    if (node.isTextual()) {
                        return new StringPrimitive(node.asText());
                    }
                    if (node.isNumber() || node.isBoolean()) {
                        return new StringPrimitive(node.asText());
                    }
                    return new StringPrimitive(node.toString());
            }
        } catch (UnsupportedRelativeLiteralException e) {
            // Belt-and-suspenders re-throw: wrapping this exception generically inside the same try block
            // (the catch (Exception e) below) would mask the wire error code. The current architecture runs the defense *above* this try, so this branch is
            // unreachable today — but if a future inner case body adds a nested coercion path that itself
            // triggers the defense, this guard keeps the typed exception intact rather than rewrapping it
            // as "Parameter X: cannot convert to …". Cheap insurance; documents the lesson.
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Parameter \"" + paramName + "\": cannot convert to " + bt + ": " + e.getMessage());
        }
    }

    /**
     * @return {@code true} when the QUERY value was supplied as a JSON-object-encoded string (rather than as
     *         a structured object) — exposed so {@link InvokeServiceExecutor} can keep its existing
     *         {@code DEBUG} log next to the invocation site without holding the coercion logic.
     */
    static boolean isTextualQueryValue(JsonNode node) {
        return node != null && node.isTextual();
    }

    static Object jsonNodeToPlatformObject(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isObject()) {
            return new JSONObject(node.toString());
        }
        if (node.isArray()) {
            return new JSONArray(node.toString());
        }
        return MAPPER.convertValue(node, Object.class);
    }
}
