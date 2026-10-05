package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.ToolResultEgressGateway;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;

/**
 * Builds {@code ALERT_SUMMARY_MULTI} rollup JSON for multi-Thing {@code query_alert_summary} (N≥2).
 */
final class AlertSummaryMultiRollup {

    static final String RESULT_KIND = "ALERT_SUMMARY_MULTI";
    static final int TOP_ALERTS_CAP = 3;
    static final int MAX_THING_NAMES_PER_CALL = 25;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AlertSummaryMultiRollup() {}

    static String thingNamesLimitExceededJson(int supplied, int max) {
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", "THING_NAMES_LIMIT_EXCEEDED");
            o.put("message",
                    "thingNames exceeds the maximum of " + max + " Things per call (supplied " + supplied
                            + "). Split the fleet or scope tighter.");
            o.put("parameterName", "thingNames");
            o.put("maxAllowed", max);
            o.put("suppliedCount", supplied);
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"THING_NAMES_LIMIT_EXCEEDED\",\"message\":\"serialization failed\"}";
        }
    }

    static String buildSuccessJson(int thingsRequested, List<ThingRollupPart> parts, ArrayNode identityErrors,
            ObjectNode extras) throws Exception {
        int failedIdentity = 0;
        int failedService = 0;
        int succeeded = 0;
        ArrayNode byThing = MAPPER.createArrayNode();
        for (ThingRollupPart p : parts) {
            byThing.add(p.entry);
            if (p.identityFailure) {
                failedIdentity++;
            } else if (p.serviceFailure) {
                failedService++;
            } else if (p.success) {
                succeeded++;
            }
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("status", "success");
        root.put("resultKind", RESULT_KIND);
        root.put("completeness",
                failedIdentity + failedService == 0 ? "complete" : "partial");
        root.put("thingsRequested", thingsRequested);
        root.put("thingsSucceeded", succeeded);
        root.put("thingsFailedIdentity", failedIdentity);
        root.put("thingsFailedService", failedService);
        root.set("byThing", byThing);
        if (identityErrors != null && identityErrors.size() > 0) {
            root.set("identityErrors", identityErrors);
        }
        ObjectNode extrasOut = extras != null ? extras.deepCopy() : MAPPER.createObjectNode();
        root.set("extras", extrasOut);
        return MAPPER.writeValueAsString(root);
    }

    static String buildAllServiceFailedJson(int thingsRequested, List<ThingRollupPart> parts, ArrayNode identityErrors,
            String dominantCode, String dominantMessage) throws Exception {
        ObjectNode err = MAPPER.createObjectNode();
        err.put("status", "error");
        err.put("resultKind", ToolResultEgressGateway.RESULT_KIND_ALERT_SUMMARY_MULTI);
        err.put("code", dominantCode != null ? dominantCode : "QUERY_ALERT_SUMMARY_ERROR");
        err.put("message", dominantMessage != null ? dominantMessage : "All alert summary sub-calls failed.");
        err.put("thingsRequested", thingsRequested);
        err.put("thingsSucceeded", 0);
        ArrayNode byThing = MAPPER.createArrayNode();
        for (ThingRollupPart p : parts) {
            byThing.add(p.entry);
        }
        err.set("byThing", byThing);
        if (identityErrors != null && identityErrors.size() > 0) {
            err.set("identityErrors", identityErrors);
        }
        return MAPPER.writeValueAsString(err);
    }

    static ThingRollupPart successFromInfoTable(String thingName, InfoTable inner, ObjectNode extras) throws Exception {
        List<AlertRow> rows = readAlertRows(inner);
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("thingName", thingName);
        entry.put("status", "success");
        int total = rows.size();
        entry.put("totalAlerts", total);
        entry.put("rowCount", total);
        int unacked = 0;
        Map<String, Integer> byPriority = new LinkedHashMap<>();
        byPriority.put("high", 0);
        byPriority.put("medium", 0);
        byPriority.put("low", 0);
        for (AlertRow r : rows) {
            if (!r.acknowledged) {
                unacked++;
            }
            String band = priorityBand(r.priority);
            byPriority.put(band, byPriority.getOrDefault(band, 0) + 1);
        }
        entry.put("unackedCount", unacked);
        ObjectNode bp = entry.putObject("byPriority");
        for (Map.Entry<String, Integer> e : byPriority.entrySet()) {
            bp.put(e.getKey(), e.getValue());
        }
        List<AlertRow> top = selectTopAlerts(rows);
        ArrayNode topAlerts = entry.putArray("topAlerts");
        for (AlertRow r : top) {
            ObjectNode a = topAlerts.addObject();
            if (r.alertName != null) {
                a.put("alertName", r.alertName);
            }
            if (r.sourceProperty != null) {
                a.put("sourceProperty", r.sourceProperty);
            }
            if (r.priority != null) {
                a.put("priority", r.priority);
            }
            if (r.timestamp != null) {
                a.put("timestamp", r.timestamp);
            }
        }
        if (inner != null && inner.getRowCount() > ToolResultEgressGateway.llmArraySampleLimit()) {
            String cacheId = InvokeServiceExecutor.storeInfotableInConversationCache(inner);
            if (cacheId != null && !cacheId.isBlank()) {
                entry.put("cacheId", cacheId);
            }
        }
        if (extras != null) {
            entry.set("queryExtras", extras.deepCopy());
        }
        return new ThingRollupPart(entry, true, false, false);
    }

    static ThingRollupPart serviceErrorEntry(String thingName, String code, String message) {
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("thingName", thingName);
        entry.put("status", "error");
        entry.put("code", code != null ? code : "QUERY_ALERT_SUMMARY_ERROR");
        entry.put("message", message != null ? message : "");
        return new ThingRollupPart(entry, false, false, true);
    }

    static ThingRollupPart identityErrorEntry(String thingName, String identityErrorJson) throws Exception {
        ObjectNode entry = (ObjectNode) MAPPER.readTree(identityErrorJson);
        if (!entry.has("thingName")) {
            entry.put("thingName", thingName);
        }
        entry.put("status", "error");
        return new ThingRollupPart(entry, false, true, false);
    }

    static ArrayNode identityErrorFromJson(String identityErrorJson) throws Exception {
        ArrayNode arr = MAPPER.createArrayNode();
        ObjectNode o = (ObjectNode) MAPPER.readTree(identityErrorJson);
        arr.add(o);
        return arr;
    }

    private static List<AlertRow> readAlertRows(InfoTable inner) {
        if (inner == null || inner.getRowCount() <= 0) {
            return Collections.emptyList();
        }
        List<AlertRow> rows = new ArrayList<>(inner.getRowCount());
        for (int i = 0; i < inner.getRowCount(); i++) {
            ValueCollection vc = inner.getRow(i);
            if (vc == null) {
                continue;
            }
            AlertRow r = new AlertRow();
            r.alertName = stringField(vc, "name", "alertName");
            r.sourceProperty = stringField(vc, "sourceProperty", "property");
            r.priority = intField(vc, "priority");
            r.timestamp = stringField(vc, "timestamp", "time");
            r.acknowledged = booleanField(vc, "acknowledged", "ack");
            rows.add(r);
        }
        return rows;
    }

    private static List<AlertRow> selectTopAlerts(List<AlertRow> rows) {
        List<AlertRow> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator
                .comparing((AlertRow r) -> r.priority != null ? r.priority : Integer.MIN_VALUE).reversed()
                .thenComparing((AlertRow r) -> r.timestamp != null ? r.timestamp : "", Comparator.reverseOrder())
                .thenComparing(r -> r.alertName != null ? r.alertName : ""));
        int n = Math.min(TOP_ALERTS_CAP, sorted.size());
        return sorted.subList(0, n);
    }

    private static String priorityBand(Integer priority) {
        if (priority == null) {
            return "low";
        }
        if (priority >= 900) {
            return "high";
        }
        if (priority >= 500) {
            return "medium";
        }
        return "low";
    }

    private static String stringField(ValueCollection vc, String... names) {
        for (String n : names) {
            Object v = vc.getValue(n);
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }

    private static Integer intField(ValueCollection vc, String name) {
        Object v = vc.getValue(name);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return null;
    }

    private static boolean booleanField(ValueCollection vc, String... names) {
        for (String n : names) {
            Object v = vc.getValue(n);
            if (v instanceof Boolean) {
                return (Boolean) v;
            }
        }
        return false;
    }

    static final class ThingRollupPart {
        final ObjectNode entry;
        final boolean success;
        final boolean identityFailure;
        final boolean serviceFailure;

        ThingRollupPart(ObjectNode entry, boolean success, boolean identityFailure, boolean serviceFailure) {
            this.entry = entry;
            this.success = success;
            this.identityFailure = identityFailure;
            this.serviceFailure = serviceFailure;
        }
    }

    private static final class AlertRow {
        String alertName;
        String sourceProperty;
        Integer priority;
        String timestamp;
        boolean acknowledged;
    }
}
