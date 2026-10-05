package com.thingworx.things.agent.tools;

import org.json.JSONObject;

import com.thingworx.entities.interfaces.IServiceProvider;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.IntegerPrimitive;
import com.thingworx.types.primitives.JSONPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * Summary probe for {@code acknowledge_alerts} / {@code specific_alerts} — no static ThingWorx logger so JUnit can
 * exercise {@link #runSummaryProbeForAckOrNormalize} without full platform log bootstrap.
 */
public final class AlertSummaryAckProbe {

    private AlertSummaryAckProbe() {}

    /** Supplies summary rows for {@code specific_alerts} probe; may throw platform exceptions. */
    @FunctionalInterface
    public interface SummaryProbeCall {
        InfoTable run() throws Exception;
    }

    /**
     * Outcome of {@link #runSummaryProbeForAckOrNormalize(SummaryProbeCall)}: either summary rows or a normalized error
     * JSON body (same shape as {@link AlertAcknowledgePlatformErrors}).
     */
    public static final class SummaryProbeOutcome {
        public final InfoTable rows;
        public final String errorJson;

        SummaryProbeOutcome(InfoTable rows, String errorJson) {
            this.rows = rows;
            this.errorJson = errorJson;
        }
    }

    /**
     * Runs a probe call (typically {@link #querySummaryRowsForAck}); on failure returns
     * {@link AlertAcknowledgePlatformErrors#normalizedPlatformJson(Throwable)}. Exposed for JUnit without instantiating
     * {@link com.thingworx.types.collections.ValueCollection} on the failure path.
     */
    public static SummaryProbeOutcome runSummaryProbeForAckOrNormalize(SummaryProbeCall probe) {
        try {
            return new SummaryProbeOutcome(probe.run(), null);
        } catch (Exception e) {
            try {
                return new SummaryProbeOutcome(null, AlertAcknowledgePlatformErrors.normalizedPlatformJson(e));
            } catch (Exception ex) {
                return new SummaryProbeOutcome(null, ackProbeErrorJson("ACK_PROBE_PLATFORM_ERROR",
                        ex.getMessage() != null ? ex.getMessage() : "normalize failed"));
            }
        }
    }

    /**
     * Runs {@link #querySummaryRowsForAck}; on platform failure returns
     * {@link AlertAcknowledgePlatformErrors#normalizedPlatformJson(Throwable)}.
     */
    public static SummaryProbeOutcome runSummaryProbeForAckOrNormalize(IServiceProvider alertFunctions, String thingName,
            String propertyName, String alertName) {
        return runSummaryProbeForAckOrNormalize(
                () -> querySummaryRowsForAck(alertFunctions, thingName, propertyName, alertName));
    }

    /**
     * Unacknowledged-only summary read for {@code specific_alerts} probe (see
     * {@code docs/operations/alert-solution.md} §5.3).
     */
    public static InfoTable querySummaryRowsForAck(IServiceProvider alertFunctions, String thingName,
            String propertyName, String alertName) throws Exception {
        ValueCollection vc = new ValueCollection();
        vc.put("name", new StringPrimitive(thingName));
        vc.put("onlyAcknowledged", new BooleanPrimitive(false));
        vc.put("onlyUnacknowledged", new BooleanPrimitive(true));
        vc.put("property", new StringPrimitive(propertyName));
        vc.put("maxItems", new IntegerPrimitive(AlertSpecificAckPolicy.PROBE_MAX_ITEMS));
        JSONObject q = AlertQueryFilterBuilder.buildSummaryQuery(alertName, null, null, null, null);
        if (q != null) {
            vc.put("query", new JSONPrimitive(q));
        }
        InfoTable outer = alertFunctions.processAPIServiceRequest("QueryAlertSummaryForThing", vc);
        InfoTable inner = ServiceResultInfotable.extractInfotableResult(outer);
        return inner != null ? inner : new InfoTable();
    }

    private static String ackProbeErrorJson(String code, String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("status", "error");
            o.put("code", code);
            o.put("message", message != null ? message : "");
            return o.toString();
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"ACK_PROBE_PLATFORM_ERROR\",\"message\":\"json failed\"}";
        }
    }
}
