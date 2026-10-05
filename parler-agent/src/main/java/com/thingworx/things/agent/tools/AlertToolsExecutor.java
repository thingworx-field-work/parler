package com.thingworx.things.agent.tools;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import org.joda.time.DateTime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.entities.RootEntity;
import com.thingworx.entities.interfaces.IServiceProvider;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.cache.ArtifactCacheTurnFaults;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.IntegerPrimitive;
import com.thingworx.types.primitives.JSONPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

import org.json.JSONObject;
import org.slf4j.Logger;

import com.thingworx.logging.LogUtilities;

/**
 * Built-in tools for {@code Resources["AlertFunctions"]} per {@code docs/operations/alert-solution.md}.
 */
public final class AlertToolsExecutor {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(AlertToolsExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RESOURCE_NAME = "AlertFunctions";
    private static final int DEFAULT_LIMIT = 100;
    /** {@code QueryAlertSummaryForThing} maxItems for {@code specific_alerts}: request one extra row to detect truncation. */
    private static final int ACK_SUMMARY_PROBE_ITEMS = AlertSpecificAckPolicy.PROBE_MAX_ITEMS;

    /**
     * Unit tests only: if non-null, invoked at the start of every {@link #resolveAlertFunctions()} call (after
     * preflight has passed). Used to assert no {@code AlertFunctions} resolution occurs on preflight reject paths.
     */
    static volatile Runnable onResolveAlertFunctionsEnteredForTests;

    /**
     * Unit tests only: when set, replaces {@code QueryAlertSummaryForThing} for each canonical Thing name.
     */
    static volatile Function<String, InfoTable> queryAlertSummaryForThingOverrideForTests;

    /**
     * Unit tests only (S7): when set, replaces the {@code specific_alerts} summary probe so JUnit can
     * exercise classify-before-write without resolving {@code AlertFunctions}.
     */
    static volatile Supplier<AlertSummaryAckProbe.SummaryProbeOutcome> ackSummaryProbeOverrideForTests;

    /**
     * Unit tests only (S7): when non-null, incremented immediately before each specific-alerts
     * platform ack write ({@code AcknowledgeAlert} / {@code AcknowledgeAlertFromSummary}).
     */
    static volatile AtomicInteger specificAlertsAckWriteAttemptsForTests;

    static void clearAlertToolsExecutorTestHooks() {
        onResolveAlertFunctionsEnteredForTests = null;
        queryAlertSummaryForThingOverrideForTests = null;
        ackSummaryProbeOverrideForTests = null;
        specificAlertsAckWriteAttemptsForTests = null;
    }

    private AlertToolsExecutor() {}

    public static String executeQueryAlertSummary(ToolCall call) {
        try {
            return doQueryAlertSummary(call);
        } catch (Exception e) {
            ArtifactCacheTurnFaults.rethrowRepositoryUnavailable(e);
            LOG.warn("query_alert_summary: {}", e.getMessage(), e);
            return errorJson("QUERY_ALERT_SUMMARY_ERROR", e.getMessage());
        }
    }

    public static String executeQueryAlertHistory(ToolCall call) {
        try {
            return doQueryAlertHistory(call);
        } catch (Exception e) {
            ArtifactCacheTurnFaults.rethrowRepositoryUnavailable(e);
            LOG.warn("query_alert_history: {}", e.getMessage(), e);
            return errorJson("QUERY_ALERT_HISTORY_ERROR", e.getMessage());
        }
    }

    public static String executeAcknowledgeAlerts(ToolCall call) {
        try {
            return doAcknowledgeAlerts(call);
        } catch (Exception e) {
            LOG.warn("acknowledge_alerts: {}", e.getMessage(), e);
            return errorJson("ACKNOWLEDGE_ALERTS_ERROR", e.getMessage());
        }
    }

    private static String doQueryAlertSummary(ToolCall call) throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
        JsonNode thingNamesNode = root.get("thingNames");
        if (thingNamesNode == null || thingNamesNode.isNull() || !thingNamesNode.isArray()) {
            return ScalarThingnamePreflight.thingNameValueRequiredJson("thingNames");
        }
        if (thingNamesNode.size() == 0) {
            return ScalarThingnamePreflight.thingNameValueRequiredJson("thingNames");
        }
        if (thingNamesNode.size() > AlertSummaryMultiRollup.MAX_THING_NAMES_PER_CALL) {
            return AlertSummaryMultiRollup.thingNamesLimitExceededJson(thingNamesNode.size(),
                    AlertSummaryMultiRollup.MAX_THING_NAMES_PER_CALL);
        }

        ScalarThingnamePreflight.ApplicationThingsListGateOutcome listGate =
                ScalarThingnamePreflight.gateApplicationThings("thingNames", thingNamesNode);
        if (listGate.isWholeCallError()) {
            return listGate.wholeCallErrorJson;
        }

        SummaryQueryParams params = SummaryQueryParams.fromRoot(root);
        if (params.invalidQueryJson != null) {
            return params.invalidQueryJson;
        }

        List<String> canonicalNames = dedupePreserveOrder(listGate.canonicalThingNames);
        if (thingNamesNode.size() == 1) {
            return executeSingleThingAlertSummary(canonicalNames.get(0), params);
        }
        return executeMultiThingAlertSummary(thingNamesNode.size(), canonicalNames, listGate.identityErrors, params);
    }

    private static String executeSingleThingAlertSummary(String canonicalThingName, SummaryQueryParams params)
            throws Exception {
        InfoTable inner = queryAlertSummaryForThing(canonicalThingName, params);
        ObjectNode extras = MAPPER.createObjectNode();
        extras.put("thingName", canonicalThingName);
        extras.put("ackState", params.ackState);
        if (params.propertyName != null && !params.propertyName.isEmpty()) {
            extras.put("propertyName", params.propertyName);
        }
        extras.put("limitApplied", params.limit);
        extras.put("rowCount", inner.getRowCount());
        if (params.summarySort != null && !params.summarySort.isEmpty()
                && !"default".equalsIgnoreCase(params.summarySort.trim())) {
            extras.put("appliedSummarySort", params.summarySort);
        }
        return InvokeServiceExecutor.formatBuiltinInfotableResult(inner, extras);
    }

    private static String executeMultiThingAlertSummary(int thingsRequested, List<String> canonicalNames,
            ArrayNode identityErrors, SummaryQueryParams params) throws Exception {
        List<AlertSummaryMultiRollup.ThingRollupPart> parts = new ArrayList<>();
        int serviceSuccess = 0;
        String lastServiceCode = null;
        String lastServiceMessage = null;
        ObjectNode queryExtras = MAPPER.createObjectNode();
        queryExtras.put("ackState", params.ackState);
        if (params.propertyName != null && !params.propertyName.isEmpty()) {
            queryExtras.put("propertyName", params.propertyName);
        }
        queryExtras.put("limitApplied", params.limit);

        if (identityErrors != null) {
            for (JsonNode err : identityErrors) {
                String tn = err.path("suppliedValue").asText(err.path("thingName").asText(""));
                ObjectNode entry = err.deepCopy();
                entry.put("status", "error");
                if (!entry.has("thingName") && !tn.isEmpty()) {
                    entry.put("thingName", tn);
                }
                parts.add(new AlertSummaryMultiRollup.ThingRollupPart(entry, false, true, false));
            }
        }

        for (String canonicalThingName : canonicalNames) {
            try {
                InfoTable inner = queryAlertSummaryForThing(canonicalThingName, params);
                parts.add(AlertSummaryMultiRollup.successFromInfoTable(canonicalThingName, inner, queryExtras));
                serviceSuccess++;
            } catch (Exception e) {
                ArtifactCacheTurnFaults.rethrowRepositoryUnavailable(e);
                lastServiceCode = "QUERY_ALERT_SUMMARY_ERROR";
                lastServiceMessage = e.getMessage();
                parts.add(AlertSummaryMultiRollup.serviceErrorEntry(canonicalThingName, lastServiceCode,
                        lastServiceMessage));
            }
        }

        if (serviceSuccess == 0 && !canonicalNames.isEmpty()) {
            return AlertSummaryMultiRollup.buildAllServiceFailedJson(thingsRequested, parts, identityErrors,
                    lastServiceCode, lastServiceMessage);
        }
        return AlertSummaryMultiRollup.buildSuccessJson(thingsRequested, parts, identityErrors, queryExtras);
    }

    private static InfoTable queryAlertSummaryForThing(String canonicalThingName, SummaryQueryParams params)
            throws Exception {
        Function<String, InfoTable> override = queryAlertSummaryForThingOverrideForTests;
        if (override != null) {
            InfoTable t = override.apply(canonicalThingName);
            return t != null ? t : new InfoTable();
        }
        ValueCollection vc = new ValueCollection();
        vc.put("name", new StringPrimitive(canonicalThingName));
        applyAckState(vc, params.ackState);
        if (params.propertyName != null && !params.propertyName.isEmpty()) {
            vc.put("property", new StringPrimitive(params.propertyName));
        }
        vc.put("maxItems", new IntegerPrimitive(params.limit));
        if (params.mergedQuery != null) {
            vc.put("query", new JSONPrimitive(params.mergedQuery));
        }
        IServiceProvider provider = resolveAlertFunctions();
        InfoTable outer = provider.processAPIServiceRequest("QueryAlertSummaryForThing", vc);
        InfoTable inner = ServiceResultInfotable.extractInfotableResult(outer);
        return inner != null ? inner : new InfoTable();
    }

    private static List<String> dedupePreserveOrder(List<String> names) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String n : names) {
            if (n != null && seen.add(n)) {
                out.add(n);
            }
        }
        return out;
    }

    private static final class SummaryQueryParams {
        final String ackState;
        final String propertyName;
        final String summarySort;
        final int limit;
        final JSONObject mergedQuery;
        final String invalidQueryJson;

        private SummaryQueryParams(String ackState, String propertyName, String summarySort, int limit,
                JSONObject mergedQuery, String invalidQueryJson) {
            this.ackState = ackState;
            this.propertyName = propertyName;
            this.summarySort = summarySort;
            this.limit = limit;
            this.mergedQuery = mergedQuery;
            this.invalidQueryJson = invalidQueryJson;
        }

        static SummaryQueryParams fromRoot(JsonNode root) {
            try {
                return fromRootInner(root);
            } catch (IllegalArgumentException e) {
                return new SummaryQueryParams("all", null, null, DEFAULT_LIMIT, null,
                        errorJson("INVALID_SUMMARY_QUERY", e.getMessage()));
            } catch (Exception e) {
                return new SummaryQueryParams("all", null, null, DEFAULT_LIMIT, null,
                        errorJson("INVALID_SUMMARY_QUERY", e.getMessage()));
            }
        }

        private static SummaryQueryParams fromRootInner(JsonNode root) throws Exception {
            String ackState = text(root, "ackState");
            if (ackState == null || ackState.isEmpty()) {
                ackState = "all";
            }
            String propertyName = text(root, "propertyName");
            String alertName = text(root, "alertName");
            String alertType = text(root, "alertType");
            Integer pMin = intOrNull(root, "priorityMin");
            Integer pMax = intOrNull(root, "priorityMax");
            String advancedQuery = text(root, "advancedQuery");
            String summarySort = text(root, "sort");
            int limit = clampLimit(root.path("limit").asInt(DEFAULT_LIMIT));
            JSONObject merged = AlertQueryFilterBuilder.buildSummaryQuery(alertName, alertType, pMin, pMax, advancedQuery,
                    summarySort);
            return new SummaryQueryParams(ackState, propertyName, summarySort, limit, merged, null);
        }
    }

    private static String doQueryAlertHistory(ToolCall call) throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
        ScalarThingnamePreflight.ApplicationThingGateOutcome thingGate =
                ScalarThingnamePreflight.gateApplicationThing("thingName", text(root, "thingName"));
        if (thingGate.isError()) {
            return thingGate.errorJson;
        }
        String canonicalThingName = thingGate.canonicalThingName;
        // Identify which field carries the non-textual value so the wire envelope can populate
        // rejectedParameter (cross-tool convention).
        String shapeBadField = ToolJsonTimeBounds.firstNonTextualField(root, "startTime", "endTime",
                "timePreset");
        if (shapeBadField != null) {
            return BuiltInToolTimeErrorJson.error("INVALID_TIME_SPEC_SHAPE",
                    shapeBadField + " must be a JSON string.", shapeBadField);
        }
        String startTime = text(root, "startTime");
        String endTime = text(root, "endTime");
        final AlertHistoryTimeRange.Preset timePreset;
        try {
            timePreset = AlertHistoryTimeRange.parsePreset(text(root, "timePreset"));
        } catch (IllegalArgumentException e) {
            return BuiltInToolTimeErrorJson.error("INVALID_TIME_PRESET", e.getMessage(), "timePreset");
        }
        final boolean presetActive = timePreset != null && timePreset != AlertHistoryTimeRange.Preset.NONE;
        BuiltInToolNaturalTimeWindow.Outcome nlWindow =
                BuiltInToolNaturalTimeWindow.resolveAlertHistory(root, Instant.now(), startTime, endTime,
                        presetActive);
        if (nlWindow.errorCode != null) {
            return BuiltInToolTimeErrorJson.fromNaturalTimeOutcome(nlWindow);
        }
        final AlertHistoryTimeRange tr;
        if (!nlWindow.skip) {
            AlertHistoryTimeRange.ResolutionSource src =
                    nlWindow.kind == BuiltInToolNaturalTimeWindow.AppliedKind.CALENDAR_DAY_ENGLISH
                            ? AlertHistoryTimeRange.ResolutionSource.NATURAL_LANGUAGE_CALENDAR_DAY
                            : AlertHistoryTimeRange.ResolutionSource.NATURAL_LANGUAGE_RELATIVE_DURATION;
            try {
                tr = AlertHistoryTimeRange.fromParlerResolution(nlWindow.resolution, src);
            } catch (IllegalArgumentException e) {
                // Should never happen — natural-time outcome was already validated above.
                return BuiltInToolTimeErrorJson.error("INVALID_TIME_RANGE", e.getMessage(), null);
            }
        } else {
            // Split parse from validation so the structured
            // failedField from ExplicitIsoTimeBounds.ParseOutcome propagates to the wire envelope.
            // Cross-cutting validation errors (timePreset vs explicit bounds; startTime > endTime) carry
            // no single rejectedParameter.
            ExplicitIsoTimeBounds.ParseOutcome po =
                    ExplicitIsoTimeBounds.parseAlertHistoryOptionalStrings(startTime, endTime);
            if (!po.ok) {
                return BuiltInToolTimeErrorJson.error(po.errorCode, po.errorMessage, po.failedField);
            }
            try {
                tr = AlertHistoryTimeRange.resolveParsed(po.start, po.end, timePreset,
                        AlertHistoryTimeRange.DEFAULT_WINDOW_DAYS);
            } catch (IllegalArgumentException e) {
                return BuiltInToolTimeErrorJson.error("INVALID_TIME_RANGE", e.getMessage(), null);
            }
        }
        DateTime startDt = tr.start;
        DateTime endDt = tr.end;

        String alertName = text(root, "alertName");
        String propertyName = text(root, "propertyName");
        String alertType = text(root, "alertType");
        Integer pMin = intOrNull(root, "priorityMin");
        Integer pMax = intOrNull(root, "priorityMax");
        final boolean oldestFirst;
        try {
            oldestFirst = AlertHistorySortOrder.resolveOldestFirst(root);
        } catch (IllegalArgumentException e) {
            return errorJson("INVALID_HISTORY_ORDER", e.getMessage());
        }
        int limit = clampLimit(root.path("limit").asInt(DEFAULT_LIMIT));
        String advancedQuery = text(root, "advancedQuery");

        JSONObject merged = AlertQueryFilterBuilder.buildHistoryQuery(alertName, propertyName, alertType, pMin, pMax,
                advancedQuery);

        ValueCollection vc = new ValueCollection();
        vc.put("name", new StringPrimitive(canonicalThingName));
        vc.put("startDate", new DatetimePrimitive(startDt));
        vc.put("endDate", new DatetimePrimitive(endDt));
        vc.put("oldestFirst", new BooleanPrimitive(oldestFirst));
        vc.put("maxItems", new IntegerPrimitive(limit));
        if (merged != null) {
            vc.put("query", new JSONPrimitive(merged));
        }

        IServiceProvider provider = resolveAlertFunctions();
        InfoTable outer = provider.processAPIServiceRequest("QueryAlertHistory", vc);
        InfoTable inner = ServiceResultInfotable.extractInfotableResult(outer);
        if (inner == null) {
            inner = new InfoTable();
        }

        ObjectNode extras = MAPPER.createObjectNode();
        extras.put("thingName", canonicalThingName);
        extras.put("appliedStartTime", startDt.toString());
        extras.put("appliedEndTime", endDt.toString());
        extras.put("timeRangeSource", tr.source.name());
        if (tr.source == AlertHistoryTimeRange.ResolutionSource.PRESET) {
            String w = AlertHistoryTimeRange.wirePresetName(tr.appliedPreset);
            if (w != null) {
                extras.put("appliedTimePreset", w);
            }
        }
        if (tr.source == AlertHistoryTimeRange.ResolutionSource.IMPLICIT_DEFAULT_WINDOW) {
            extras.put("implicitDefaultWindowDays", AlertHistoryTimeRange.DEFAULT_WINDOW_DAYS);
        }
        ParlerAppliedTimeWindowJson.putClosedOpenWindow(extras, tr.source.name(),
                Instant.ofEpochMilli(tr.start.getMillis()), Instant.ofEpochMilli(tr.end.getMillis()),
                tr.source == AlertHistoryTimeRange.ResolutionSource.NATURAL_LANGUAGE_CALENDAR_DAY
                        ? AgentToolContext.getUserIanaTimezone()
                        : null);
        extras.put("oldestFirst", oldestFirst);
        extras.put("limitApplied", limit);
        extras.put("rowCount", inner.getRowCount());
        extras.put("historyQueryResource", RESOURCE_NAME + ".QueryAlertHistory");
        return InvokeServiceExecutor.formatBuiltinInfotableResult(inner, extras);
    }

    private static String doAcknowledgeAlerts(ToolCall call) throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
        String rawThingName = text(root, "thingName");
        ScalarThingnamePreflight.ApplicationThingGateOutcome thingGate =
                ScalarThingnamePreflight.gateApplicationThing("thingName", rawThingName);
        final String canonicalThingName;
        if (!thingGate.isError()) {
            canonicalThingName = thingGate.canonicalThingName;
        } else if (ackSummaryProbeOverrideForTests != null && rawThingName != null
                && ScalarThingnamePreflight.isModelVisibleThing(rawThingName)) {
            // Offline S7 harness: visible name + probe override without a platform Thing handle.
            canonicalThingName = rawThingName.trim();
        } else {
            return thingGate.errorJson;
        }
        String mode = text(root, "mode");
        if (mode == null || mode.isEmpty()) {
            mode = "specific_alerts";
        }
        String propertyName = text(root, "propertyName");
        String alertName = text(root, "alertName");
        String message = text(root, "message");

        if ("property_all".equalsIgnoreCase(mode)) {
            if (propertyName == null || propertyName.isEmpty()) {
                return errorJson("MISSING_PROPERTY_NAME", "propertyName is required for property_all");
            }
            ValueCollection vc = new ValueCollection();
            vc.put("source", new StringPrimitive(canonicalThingName));
            vc.put("sourceProperty", new StringPrimitive(propertyName));
            if (message != null && !message.isEmpty()) {
                vc.put("message", new StringPrimitive(message));
            }
            IServiceProvider provider = resolveAlertFunctions();
            try {
                provider.processAPIServiceRequest("AcknowledgeAlert", vc);
            } catch (Exception e) {
                return AlertAcknowledgePlatformErrors.normalizedPlatformJson(e);
            }
            return AlertAcknowledgeResultJson.propertyAllSuccess(canonicalThingName, propertyName, message);
        }

        if (!"specific_alerts".equalsIgnoreCase(mode)) {
            return errorJson("UNKNOWN_MODE", "mode must be specific_alerts or property_all");
        }
        if (propertyName == null || propertyName.isEmpty()) {
            return errorJson("MISSING_PROPERTY_NAME",
                    "propertyName is required for specific_alerts (see docs/operations/alert-solution.md §5.3)");
        }

        // Probe first (optional offline override). EXCEEDS_LIMIT / EMPTY return before any ack write (S7).
        IServiceProvider provider = null;
        final AlertSummaryAckProbe.SummaryProbeOutcome probeOut;
        if (ackSummaryProbeOverrideForTests != null) {
            probeOut = ackSummaryProbeOverrideForTests.get();
        } else {
            provider = resolveAlertFunctions();
            probeOut = AlertSummaryAckProbe.runSummaryProbeForAckOrNormalize(provider, canonicalThingName,
                    propertyName, alertName);
        }
        if (probeOut.errorJson != null) {
            return probeOut.errorJson;
        }
        InfoTable summaryRows = probeOut.rows;
        int n = summaryRows.getRowCount();
        switch (AlertSpecificAckPolicy.classifyUnackedRowCount(n)) {
            case EXCEEDS_LIMIT:
                return AlertAcknowledgeResultJson.specificAlertsExceedsLimitError(n,
                        AlertSpecificAckPolicy.MAX_BATCH_ROWS);
            case EMPTY:
                return AlertAcknowledgeResultJson.specificAlertsEmpty(canonicalThingName, propertyName, message);
            case WITHIN_LIMIT:
                break;
        }

        if (provider == null) {
            provider = resolveAlertFunctions();
        }
        if (AlertNarrowAckPolicy.useAcknowledgeAlertForSpecificAlerts(n, alertName)) {
            ValueCollection narrow = new ValueCollection();
            narrow.put("source", new StringPrimitive(canonicalThingName));
            narrow.put("sourceProperty", new StringPrimitive(propertyName));
            if (message != null && !message.isEmpty()) {
                narrow.put("message", new StringPrimitive(message));
            }
            noteSpecificAlertsAckWriteAttemptForTests();
            try {
                provider.processAPIServiceRequest("AcknowledgeAlert", narrow);
            } catch (Exception e) {
                return AlertAcknowledgePlatformErrors.normalizedPlatformJson(e);
            }
            return AlertAcknowledgeResultJson.specificAlertsAfterAck(n, canonicalThingName, propertyName, alertName,
                    message,
                    "AcknowledgeAlert");
        }

        ValueCollection vc = new ValueCollection();
        vc.SetInfoTableValue("alerts", summaryRows);
        if (message != null && !message.isEmpty()) {
            vc.put("message", new StringPrimitive(message));
        }
        noteSpecificAlertsAckWriteAttemptForTests();
        try {
            provider.processAPIServiceRequest("AcknowledgeAlertFromSummary", vc);
        } catch (Exception e) {
            return AlertAcknowledgePlatformErrors.normalizedPlatformJson(e);
        }

        return AlertAcknowledgeResultJson.specificAlertsAfterAck(n, canonicalThingName, propertyName, alertName,
                message,
                "AcknowledgeAlertFromSummary");
    }

    private static void noteSpecificAlertsAckWriteAttemptForTests() {
        AtomicInteger counter = specificAlertsAckWriteAttemptsForTests;
        if (counter != null) {
            counter.incrementAndGet();
        }
    }

    private static IServiceProvider resolveAlertFunctions() throws Exception {
        Runnable hook = onResolveAlertFunctionsEnteredForTests;
        if (hook != null) {
            hook.run();
        }
        RootEntity ent = PlatformAccess.findAsUser(RESOURCE_NAME,
                RelationshipTypes.ThingworxRelationshipTypes.Resource);
        if (ent == null || !(ent instanceof IServiceProvider)) {
            throw new IllegalStateException("Resource \"" + RESOURCE_NAME + "\" not found or not invocable");
        }
        return (IServiceProvider) ent;
    }

    private static void applyAckState(ValueCollection vc, String ackState) {
        boolean onlyAck = "acknowledged".equalsIgnoreCase(ackState);
        boolean onlyUnack = "unacknowledged".equalsIgnoreCase(ackState);
        vc.put("onlyAcknowledged", new BooleanPrimitive(onlyAck));
        vc.put("onlyUnacknowledged", new BooleanPrimitive(onlyUnack));
    }

    private static int clampLimit(int lim) {
        if (lim < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(lim, AlertSpecificAckPolicy.MAX_BATCH_ROWS);
    }

    private static Integer intOrNull(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull() || !n.isNumber()) {
            return null;
        }
        return n.asInt();
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull() || !n.isTextual()) {
            return null;
        }
        String s = n.asText();
        return s == null || s.isBlank() ? null : s.trim();
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
