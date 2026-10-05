package com.thingworx.things.agent.tools;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.Thing;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.cache.ArtifactCacheTurnFaults;
import com.thingworx.things.agent.HistorySeriesComposerSupport;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.IntegerPrimitive;
import com.thingworx.types.primitives.LongPrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

import org.slf4j.Logger;
import com.thingworx.logging.LogUtilities;

/**
 * Batch property reads and numeric property history + aggregates.
 *
 * @see docs/agent/property_value.md
 */
public final class PropertyToolsExecutor {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(PropertyToolsExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int MAX_PROPERTY_NAMES = 40;
    private static final int MAX_HISTORY_ROWS = 5000;
    private static final int NUMERIC_HISTORY_LLM_SAMPLE_ROWS = 20;
    static final String NUMERIC_HISTORY_COMPACT_FORMAT = "parler.numeric_history.compact.v1";
    static final String CODE_PROPERTY_METADATA_UNRESOLVED = "PROPERTY_METADATA_UNRESOLVED";
    static final String CODE_PROPERTY_NOT_FOUND = "PROPERTY_NOT_FOUND";

    /**
     * When non-null, replaces the reflective {@code getInstancePropertyDefinitions} read in
     * {@link #resolvePropertyDefinitionOutcome(Thing, String, String)} (unit tests only; cleared in
     * {@link #clearPropertyDefinitionLookupOverrideForTests()}).
     */
    @FunctionalInterface
    interface PropertyDefinitionLookupForTests {
        PropertyDefinitionOutcome apply(Thing thing, String propertyName) throws Exception;
    }

    static volatile PropertyDefinitionLookupForTests propertyDefinitionLookupOverrideForTests;

    /** Package-private outcome of the TR-3 definition guard (before history dispatch). */
    static final class PropertyDefinitionOutcome {
        private final boolean absent;
        private final BaseTypes baseType;

        private PropertyDefinitionOutcome(boolean absent, BaseTypes baseType) {
            this.absent = absent;
            this.baseType = baseType;
        }

        static PropertyDefinitionOutcome absent() {
            return new PropertyDefinitionOutcome(true, null);
        }

        static PropertyDefinitionOutcome found(BaseTypes baseType) {
            return new PropertyDefinitionOutcome(false, baseType);
        }

        boolean isAbsent() {
            return absent;
        }

        boolean isNumeric() {
            return baseType == BaseTypes.NUMBER || baseType == BaseTypes.INTEGER || baseType == BaseTypes.LONG;
        }
    }

    static void clearPropertyDefinitionLookupOverrideForTests() {
        propertyDefinitionLookupOverrideForTests = null;
    }

    /** Aligned with Python {@code build_chart_from_history} / {@code CONTRACTS/CHART_CONTRACT.md}. */
    private static final Set<String> CHART_REF_ROLES = Set.of(
            "usl", "ucl", "lcl", "lsl", "target", "limit", "warning");

    private PropertyToolsExecutor() {}

    public static String executeGetPropertyValues(ToolCall call) {
        try {
            return doGetPropertyValues(call);
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            LOG.warn("get_property_values failed: {}", msg, e);
            return errorJson("GET_PROPERTY_VALUES_ERROR", msg);
        } catch (Exception e) {
            ArtifactCacheTurnFaults.rethrowRepositoryUnavailable(e);
            LOG.warn("get_property_values failed: {}", e.getMessage(), e);
            return errorJson("GET_PROPERTY_VALUES_ERROR", e.getMessage());
        }
    }

    public static String executeQueryPropertyHistory(ToolCall call) {
        return wrapHistoryFailure(() -> doQueryPropertyHistory(call));
    }

    static String wrapHistoryFailure(Callable<String> action) {
        try {
            return action.call();
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            LOG.warn("query_property_history failed: {}", msg, e);
            return errorJson("QUERY_PROPERTY_HISTORY_ERROR", msg);
        } catch (Exception e) {
            ArtifactCacheTurnFaults.rethrowRepositoryUnavailable(e);
            LOG.warn("query_property_history failed: {}", e.getMessage(), e);
            return errorJson("QUERY_PROPERTY_HISTORY_ERROR", e.getMessage());
        }
    }

    @Deprecated
    /** @deprecated Legacy entry; delegates to {@link #executeQueryPropertyHistory}. */
    public static String executeQueryNumericPropertyHistory(ToolCall call) {
        return executeQueryPropertyHistory(call);
    }

    private static String doGetPropertyValues(ToolCall call) throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
        ScalarThingnamePreflight.ApplicationThingGateOutcome thingGate =
                ScalarThingnamePreflight.gateApplicationThing("thingName", text(root, "thingName"));
        if (thingGate.isError()) {
            return thingGate.errorJson;
        }
        Thing thing = thingGate.thing;
        String canonicalThingName = thingGate.canonicalThingName;
        JsonNode namesNode = root.get("propertyNames");
        if (namesNode == null || !namesNode.isArray() || namesNode.size() == 0) {
            return errorJson("MISSING_PROPERTY_NAMES", "propertyNames must be a non-empty JSON array of strings");
        }
        List<String> names = new ArrayList<>();
        for (JsonNode n : namesNode) {
            if (n != null && n.isTextual()) {
                String s = n.asText().trim();
                if (!s.isEmpty()) {
                    names.add(s);
                }
            }
        }
        if (names.isEmpty()) {
            return errorJson("MISSING_PROPERTY_NAMES", "propertyNames must contain at least one non-empty name");
        }
        if (names.size() > MAX_PROPERTY_NAMES) {
            LOG.warn("get_property_values TOO_MANY_PROPERTIES thing={} count={}", canonicalThingName, names.size());
            return errorJson("TOO_MANY_PROPERTIES", "At most " + MAX_PROPERTY_NAMES + " properties per call");
        }

        LOG.info("get_property_values start: thing={} propertyCount={} names={}", canonicalThingName, names.size(),
                names);
        ArrayNode arr = MAPPER.createArrayNode();
        int okCount = 0;
        int failCount = 0;
        for (String pn : names) {
            ObjectNode row = MAPPER.createObjectNode();
            row.put("name", pn);
            try {
                BaseTypes propertyBaseType = ProtectedValuePolicy.propertyBaseType(thing, pn);
                if (propertyBaseType == null) {
                    row.put("ok", false);
                    row.put("code", CODE_PROPERTY_METADATA_UNRESOLVED);
                    row.put("baseType", "UNKNOWN");
                    row.put("message", "Property metadata could not be resolved; verify the exact ThingWorx property "
                            + "name with discover_thing_members (facet=properties) before reading.");
                    failCount++;
                    LOG.warn("get_property_values METADATA_UNRESOLVED: thing={} property={}", canonicalThingName, pn);
                    arr.add(row);
                    continue;
                }
                if (ProtectedValuePolicy.isProtectedBaseType(propertyBaseType)) {
                    row.put("ok", false);
                    row.put("code", ProtectedValuePolicy.CODE_READ_BLOCKED);
                    row.put("baseType", propertyBaseType.name());
                    row.put("message", "This value is protected and cannot be read by the agent.");
                    failCount++;
                    LOG.info("get_property_values PROTECTED: thing={} property={}", canonicalThingName, pn);
                    arr.add(row);
                    continue;
                }
                IPrimitiveType pv = readPropertyPrimitive(thing, pn);
                if (pv == null) {
                    row.put("ok", false);
                    row.put("code", "NO_VALUE");
                    row.put("message", "Property has no value or is not readable");
                    failCount++;
                    LOG.warn("get_property_values NO_VALUE: thing={} property={}", canonicalThingName, pn);
                } else {
                    row.put("ok", true);
                    row.put("baseType", pv.getBaseType() != null ? pv.getBaseType().name() : "UNKNOWN");
                    row.set("value", primitiveToJsonNode(pv));
                    okCount++;
                }
            } catch (Exception ex) {
                row.put("ok", false);
                row.put("code", "READ_FAILED");
                row.put("message", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                failCount++;
                LOG.warn("get_property_values READ_FAILED: thing={} property={} — {}", canonicalThingName, pn,
                        ex.getMessage());
            }
            arr.add(row);
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("thingName", canonicalThingName);
        out.set("properties", arr);
        LOG.info("get_property_values done: thing={} ok={} failed={}", canonicalThingName, okCount, failCount);
        return MAPPER.writeValueAsString(out);
    }

    private static String doQueryPropertyHistory(ToolCall call) throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
        ScalarThingnamePreflight.ApplicationThingGateOutcome thingGate =
                ScalarThingnamePreflight.gateApplicationThing("thingName", text(root, "thingName"));
        if (thingGate.isError()) {
            return thingGate.errorJson;
        }
        return queryPropertyHistoryAfterGate(call, thingGate.thing, thingGate.canonicalThingName);
    }

    /**
     * Everything after the Thing gate: property-name validation, TR-3 definition guard, then the
     * unchanged numeric or value-stream dispatch. Package-private for unit tests that bypass the gate.
     */
    static String queryPropertyHistoryAfterGate(ToolCall call, Thing thing, String canonicalThingName)
            throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
        String propertyName = text(root, "propertyName");
        if (propertyName == null || propertyName.isEmpty()) {
            LOG.warn("query_property_history MISSING_PROPERTY_NAME");
            return errorJson("MISSING_PROPERTY_NAME", "propertyName is required");
        }
        PropertyDefinitionOutcome outcome =
                resolvePropertyDefinitionOutcome(thing, propertyName, canonicalThingName);
        if (outcome.isAbsent()) {
            return propertyNotFoundError(canonicalThingName, propertyName);
        }
        if (outcome.isNumeric()) {
            return doQueryNumericPropertyHistory(call, thing, canonicalThingName);
        }
        String nonNumericActionsErr = nonNumericPropertyHistoryActionsError(root);
        if (nonNumericActionsErr != null) {
            return nonNumericActionsErr;
        }
        return doQueryValueStreamPropertyHistoryCompact(call, thing, root, canonicalThingName);
    }

    static PropertyDefinitionOutcome resolvePropertyDefinitionOutcome(Thing thing, String propertyName,
            String canonicalThingName) throws Exception {
        if (propertyDefinitionLookupOverrideForTests != null) {
            return propertyDefinitionLookupOverrideForTests.apply(thing, propertyName);
        }
        Object coll = thing.getClass().getMethod("getInstancePropertyDefinitions").invoke(thing);
        if (coll == null) {
            throw new IllegalStateException(
                    "property definitions unavailable for " + canonicalThingName);
        }
        Object vals = coll.getClass().getMethod("values").invoke(coll);
        if (!(vals instanceof Iterable)) {
            throw new IllegalStateException(
                    "property definitions unavailable for " + canonicalThingName);
        }
        for (Object pd : (Iterable<?>) vals) {
            Object name = pd.getClass().getMethod("getName").invoke(pd);
            if (!propertyName.equals(name)) {
                continue;
            }
            BaseTypes baseType = null;
            try {
                Object bt = pd.getClass().getMethod("getBaseType").invoke(pd);
                if (bt instanceof BaseTypes) {
                    baseType = (BaseTypes) bt;
                }
            } catch (Exception ignored) {
                // unreadable base type — same as isNumericProperty skipping the definition
            }
            return PropertyDefinitionOutcome.found(baseType);
        }
        return PropertyDefinitionOutcome.absent();
    }

    private static String propertyNotFoundError(String canonicalThingName, String propertyName) {
        try {
            ObjectNode rh = MAPPER.createObjectNode();
            rh.put("tool", "discover_thing_members");
            rh.put("argument", "namePrefix");
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", CODE_PROPERTY_NOT_FOUND);
            o.put("message",
                    "Property \"" + propertyName + "\" is not defined on Thing \"" + canonicalThingName
                            + "\" (own, template, shape or package definitions). No history was queried. "
                            + "List the exact property names with discover_thing_members (facet \"properties\").");
            o.put("thingName", canonicalThingName);
            o.put("propertyName", propertyName);
            o.set("recoveryHint", rh);
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"" + CODE_PROPERTY_NOT_FOUND + "\"}";
        }
    }

    /**
     * On the non-numeric branch, reject any {@code actions} value that is not absent or an empty array — including
     * non-textual array elements and non-array shapes — so the model never gets a plausible history body when aggregate
     * semantics do not apply. Package-private for unit tests in this package.
     */
    static String nonNumericPropertyHistoryActionsError(JsonNode root) {
        if (!root.has("actions")) {
            return null;
        }
        JsonNode an = root.get("actions");
        if (an.isNull()) {
            return errorJson("INVALID_ACTIONS_SHAPE",
                    "actions must be a JSON array of aggregate names when provided; omit the field for non-numeric "
                            + "property history.");
        }
        if (!an.isArray()) {
            return errorJson("INVALID_ACTIONS_SHAPE",
                    "actions must be a JSON array of aggregate names when provided; omit the field for non-numeric "
                            + "property history.");
        }
        if (an.size() == 0) {
            return null;
        }
        return errorJson("NUMERIC_ACTIONS_UNSUPPORTED_FOR_PROPERTY_TYPE",
                "actions are supported only for NUMBER/INTEGER/LONG properties. "
                        + "Omit actions for this property or pick a numeric trend field.");
    }

    /** E9/B1: published clamp-echo plus retained alias key for older consumers. */
    private static void putHistoryLimitEcho(ObjectNode extras, HistoryRowLimitPrecedence.Resolved limit) {
        if (extras == null || limit == null) {
            return;
        }
        extras.put("maxItemsRequested", limit.requested);
        extras.put("maxItemsEffective", limit.effective);
        extras.put("maxItemsSource", limit.sourceField);
        extras.put("maxRowsRequested", limit.effective);
    }

    private static void putAppliedWindowOnExtrasIfPresent(ObjectNode extras, DateTime start, DateTime end,
            String appliedSource, String appliedTz) {
        if (start == null || end == null || appliedSource == null) {
            return;
        }
        ParlerAppliedTimeWindowJson.putClosedOpenWindow(extras, appliedSource,
                Instant.ofEpochMilli(start.getMillis()), Instant.ofEpochMilli(end.getMillis()), appliedTz);
    }

    private static String doQueryValueStreamPropertyHistoryCompact(ToolCall call, Thing thing, JsonNode root,
            String canonicalThingName) throws Exception {
        String thingName = canonicalThingName;
        String propertyName = text(root, "propertyName");

        HistoryRowLimitPrecedence.Resolved limit =
                HistoryRowLimitPrecedence.resolve(root, MAX_HISTORY_ROWS);
        int maxRows = limit.effective;

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

        LOG.info("query_property_history value_stream: thing={} property={} maxRows={} start={} end={}",
                thingName, propertyName, maxRows, start, end);

        ValueCollection vc = new ValueCollection();
        vc.put("propertyName", new StringPrimitive(propertyName));
        vc.put("maxItems", new IntegerPrimitive(maxRows));
        vc.put("oldestFirst", new BooleanPrimitive(true));
        if (start != null && end != null) {
            vc.put("startDate", new DatetimePrimitive(start));
            vc.put("endDate", new DatetimePrimitive(end));
        }

        InfoTable table;
        try {
            table = PlatformAccess.invokeAsUser(thing, "QueryPropertyHistory", vc);
        } catch (Exception e) {
            LOG.warn("query_property_history QueryPropertyHistory failed: thing={} property={} — {}",
                    thingName, propertyName, e.getMessage());
            return errorJson("VALUE_STREAM_HISTORY_QUERY_FAILED",
                    e.getMessage() != null ? e.getMessage()
                            : "QueryPropertyHistory failed. Ensure the Thing has a value stream for this property.");
        }

        ObjectNode extras = MAPPER.createObjectNode();
        extras.put("tool", "query_property_history");
        extras.put("thingName", thingName);
        extras.put("propertyName", propertyName);
        extras.put("serviceInvoked", "QueryPropertyHistory");
        // S2: value-stream path always queries oldest-first; sampleRows are prefix of that order.
        extras.put("historyOrder", "oldest_first");
        extras.put("oldestFirst", true);
        putHistoryLimitEcho(extras, limit);
        putAppliedWindowOnExtrasIfPresent(extras, start, end, appliedSource, appliedTz);
        // Stated here, from the platform's table and this read's effective limit (design tabular-reach 7.10).
        return InvokeServiceExecutor.formatPropertyHistoryValueStreamCompact(table, thing, extras,
                NUMERIC_HISTORY_LLM_SAMPLE_ROWS, com.thingworx.things.agent.source.ReadLimitFact.observe(table, maxRows));
    }

    private static String doQueryNumericPropertyHistory(ToolCall call, Thing thing, String canonicalThingName)
            throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
        String propertyName = text(root, "propertyName");
        if (propertyName == null || propertyName.isEmpty()) {
            LOG.warn("query_numeric_property_history MISSING_PROPERTY_NAME");
            return errorJson("MISSING_PROPERTY_NAME", "propertyName is required");
        }
        if (!isNumericProperty(thing, propertyName)) {
            LOG.warn("query_numeric_property_history NOT_NUMERIC: thing={} property={}", canonicalThingName,
                    propertyName);
            return errorJson("NOT_NUMERIC_PROPERTY",
                    "Property \"" + propertyName
                            + "\" must be NUMBER, INTEGER, or LONG for the numeric history branch (use unified "
                            + "query_property_history for all property types).");
        }

        HistoryRowLimitPrecedence.Resolved limit =
                HistoryRowLimitPrecedence.resolve(root, MAX_HISTORY_ROWS);
        int maxRows = limit.effective;

        // Identify which field carries the non-textual value so the wire envelope can populate
        // rejectedParameter (cross-tool convention).
        String shapeBadField = ToolJsonTimeBounds.firstNonTextualField(root, "startTime", "endTime", "start",
                "end");
        if (shapeBadField != null) {
            return BuiltInToolTimeErrorJson.error("INVALID_TIME_SPEC_SHAPE",
                    shapeBadField + " must be a JSON string.", shapeBadField);
        }

        // Preserve the user-supplied alias label
        // (start vs startTime, end vs endTime) so INVALID_TIME_RANGE wire envelopes can carry the exact
        // rejectedParameter the LLM would use to retry. pickAliasLabel + firstNonBlank must traverse the
        // same alias order so label and value agree.
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
        String appliedTimeWindowSource = null;
        String appliedTimeWindowTz = null;
        if (!nlWindow.skip) {
            start = utcDateTime(nlWindow.resolution.getStartUtc());
            end = utcDateTime(nlWindow.resolution.getEndUtc());
            appliedTimeWindowSource =
                    nlWindow.kind == BuiltInToolNaturalTimeWindow.AppliedKind.CALENDAR_DAY_ENGLISH
                            ? "NATURAL_LANGUAGE_CALENDAR_DAY"
                            : "NATURAL_LANGUAGE_RELATIVE_DURATION";
            if (nlWindow.kind == BuiltInToolNaturalTimeWindow.AppliedKind.CALENDAR_DAY_ENGLISH) {
                appliedTimeWindowTz = AgentToolContext.getUserIanaTimezone();
            }
        } else {
            ExplicitIsoTimeBounds.ParseOutcome iso =
                    ExplicitIsoTimeBounds.parseOptionalPair(startStr, endStr, startLabel, endLabel);
            if (!iso.ok) {
                LOG.warn("query_numeric_property_history {}: thing={} property={} field={}",
                        iso.errorCode, canonicalThingName, propertyName, iso.failedField);
                // When the parse error identifies a single bound,
                // surface it via rejectedParameter on the cross-tool envelope; cross-cutting failures
                // (the "both or neither" rule) carry failedField=null and are emitted as a plain envelope.
                return BuiltInToolTimeErrorJson.error(iso.errorCode, iso.errorMessage, iso.failedField);
            }
            start = iso.start;
            end = iso.end;
            if (start != null && end != null) {
                appliedTimeWindowSource = "EXPLICIT_ISO";
            }
        }

        List<String> actionStrs = new ArrayList<>();
        JsonNode an = root.get("actions");
        if (an != null && an.isArray()) {
            for (JsonNode x : an) {
                if (x != null && x.isTextual()) {
                    actionStrs.add(x.asText());
                }
            }
        }
        List<NumericSeriesAggregateAction> actions;
        try {
            actions = NumericSeriesAggregateAction.parseList(actionStrs);
        } catch (IllegalArgumentException e) {
            LOG.warn("query_numeric_property_history INVALID_ACTIONS: {}", e.getMessage());
            return errorJson("INVALID_ACTIONS", e.getMessage());
        }

        LOG.info("query_numeric_property_history start: thing={} property={} maxRows={} actions={} start={} end={}",
                canonicalThingName, propertyName, maxRows, actionStrs, start, end);

        InfoTable history;
        try {
            history = queryPropertyHistoryTable(thing, propertyName, start, end, maxRows);
        } catch (Exception e) {
            LOG.warn("query_numeric_property_history HISTORY_QUERY_FAILED: thing={} property={} — {}",
                    canonicalThingName, propertyName, e.getMessage());
            return errorJson("HISTORY_QUERY_FAILED",
                    e.getMessage() + " If the Thing has no value stream for this property, history may be unavailable.");
        }
        if (history == null || history.getRowCount() == 0) {
            LOG.info("query_numeric_property_history empty: thing={} property={}", canonicalThingName, propertyName);
            ObjectNode out = MAPPER.createObjectNode();
            out.put("status", "success");
            out.put("$format", NUMERIC_HISTORY_COMPACT_FORMAT);
            out.put("resultKind", actions.isEmpty() ? "NUMERIC_HISTORY_INLINE" : "NUMERIC_HISTORY_AGGREGATES");
            out.put("thingName", canonicalThingName);
            out.put("propertyName", propertyName);
            out.put("totalRows", 0);
            out.put("returnedRows", 0);
            out.put("sampleOnly", false);
            out.put("rowsOmitted", false);
            out.set("columns", numericHistoryColumns(null));
            out.putArray("sampleRows");
            if (!actions.isEmpty()) {
                out.set("aggregates", MAPPER.createObjectNode());
            }
            out.put("chartEmitted", false);
            putHistoryLimitEcho(out, limit);
            putRequestedTimeRangeOnHistoryResult(out, start, end);
            putAppliedTimeWindowOnHistoryResult(out, start, end, appliedTimeWindowSource, appliedTimeWindowTz);
            mergeRequestedTimeRangeFromToolArgsIfAbsent(root, out);
            mergeChartHintsFromArguments(root, out);
            return MAPPER.writeValueAsString(out);
        }

        List<Double> values = new ArrayList<>();
        List<ObjectNode> pointNodes = new ArrayList<>();
        String valueCol = detectValueColumn(history, propertyName);
        String timeCol = detectTimestampColumn(history);
        for (int i = 0; i < history.getRowCount(); i++) {
            ValueCollection row = history.getRow(i);
            Double d = extractDouble(row, valueCol);
            if (d != null && !d.isNaN() && !d.isInfinite()) {
                values.add(d);
            }
            ObjectNode pt = MAPPER.createObjectNode();
            if (timeCol != null) {
                Object ts = row.getValue(timeCol);
                pt.put("timestamp", ts != null ? ts.toString() : "");
            }
            if (d != null) {
                pt.put("value", d);
            } else {
                pt.putNull("value");
            }
            pointNodes.add(pt);
        }
        double[] arr = values.stream().mapToDouble(Double::doubleValue).toArray();

        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("thingName", canonicalThingName);
        out.put("propertyName", propertyName);
        out.put("sampleCount", arr.length);

        if (!actions.isEmpty()) {
            Map<String, Double> agg = NumericSeriesAggregator.aggregateAll(arr, actions);
            ObjectNode aggNode = MAPPER.createObjectNode();
            for (Map.Entry<String, Double> e : agg.entrySet()) {
                aggNode.put(e.getKey(), e.getValue());
            }
            out.set("aggregates", aggNode);
        }

        ArrayNode pointsOut = MAPPER.createArrayNode();
        for (ObjectNode pt : pointNodes) {
            pointsOut.add(pt);
        }
        out.set("points", pointsOut);
        boolean hitRowCap = history.getRowCount() >= maxRows;
        out.put("pointsTruncated", hitRowCap);
        // Same observation, taken on the platform's table before invalid points were dropped.
        com.thingworx.things.agent.source.ReadLimitFact readLimit = com.thingworx.things.agent.source.ReadLimitFact.observe(history, maxRows);
        InvokeServiceExecutor.putReadLimitEvidence(out, readLimit);
        out.put("pointsReturned", pointsOut.size());
        out.put("pointsTotal", pointNodes.size());
        putHistoryLimitEcho(out, limit);
        LOG.info("query_numeric_property_history done: thing={} property={} rows={} numericSamples={} pointsReturned={} hitRowCap={} aggregates={}",
                canonicalThingName, propertyName, history.getRowCount(), arr.length, pointsOut.size(),
                hitRowCap, !actions.isEmpty());
        putRequestedTimeRangeOnHistoryResult(out, start, end);
        putAppliedTimeWindowOnHistoryResult(out, start, end, appliedTimeWindowSource, appliedTimeWindowTz);
        mergeRequestedTimeRangeFromToolArgsIfAbsent(root, out);
        mergeChartHintsFromArguments(root, out);
        StoredSeriesCache stored = cacheNumericHistorySeries(pointNodes, thing, propertyName, readLimit);
        String cacheId = stored != null ? stored.cacheId() : null;
        boolean chartEmitted = false;
        if (call.getId() != null && !call.getId().isEmpty()) {
            AgentToolContext.setToolEgressFullJsonForToolCall(call.getId(), MAPPER.writeValueAsString(out));
            chartEmitted = actions.isEmpty();
        }
        ObjectNode compact = compactNumericHistoryResult(out, pointNodes, actions, stored, hitRowCap, chartEmitted);
        return MAPPER.writeValueAsString(compact);
    }

    static ObjectNode compactNumericHistoryResult(ObjectNode full, List<ObjectNode> pointNodes,
            List<NumericSeriesAggregateAction> actions, StoredSeriesCache stored, boolean hitRowCap,
            boolean chartEmitted) {
        String cacheId = stored != null ? stored.cacheId() : null;
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("$format", NUMERIC_HISTORY_COMPACT_FORMAT);
        boolean aggregateOnly = actions != null && !actions.isEmpty();
        out.put("resultKind", aggregateOnly ? "NUMERIC_HISTORY_AGGREGATES" : "NUMERIC_HISTORY_INLINE");
        copyIfPresent(full, out, "thingName");
        copyIfPresent(full, out, "propertyName");
        int totalRows = pointNodes != null ? pointNodes.size() : 0;
        out.put("totalRows", totalRows);
        out.set("columns", numericHistoryColumns(stored));
        if (stored != null && stored.hasRoles()) {
            out.put("timeColumn", stored.timeColumn());
            out.put("valueColumn", stored.valueColumn());
        }
        if (full != null && full.has("aggregates")) {
            out.set("aggregates", full.get("aggregates"));
        }
        if (aggregateOnly) {
            out.put("returnedRows", 0);
            out.put("sampleOnly", totalRows > 0);
            out.put("rowsOmitted", totalRows > 0);
            out.putArray("sampleRows");
        } else {
            int returned = Math.min(NUMERIC_HISTORY_LLM_SAMPLE_ROWS, totalRows);
            out.put("returnedRows", returned);
            out.put("sampleOnly", totalRows > returned);
            out.put("rowsOmitted", totalRows > returned);
            ArrayNode sampleRows = MAPPER.createArrayNode();
            for (int i = 0; i < returned; i++) {
                sampleRows.add(numericPointToRow(pointNodes.get(i)));
            }
            out.set("sampleRows", sampleRows);
        }
        if (cacheId != null && !cacheId.isEmpty()) {
            out.put("cacheId", cacheId);
        }
        out.put("chartEmitted", chartEmitted);
        out.put("pointsTruncated", hitRowCap);
        copyIfPresent(full, out, com.thingworx.things.agent.source.ReadLimitFact.FIELD_REACHED);
        copyIfPresent(full, out, com.thingworx.things.agent.source.ReadLimitFact.FIELD_NOTE);
        copyIfPresent(full, out, "maxItemsRequested");
        copyIfPresent(full, out, "maxItemsEffective");
        copyIfPresent(full, out, "maxItemsSource");
        copyIfPresent(full, out, "maxRowsRequested");
        copyIfPresent(full, out, "requested_time_range");
        copyIfPresent(full, out, "applied_time_window");
        copyIfPresent(full, out, "chart_kind");
        copyIfPresent(full, out, "chart_title");
        copyIfPresent(full, out, "chart_x_label");
        copyIfPresent(full, out, "chart_y_label");
        copyIfPresent(full, out, "y_reference_lines");
        if (aggregateOnly) {
            out.put("hint", cacheId != null && !cacheId.isEmpty()
                    ? "Full rows were cached and not placed in the LLM prompt. Use deterministic cached-table tools "
                            + "or aggregate actions for further computation."
                    : "Full rows were not placed in the LLM prompt; cache registration was unavailable.");
        } else if (totalRows > NUMERIC_HISTORY_LLM_SAMPLE_ROWS) {
            out.put("hint", cacheId != null && !cacheId.isEmpty()
                    ? "Full rows were cached and not placed in the LLM prompt. Use cacheId for deterministic "
                            + "follow-up computation."
                    : "Full rows were not placed in the LLM prompt; cache registration was unavailable.");
        }
        return out;
    }

    /** Columns as written by {@link NumericHistoryCacheWriter}; the canonical shape when nothing was stored. */
    private static ArrayNode numericHistoryColumns(StoredSeriesCache stored) {
        ArrayNode columns = MAPPER.createArrayNode();
        List<com.thingworx.things.agent.cache.TypedColumn> cols = stored != null
                ? stored.columns()
                : NumericHistoryCacheWriter.canonicalColumns();
        for (com.thingworx.things.agent.cache.TypedColumn c : cols) {
            ObjectNode col = MAPPER.createObjectNode();
            col.put("name", c.name());
            col.put("baseType", c.baseType().name());
            columns.add(col);
        }
        return columns;
    }

    private static ObjectNode numericPointToRow(ObjectNode point) {
        ObjectNode row = MAPPER.createObjectNode();
        JsonNode ts = point != null ? point.get("timestamp") : null;
        row.set("timestamp", ts == null || ts.isNull() ? MAPPER.nullNode() : ts);
        JsonNode value = point != null ? point.get("value") : null;
        row.set("value", value == null || value.isNull() ? MAPPER.nullNode() : value);
        return row;
    }

    private static void copyIfPresent(ObjectNode src, ObjectNode dst, String field) {
        if (src != null && dst != null && src.has(field)) {
            dst.set(field, src.get(field));
        }
    }

    static StoredSeriesCache cacheNumericHistorySeries(List<ObjectNode> pointNodes, Thing thing,
            String propertyName, com.thingworx.things.agent.source.ReadLimitFact readLimit) {
        if (pointNodes == null || pointNodes.isEmpty()) {
            return null;
        }
        try {
            InfoTable table = NumericHistoryCacheWriter.newTable();
            for (ObjectNode point : pointNodes) {
                JsonNode tsNode = point != null ? point.get("timestamp") : null;
                JsonNode v = point != null ? point.get("value") : null;
                NumericHistoryCacheWriter.addRow(table,
                        tsNode != null && !tsNode.isNull() ? tsNode.asText("") : "",
                        v != null && v.isNumber() ? v.asDouble() : null);
            }
            // Roles are declared before provenance is attached; attachPropertyProvenance is a
            // same-artifact rebuild and keeps them (CM-0).
            return NumericHistoryCacheWriter.store(table, "query_numeric_property_history",
                    thing != null ? thing.getName() : null, propertyName,
                    d -> com.thingworx.things.agent.source.SourceDescriptorSupport.withReadLimitFact(
                            com.thingworx.things.agent.semantics.SemanticSourceHandoff.attachPropertyProvenance(
                                    d, thing, propertyName),
                            readLimit));
        } catch (Exception e) {
            LOG.warn("query_numeric_property_history cache registration skipped: {}", e.getMessage());
            return null;
        }
    }

    private static DateTime utcDateTime(Instant instant) {
        return new DateTime(instant.toEpochMilli(), DateTimeZone.UTC);
    }

    private static void putAppliedTimeWindowOnHistoryResult(ObjectNode out, DateTime start, DateTime end,
            String source, String timezoneBasisOrNull) {
        if (start == null || end == null || source == null) {
            return;
        }
        ParlerAppliedTimeWindowJson.putClosedOpenWindow(out, source,
                Instant.ofEpochMilli(start.getMillis()), Instant.ofEpochMilli(end.getMillis()), timezoneBasisOrNull);
    }

    /**
     * Echoes the resolved query window so {@link com.thingworx.things.agent.ParlerChartWireSupport} can emit
     * {@code ChartBlock.requested_time_range} for time-axis domain (dev: no backward-compat constraint).
     */
    private static void putRequestedTimeRangeOnHistoryResult(ObjectNode out, DateTime start, DateTime end) {
        if (start == null || end == null) {
            return;
        }
        ObjectNode r = MAPPER.createObjectNode();
        r.put("start", start.toString());
        r.put("end", end.toString());
        out.set("requested_time_range", r);
    }

    /**
     * When the query used the platform default window ({@code start}/{@code end} null), copy optional
     * {@code requestedTimeRange} or {@code requested_time_range} from tool arguments so the chart X domain can
     * still match a user-described interval (ISO-8601 strings). Does not override server-resolved bounds.
     */
    private static void mergeRequestedTimeRangeFromToolArgsIfAbsent(JsonNode root, ObjectNode out) {
        if (root == null || out == null || out.has("requested_time_range")) {
            return;
        }
        JsonNode block = root.get("requestedTimeRange");
        if (block == null || !block.isObject()) {
            block = root.get("requested_time_range");
        }
        if (block == null || !block.isObject()) {
            return;
        }
        JsonNode s = block.get("start");
        JsonNode e = block.get("end");
        if (s == null || !s.isTextual() || e == null || !e.isTextual()) {
            return;
        }
        String ss = s.asText().trim();
        String ee = e.asText().trim();
        if (ss.isEmpty() || ee.isEmpty()) {
            return;
        }
        ObjectNode r = MAPPER.createObjectNode();
        r.put("start", ss);
        r.put("end", ee);
        out.set("requested_time_range", r);
    }

    /**
     * Echoes optional chart styling from tool arguments into the tool result JSON so
     * {@link com.thingworx.things.agent.ParlerChartWireSupport} can build a full {@code ChartBlock}
     * (parity with Python {@code app.charts.builder.build_chart_from_history_payload}).
     */
    private static void mergeChartHintsFromArguments(JsonNode root, ObjectNode out) {
        if (root == null || out == null) {
            return;
        }
        String kind = text(root, "kind");
        if (kind != null && !kind.isBlank()) {
            String k = kind.trim().toLowerCase();
            if (k.equals("line") || k.equals("bar") || k.equals("scatter")) {
                out.put("chart_kind", k);
            }
        }
        String chartTitle = text(root, "title");
        if (chartTitle != null && !chartTitle.isBlank()) {
            out.put("chart_title", chartTitle.trim());
        }
        String xl = text(root, "x_label");
        if (xl != null && !xl.isBlank()) {
            out.put("chart_x_label", xl.trim());
        }
        String yl = text(root, "y_label");
        if (yl != null && !yl.isBlank()) {
            out.put("chart_y_label", yl.trim());
        }
        JsonNode yrl = root.get("y_reference_lines");
        if (yrl == null || !yrl.isArray()) {
            return;
        }
        ArrayNode arr = MAPPER.createArrayNode();
        int n = 0;
        for (JsonNode item : yrl) {
            if (n >= 12) {
                break;
            }
            if (item == null || !item.isObject()) {
                continue;
            }
            if (!item.has("y") || item.get("y").isNull()) {
                continue;
            }
            double y = item.get("y").asDouble();
            if (Double.isNaN(y) || Double.isInfinite(y)) {
                continue;
            }
            ObjectNode line = MAPPER.createObjectNode();
            line.put("y", y);
            JsonNode lab = item.get("label");
            if (lab != null && lab.isTextual() && !lab.asText().isBlank()) {
                line.put("label", lab.asText().trim());
            }
            String role = "limit";
            JsonNode r = item.get("role");
            if (r != null && r.isTextual()) {
                String rs = r.asText().trim().toLowerCase();
                if (CHART_REF_ROLES.contains(rs)) {
                    role = rs;
                }
            }
            line.put("role", role);
            arr.add(line);
            n++;
        }
        if (arr.size() > 0) {
            out.set("y_reference_lines", arr);
        }
    }

    private static IPrimitiveType readPropertyPrimitive(Thing thing, String name) throws Exception {
        return PropertyValueReads.readCurrentValue(thing, name);
    }

    private static com.fasterxml.jackson.databind.JsonNode primitiveToJsonNode(IPrimitiveType p) {
        if (p == null) {
            return MAPPER.getNodeFactory().nullNode();
        }
        BaseTypes bt = p.getBaseType();
        if (bt == BaseTypes.PASSWORD) {
            return MAPPER.getNodeFactory().textNode(ProtectedValuePolicy.mask());
        }
        try {
            if (bt == BaseTypes.BOOLEAN && p instanceof BooleanPrimitive) {
                return MAPPER.getNodeFactory().booleanNode(((BooleanPrimitive) p).getValue());
            }
            if (bt == BaseTypes.INTEGER && p instanceof IntegerPrimitive) {
                return MAPPER.getNodeFactory().numberNode(((IntegerPrimitive) p).getValue());
            }
            if (bt == BaseTypes.LONG && p instanceof LongPrimitive) {
                return MAPPER.getNodeFactory().numberNode(((LongPrimitive) p).getValue());
            }
            if (bt == BaseTypes.LONG && p instanceof IntegerPrimitive) {
                return MAPPER.getNodeFactory().numberNode((long) ((IntegerPrimitive) p).getValue());
            }
            if (bt == BaseTypes.NUMBER && p instanceof NumberPrimitive) {
                return MAPPER.getNodeFactory().numberNode(((NumberPrimitive) p).getValue());
            }
            if (p instanceof StringPrimitive) {
                return MAPPER.getNodeFactory().textNode(((StringPrimitive) p).getValue());
            }
            if (p instanceof DatetimePrimitive) {
                return MAPPER.getNodeFactory().textNode(((DatetimePrimitive) p).getValue().toString());
            }
        } catch (Exception ignored) {
            // fall through
        }
        return MAPPER.getNodeFactory().textNode(p.toString());
    }

    private static boolean isNumericProperty(Thing thing, String propertyName) {
        try {
            Object coll = thing.getClass().getMethod("getInstancePropertyDefinitions").invoke(thing);
            if (coll == null) {
                return false;
            }
            Object vals = coll.getClass().getMethod("values").invoke(coll);
            if (!(vals instanceof Iterable)) {
                return false;
            }
            for (Object pd : (Iterable<?>) vals) {
                try {
                    Object name = pd.getClass().getMethod("getName").invoke(pd);
                    if (!propertyName.equals(name)) {
                        continue;
                    }
                    Object bt = pd.getClass().getMethod("getBaseType").invoke(pd);
                    if (bt instanceof BaseTypes) {
                        BaseTypes b = (BaseTypes) bt;
                        return b == BaseTypes.NUMBER || b == BaseTypes.INTEGER || b == BaseTypes.LONG;
                    }
                } catch (Exception ignored) {
                    // next
                }
            }
        } catch (Exception e) {
            LOG.warn("isNumericProperty reflection error: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Shared history fetch used by {@link BuildHistoryOverlayChartExecutor} and related overlay paths.
     */
    public static InfoTable queryNumericPropertyHistoryTable(Thing thing, String propertyName, DateTime start,
            DateTime end, int maxRows) throws Exception {
        return queryPropertyHistoryTable(thing, propertyName, start, end, maxRows);
    }

    /** Package-visible for {@link BuildHistoryOverlayChartExecutor}. */
    public static boolean isChartableNumericProperty(Thing thing, String propertyName) {
        return isNumericProperty(thing, propertyName);
    }

    /**
     * Reads optional unit metadata from ThingWorx property aspects ({@code units}, {@code Units},
     * {@code unitOfMeasure}). Empty when absent.
     */
    public static String readPropertyUnitAspect(Thing thing, String propertyName) {
        if (thing == null || propertyName == null || propertyName.isBlank()) {
            return "";
        }
        try {
            Object coll = thing.getClass().getMethod("getInstancePropertyDefinitions").invoke(thing);
            if (coll == null) {
                return "";
            }
            Object vals = coll.getClass().getMethod("values").invoke(coll);
            if (!(vals instanceof Iterable)) {
                return "";
            }
            for (Object pd : (Iterable<?>) vals) {
                Object name = pd.getClass().getMethod("getName").invoke(pd);
                if (!propertyName.equals(name)) {
                    continue;
                }
                Object aspects = pd.getClass().getMethod("getAspects").invoke(pd);
                if (aspects == null) {
                    return "";
                }
                for (String key : new String[] {"units", "Units", "unitOfMeasure", "unit"}) {
                    Object v = aspects.getClass().getMethod("get", Object.class).invoke(aspects, key);
                    if (v != null) {
                        String s = v.toString().trim();
                        if (!s.isEmpty()) {
                            return s;
                        }
                    }
                }
                return "";
            }
        } catch (Exception e) {
            LOG.warn("readPropertyUnitAspect reflection error: {}", e.getMessage());
        }
        return "";
    }

    /**
     * PoP-only history extraction — never fabricates timestamps.
     */
    public static final class PopHistoryExtract {
        public final List<HistorySeriesComposerSupport.HistoryPoint> points;
        public final String errorCode;
        public final String errorMessage;

        private PopHistoryExtract(List<HistorySeriesComposerSupport.HistoryPoint> points, String errorCode,
                String errorMessage) {
            this.points = points != null ? points : List.of();
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        public static PopHistoryExtract ok(List<HistorySeriesComposerSupport.HistoryPoint> points) {
            return new PopHistoryExtract(points, null, null);
        }

        public static PopHistoryExtract error(String code, String message) {
            return new PopHistoryExtract(List.of(), code, message);
        }
    }

    /**
     * Extract timestamp/value pairs for PoP charts. Skips rows with unparseable timestamps; fails when
     * rows exist but no timestamp column is detected.
     */
    public static PopHistoryExtract extractPopNumericHistoryPoints(InfoTable history, String propertyName) {
        if (history == null || history.getRowCount() == 0) {
            return PopHistoryExtract.ok(List.of());
        }
        String timeCol = detectTimestampColumn(history);
        if (timeCol == null) {
            return PopHistoryExtract.error("POP_HISTORY_TIMESTAMP_UNRESOLVED",
                    "History rows lack a recognizable timestamp column for elapsed mapping.");
        }
        String valueCol = detectValueColumn(history, propertyName);
        List<HistorySeriesComposerSupport.HistoryPoint> out = new ArrayList<>();
        for (int i = 0; i < history.getRowCount(); i++) {
            ValueCollection row = history.getRow(i);
            Double d = extractDouble(row, valueCol);
            if (d == null || d.isNaN() || d.isInfinite()) {
                continue;
            }
            Instant ts = parsePopHistoryTimestamp(row, timeCol);
            if (ts == null) {
                continue;
            }
            out.add(new HistorySeriesComposerSupport.HistoryPoint(ts, d));
        }
        return PopHistoryExtract.ok(out);
    }

    private static Instant parsePopHistoryTimestamp(ValueCollection row, String timeCol) {
        if (timeCol == null) {
            return null;
        }
        Object ts = row.getValue(timeCol);
        if (ts == null) {
            return null;
        }
        try {
            if (ts instanceof org.joda.time.DateTime) {
                return Instant.ofEpochMilli(((org.joda.time.DateTime) ts).getMillis());
            }
            return Instant.parse(ts.toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static InfoTable queryPropertyHistoryTable(Thing thing, String propertyName,
            DateTime start, DateTime end, int maxRows) throws Exception {
        ValueCollection vc = new ValueCollection();
        vc.put("propertyName", new StringPrimitive(propertyName));
        vc.put("maxItems", new IntegerPrimitive(maxRows));
        vc.put("oldestFirst", new BooleanPrimitive(true));
        if (start != null && end != null) {
            vc.put("startDate", new DatetimePrimitive(start));
            vc.put("endDate", new DatetimePrimitive(end));
        }
        try {
            return PlatformAccess.invokeAsUser(thing, "QueryNumberPropertyHistory", vc);
        } catch (Exception e) {
            LOG.warn("QueryNumberPropertyHistory failed for {}.{}, fallback QueryPropertyHistory: {}",
                    thing.getName(), propertyName, e.getMessage());
            return PlatformAccess.invokeAsUser(thing, "QueryPropertyHistory", vc);
        }
    }

    private static String detectValueColumn(InfoTable it, String propertyName) {
        List<String> cols = columnNames(it);
        for (String c : new String[] {"value", "Value", propertyName}) {
            if (c != null && cols.contains(c)) {
                return c;
            }
        }
        for (String c : cols) {
            if (c != null && !c.equalsIgnoreCase("timestamp") && !c.equalsIgnoreCase("time")) {
                return c;
            }
        }
        return cols.isEmpty() ? null : cols.get(0);
    }

    private static String detectTimestampColumn(InfoTable it) {
        for (String c : columnNames(it)) {
            if (c != null && (c.equalsIgnoreCase("timestamp") || c.equalsIgnoreCase("time")
                    || c.equalsIgnoreCase("date"))) {
                return c;
            }
        }
        return null;
    }

    private static List<String> columnNames(InfoTable it) {
        List<String> names = new ArrayList<>();
        try {
            if (it.getDataShape() != null && it.getDataShape().getFields() != null) {
                for (FieldDefinition f : it.getDataShape().getFields().values()) {
                    if (f.getName() != null) {
                        names.add(f.getName());
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        if (names.isEmpty() && it.getRowCount() > 0) {
            ValueCollection row = it.getRow(0);
            try {
                Iterator<String> itKeys = row.keySet().iterator();
                while (itKeys.hasNext()) {
                    names.add(itKeys.next());
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        return names;
    }

    private static Double extractDouble(ValueCollection row, String col) {
        if (col == null) {
            return null;
        }
        Object v = row.getValue(col);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof IPrimitiveType) {
            try {
                if (v instanceof NumberPrimitive) {
                    return ((NumberPrimitive) v).getValue();
                }
                if (v instanceof IntegerPrimitive) {
                    return (double) ((IntegerPrimitive) v).getValue();
                }
                if (v instanceof LongPrimitive) {
                    return (double) ((LongPrimitive) v).getValue();
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        return n.asText();
    }

    /** First non-null, non-blank string, or null. */
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
            return "{\"status\":\"error\",\"code\":\"" + code + "\"}";
        }
    }
}
