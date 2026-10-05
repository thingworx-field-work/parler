package com.thingworx.things.agent.tools;

import java.time.Instant;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.Thing;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

import org.slf4j.Logger;
import com.thingworx.logging.LogUtilities;

/**
 * Curated wrapper for Stream {@link com.thingworx.things.connected.RemoteStream#QueryStreamData}. Non-numeric Thing
 * property history is handled by {@link PropertyToolsExecutor#executeQueryPropertyHistory} (tool {@code query_property_history}).
 *
 * <p>App User data-insight surfaces ship before {@code query*log} wrappers.
 * LogRetriever tools remain lowest priority for Parler (developer/operator debugging); streams and non-numeric value
 * history are wired here first.</p>
 */
public final class StreamValueStreamToolsExecutor {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(StreamValueStreamToolsExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_ITEMS = 500;
    private static final int MAX_STREAM_ITEMS = 5000;

    private StreamValueStreamToolsExecutor() {}

    public static String executeQueryStreamData(ToolCall call) {
        try {
            return doQueryStreamData(call);
        } catch (Exception e) {
            LOG.warn("query_stream_data failed: {}", e.getMessage(), e);
            return errorJson("QUERY_STREAM_DATA_ERROR", e.getMessage());
        }
    }

    /**
     * Legacy executor entry for replay/tests; unified implementation lives in
     * {@link PropertyToolsExecutor#executeQueryPropertyHistory}.
     */
    public static String executeQueryValueStreamPropertyHistory(ToolCall call) {
        return PropertyToolsExecutor.executeQueryPropertyHistory(call);
    }

    private static String doQueryStreamData(ToolCall call) throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
        // B14/S12: same scalar Thing preflight as property/alert tools (canonical gate + recoveryHint).
        ScalarThingnamePreflight.ApplicationThingGateOutcome thingGate =
                ScalarThingnamePreflight.gateApplicationThing("thingName", text(root, "thingName"));
        if (thingGate.isError()) {
            return thingGate.errorJson;
        }
        Thing thing = thingGate.thing;
        String thingName = thingGate.canonicalThingName;
        String shapeBadField = ToolJsonTimeBounds.firstNonTextualField(root, "startTime", "endTime", "start", "end",
                "calendarPhrase", "relativeDuration");
        if (shapeBadField != null) {
            return BuiltInToolTimeErrorJson.error("INVALID_TIME_SPEC_SHAPE",
                    shapeBadField + " must be a JSON string.", shapeBadField);
        }
        String startLabel = ExplicitIsoTimeBounds.pickAliasLabel(root, "startTime", "start");
        String endLabel = ExplicitIsoTimeBounds.pickAliasLabel(root, "endTime", "end");
        String startStr = firstNonBlank(text(root, "startTime"), text(root, "start"));
        String endStr = firstNonBlank(text(root, "endTime"), text(root, "end"));
        BuiltInToolNaturalTimeWindow.Outcome nlWindow =
                BuiltInToolNaturalTimeWindow.resolveNumericPropertyHistory(root, Instant.now(), startStr, endStr);
        if (nlWindow.errorCode != null) {
            return BuiltInToolTimeErrorJson.fromNaturalTimeOutcome(nlWindow);
        }
        DateTime start;
        DateTime end;
        String appliedSource = null;
        String appliedTz = null;
        if (!nlWindow.skip) {
            start = utcDateTime(nlWindow.resolution.getStartUtc());
            end = utcDateTime(nlWindow.resolution.getEndUtc());
            appliedSource = nlWindow.kind == BuiltInToolNaturalTimeWindow.AppliedKind.CALENDAR_DAY_ENGLISH
                    ? "NATURAL_LANGUAGE_CALENDAR_DAY"
                    : "NATURAL_LANGUAGE_RELATIVE_DURATION";
            if (nlWindow.kind == BuiltInToolNaturalTimeWindow.AppliedKind.CALENDAR_DAY_ENGLISH) {
                appliedTz = AgentToolContext.getUserIanaTimezone();
            }
        } else {
            ExplicitIsoTimeBounds.ParseOutcome iso =
                    ExplicitIsoTimeBounds.parseOptionalPair(startStr, endStr, startLabel, endLabel);
            if (!iso.ok) {
                return BuiltInToolTimeErrorJson.error(iso.errorCode, iso.errorMessage, iso.failedField);
            }
            start = iso.start;
            end = iso.end;
            if (start != null && end != null) {
                appliedSource = "EXPLICIT_ISO";
            }
        }

        int maxItemsRequested = root.has("maxItems") ? root.get("maxItems").asInt(DEFAULT_MAX_ITEMS) : DEFAULT_MAX_ITEMS;
        int maxItems = Math.min(Math.max(1, maxItemsRequested), MAX_STREAM_ITEMS);
        boolean oldestFirst = root.has("oldestFirst") && root.get("oldestFirst").asBoolean(false);
        String source = text(root, "source");

        ValueCollection vc = new ValueCollection();
        vc.put("maxItems", new NumberPrimitive((double) maxItems));
        vc.put("oldestFirst", new BooleanPrimitive(oldestFirst));
        if (start != null) {
            vc.put("startDate", new DatetimePrimitive(start));
        }
        if (end != null) {
            vc.put("endDate", new DatetimePrimitive(end));
        }
        if (source != null && !source.isBlank()) {
            vc.put("source", new StringPrimitive(source.trim()));
        }

        LOG.info("query_stream_data: thing={} maxItems={} oldestFirst={} start={} end={}",
                thingName, maxItems, oldestFirst, start, end);

        InfoTable table;
        try {
            table = PlatformAccess.invokeAsUser(thing, "QueryStreamData", vc);
        } catch (Exception e) {
            LOG.warn("query_stream_data QueryStreamData failed: thing={} — {}", thingName, e.getMessage());
            return errorJson("QUERY_STREAM_SERVICE_FAILED",
                    e.getMessage() != null ? e.getMessage()
                            : "QueryStreamData failed. Ensure thingName is a Stream Thing with a configured data shape.");
        }
        ObjectNode extras = MAPPER.createObjectNode();
        extras.put("tool", "query_stream_data");
        extras.put("thingName", thingName);
        extras.put("serviceInvoked", "QueryStreamData");
        extras.put("maxItemsRequested", maxItemsRequested);
        extras.put("maxItemsEffective", maxItems);
        extras.put("oldestFirst", oldestFirst);
        putAppliedWindowIfPresent(extras, start, end, appliedSource, appliedTz);
        // Stated here, from the platform's table and this read's effective limit (design tabular-reach 7.10).
        return InvokeServiceExecutor.formatBuiltinInfotableResult(table, extras,
                com.thingworx.things.agent.source.ReadLimitFact.observe(table, maxItems));
    }

    private static void putAppliedWindowIfPresent(ObjectNode parent, DateTime start, DateTime end, String source,
            String tz) {
        if (start == null || end == null || source == null) {
            return;
        }
        ParlerAppliedTimeWindowJson.putClosedOpenWindow(parent, source,
                Instant.ofEpochMilli(start.getMillis()), Instant.ofEpochMilli(end.getMillis()), tz);
    }

    private static DateTime utcDateTime(Instant instant) {
        return new DateTime(instant.toEpochMilli(), DateTimeZone.UTC);
    }

    private static String text(JsonNode root, String field) {
        if (root == null || !root.has(field)) {
            return null;
        }
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isTextual()) {
            return null;
        }
        String s = n.asText();
        return s != null ? s.trim() : null;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    private static String errorJson(String code, String message) {
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", code);
            o.put("message", message == null ? "" : message);
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"" + code + "\",\"message\":\"serialization failed\"}";
        }
    }
}
