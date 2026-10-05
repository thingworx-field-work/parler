package com.thingworx.things.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.ISODateTimeFormat;
import org.json.JSONObject;
import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.entities.RootEntity;
import com.thingworx.logging.LogUtilities;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.Thing;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * Builds {@code ai-parler-history-v1} JSON from {@link AgentMessageStreamAppender#STREAM_THING_NAME} for a
 * conversation {@code source} (ParlerGateway / {@code conversationId}). Uses platform
 * {@code QueryStreamData} so each result row matches {@link AgentMessageStreamAppender}'s value DataShape
 * ({@code AgentMessageData}) — not {@code QueryStreamEntriesWithData} (stream-entry wrapper + nested values).
 * Uses {@code oldestFirst=false} so {@code maxItems} selects the newest rows when the stream is longer
 * than the cap; rows are then reversed to chronological order before mapping to {@code ai-parler-history-v1}.
 * <p>
 * <b>Display parity with live ParlerStream:</b> each user turn becomes one user row plus one assistant row whose
 * {@code markdown} is only the <em>final</em> assistant message in that turn (the post-loop reply;
 * intermediate tool-calling assistant rows and raw tool payloads are omitted). Chart blocks match live
 * {@code type: "chart"} frames: built via {@link ParlerChartWireSupport} from numeric history tool results and
 * persisted {@code CHART_EMITTED} tabular chart tool results ({@code chartBlock}).
 * Table blocks match live {@code type: "table"} frames: {@link ParlerTabulateEntityListTableWire},
 * {@link ParlerTaxonomyEntityListTableWire}, {@link ParlerListEntitiesEntityListTableWire},
 * {@link ParlerQueryEntitiesEntityListTableWire}, {@link ParlerInvokeServiceInfotableTableWire}
 * (including built-in **query_alert_*** tools that reuse the INFOTABLE / INFOTABLE_LARGE envelope),
 * {@link ParlerFetchCachedResultTableWire} on matching **TOOL**
 * success JSON. When the stream row embeds {@link ParlerTableExportSidecar}, export fields are merged for history parity.
 * {@code ui_feedback} rows are omitted from turn segmentation but contribute a last-wins {@code feedbackRating} on the
 * matching assistant history row (same {@code assistantMessageId}).
 * Product / mapping SoT: {@code docs/ui/table-view-solution.md} §6,
 * {@code docs/ui/AGENT_MESSAGE_STREAM_TO_AI_PARLER.md} §3.
 */
public final class AgentMessageStreamHistoryExporter {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(AgentMessageStreamHistoryExporter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Default cap when service omits {@code maxItems} (matches common platform default). */
    public static final int DEFAULT_MAX_STREAM_ROWS = 500;
    /** Hard upper bound per request (abuse guard). */
    public static final int HARD_CAP_STREAM_ROWS = 20_000;
    /** Max serialized JSON characters returned (approximate). */
    public static final int MAX_JSON_CHARS = 2_000_000;

    private AgentMessageStreamHistoryExporter() {}

    /**
     * @param conversationSource {@code AddStreamEntry.source} / thread key (Gateway name)
     * @param maxItems optional row cap ({@code null} / NaN / non-positive → {@link #DEFAULT_MAX_STREAM_ROWS}); clamped to
     *            {@link #HARD_CAP_STREAM_ROWS}
     * @return JSON string with {@code format} + {@code rows} for the parler-ui widget {@code hydrateHistoryFromJsonString}
     */
    public static String exportHistoryJsonString(String conversationSource, Double maxItems) throws Exception {
        return exportHistoryJsonString(conversationSource, maxItems, null);
    }

    /**
     * @param historyClearedAtOrNull when non-null, rows at or before this instant are excluded via
     *            {@link StreamHistoryBounds#queryStartAfterClear(DateTime)} on {@code QueryStreamData.startDate}
     */
    public static String exportHistoryJsonString(String conversationSource, Double maxItems,
            DateTime historyClearedAtOrNull) throws Exception {
        Thing stream = resolveStreamThing();
        if (stream == null) {
            LOG.warn("AgentMessageStreamHistoryExporter: stream Thing [{}] not found; returning empty history",
                    AgentMessageStreamAppender.STREAM_THING_NAME);
            return emptyDocumentJson();
        }
        int cap = effectiveMaxItems(maxItems);
        String src = conversationSource != null ? conversationSource : "";
        DateTime startExclusive = StreamHistoryBounds.queryStartAfterClear(historyClearedAtOrNull);
        ValueCollection params = new ValueCollection();
        params.put("maxItems", new NumberPrimitive((double) cap));
        params.put("source", new StringPrimitive(src));
        params.put("tags", null);
        params.put("sourceTags", null);
        params.put("startDate", startExclusive != null ? new DatetimePrimitive(startExclusive) : null);
        params.put("endDate", null);
        params.put("oldestFirst", new BooleanPrimitive(false));
        params.put("query", null);

        InfoTable streamRows = PlatformAccess.invokeProgrammatic(stream, "QueryStreamData", params);
        List<ValueCollection> dataRows = new ArrayList<>(streamRows.getRowCount());
        for (int i = 0; i < streamRows.getRowCount(); i++) {
            dataRows.add(streamRows.getRow(i));
        }
        Collections.reverse(dataRows);

        return buildHistoryJson(dataRows);
    }

    /**
     * The wire shape, from chronological Stream rows onward.
     *
     * <p>Package-private so segmentation and role gating are testable without a live Stream Thing;
     * {@code exportHistoryJsonString} is this plus the query.
     */
    static String buildHistoryJson(List<ValueCollection> dataRows) throws Exception {
        Map<String, String> feedbackByAssistant = lastWinFeedbackRatingsByAssistantId(dataRows);
        List<TurnSegment> segments = splitIntoTurnSegments(dataRows);
        ArrayNode rows = JSON.createArrayNode();
        int assistantSeq = 0;
        for (TurnSegment seg : segments) {
            assistantSeq = appendSegmentToHistoryRows(rows, seg, assistantSeq, feedbackByAssistant);
        }

        ObjectNode root = JSON.createObjectNode();
        root.put("format", "ai-parler-history-v1");
        root.set("rows", rows);
        String out = JSON.writeValueAsString(root);
        if (out.length() > MAX_JSON_CHARS) {
            throw new Exception("GetConversationHistoryJson: result exceeds max JSON size (" + MAX_JSON_CHARS + " chars)");
        }
        return out;
    }

    /** One user message plus following stream rows until the next user (or leading tail with no user when window starts mid-turn). */
    private static final class TurnSegment {
        /** Null when {@code maxItems} window begins after the user line of that turn. */
        private final ValueCollection userRow;
        /** Assistant and tool rows; excludes {@link #userRow}. */
        private final List<ValueCollection> tail;

        private TurnSegment(ValueCollection userRow, List<ValueCollection> tail) {
            this.userRow = userRow;
            this.tail = tail;
        }
    }

    private static List<TurnSegment> splitIntoTurnSegments(List<ValueCollection> chronologicalRows) {
        List<TurnSegment> segments = new ArrayList<>();
        ValueCollection pendingUser = null;
        List<ValueCollection> pendingTail = new ArrayList<>();

        for (ValueCollection dataRow : chronologicalRows) {
            String role = stringField(dataRow, "role").trim().toLowerCase();
            // §9.1: internal rows are skipped explicitly before segmentation. A context_checkpoint row is neither
            // system/ui_feedback nor user, so unknown-role fallthrough would put it into the pending tail — and a
            // window whose oldest rows are a checkpoint with no preceding user would emit a userless segment.
            // The UI wire shape does not change because the row never reaches it.
            if ("system".equals(role) || "ui_feedback".equals(role)
                    || AgentMessageStreamAppender.ROLE_CONTEXT_CHECKPOINT.equals(role)) {
                continue;
            }
            if ("user".equals(role)) {
                if (pendingUser != null || !pendingTail.isEmpty()) {
                    segments.add(new TurnSegment(pendingUser, pendingTail));
                }
                pendingUser = dataRow;
                pendingTail = new ArrayList<>();
            } else {
                pendingTail.add(dataRow);
            }
        }
        if (pendingUser != null || !pendingTail.isEmpty()) {
            segments.add(new TurnSegment(pendingUser, pendingTail));
        }
        return segments;
    }

    /**
     * Last-wins thumbs state from chronological {@code ui_feedback} rows (JSON {@code type=assistant_feedback});
     * excluded from LLM replay via turn segmentation.
     */
    private static Map<String, String> lastWinFeedbackRatingsByAssistantId(List<ValueCollection> chronologicalRows) {
        List<AssistantFeedbackLastWin.RoleContentRow> rows = new ArrayList<>(chronologicalRows.size());
        for (ValueCollection dataRow : chronologicalRows) {
            rows.add(new AssistantFeedbackLastWin.RoleContentRow(stringField(dataRow, "role"), stringField(dataRow, "content")));
        }
        return AssistantFeedbackLastWin.lastWinRatingsChronological(rows);
    }

    /**
     * @return next assistant sequence index
     */
    private static int appendSegmentToHistoryRows(ArrayNode rows, TurnSegment seg, int assistantSeq,
            Map<String, String> feedbackByAssistant) throws Exception {
        if (seg.userRow != null) {
            String text = stringField(seg.userRow, "content").trim();
            if (!text.isEmpty()) {
                ObjectNode u = JSON.createObjectNode();
                u.put("kind", "user");
                u.put("text", text);
                String snap = stringField(seg.userRow,
                        AgentMessageStreamAppender.FIELD_HOST_CONTEXT_SNAPSHOT_JSON).trim();
                if (!snap.isEmpty()) {
                    try {
                        u.set("hostContext", JSON.readTree(snap));
                    } catch (Exception ignored) {
                        // omit malformed snapshot from wire
                    }
                }
                rows.add(u);
            }
        }

        if (seg.tail.isEmpty()) {
            return assistantSeq;
        }

        String markdown = finalAssistantMarkdown(seg.tail);
        ArrayNode charts = chartsFromToolRows(seg.tail);
        ArrayNode tables = tablesFromToolRows(seg.tail);

        ObjectNode a = JSON.createObjectNode();
        a.put("kind", "assistant");
        String amid = finalAssistantMessageId(seg.tail);
        if (!amid.isEmpty()) {
            a.put("requestId", amid);
            a.put("assistantMessageId", amid);
            String fr = feedbackByAssistant.get(amid);
            if (fr != null && ("up".equals(fr) || "down".equals(fr))) {
                a.put("feedbackRating", fr);
            }
        } else {
            a.put("requestId", "hist-" + assistantSeq++);
        }
        String completedIso = finalAssistantStreamTimestampIso(seg.tail);
        if (!completedIso.isEmpty()) {
            a.put("completedAt", completedIso);
        }
        a.put("markdown", markdown != null ? markdown : "");
        a.set("charts", charts);
        a.set("tables", tables);
        ArrayNode groups = groupsFromTail(seg.tail);
        if (groups.size() > 0) {
            a.set("groups", groups);
        }
        a.putNull("activity");
        String usageJson = finalAssistantLlmUsageJson(seg.tail);
        JSONObject sanitized = ParlerLlmUsageWireSanitizer.sanitizeLlmUsageJson(usageJson);
        if (sanitized != null && sanitized.length() > 0) {
            a.set("llmUsage", JSON.readTree(sanitized.toString()));
        }
        rows.add(a);
        return assistantSeq;
    }

    /** Last assistant row's {@code llmUsageJson} Stream field (trimmed), or empty. */
    private static String finalAssistantLlmUsageJson(List<ValueCollection> tail) {
        for (int i = tail.size() - 1; i >= 0; i--) {
            ValueCollection dataRow = tail.get(i);
            if ("assistant".equals(stringField(dataRow, "role").trim().toLowerCase())) {
                return stringField(dataRow, "llmUsageJson").trim();
            }
        }
        return "";
    }

    /**
     * Last {@code assistant} row in the turn supplies the visible reply (matches {@link AgentLoop} appending
     * {@code ChatMessage.assistant(result.getContent())} after tool rounds).
     */
    private static String finalAssistantMarkdown(List<ValueCollection> tail) {
        for (int i = tail.size() - 1; i >= 0; i--) {
            ValueCollection dataRow = tail.get(i);
            String role = stringField(dataRow, "role").trim().toLowerCase();
            if ("assistant".equals(role)) {
                String content = stringField(dataRow, "content");
                return content != null ? content : "";
            }
        }
        return "";
    }

    /** Stable id persisted on the final assistant Stream row; empty when older rows predate the field. */
    private static String finalAssistantMessageId(List<ValueCollection> tail) {
        for (int i = tail.size() - 1; i >= 0; i--) {
            ValueCollection dataRow = tail.get(i);
            String role = stringField(dataRow, "role").trim().toLowerCase();
            if ("assistant".equals(role)) {
                return stringField(dataRow, "assistantMessageId").trim();
            }
        }
        return "";
    }

    /** Stream {@code QueryStreamData} row timestamp on the final assistant line, ISO-8601 UTC, or empty. */
    private static String finalAssistantStreamTimestampIso(List<ValueCollection> tail) {
        for (int i = tail.size() - 1; i >= 0; i--) {
            ValueCollection dataRow = tail.get(i);
            if ("assistant".equals(stringField(dataRow, "role").trim().toLowerCase())) {
                return streamRowTimestampIsoUtc(dataRow);
            }
        }
        return "";
    }

    private static String streamRowTimestampIsoUtc(ValueCollection dataRow) {
        if (dataRow == null) {
            return "";
        }
        try {
            Object ts = dataRow.getValue("timestamp");
            if (ts instanceof DatetimePrimitive) {
                DateTime dt = ((DatetimePrimitive) ts).getValue();
                if (dt != null) {
                    return ISODateTimeFormat.dateTime().withZone(DateTimeZone.UTC).print(dt.withZone(DateTimeZone.UTC));
                }
            }
            if (ts instanceof DateTime) {
                return ISODateTimeFormat.dateTime().withZone(DateTimeZone.UTC)
                        .print(((DateTime) ts).withZone(DateTimeZone.UTC));
            }
        } catch (Exception e) {
            return "";
        }
        return "";
    }

    /**
     * C3b-1 (design §8.5): {@code groups[]} for the assistant row — the final manifests persisted in the final
     * assistant row's {@code chartGroupsJson}; when that field is absent (the turn never terminated normally), the
     * group is rebuilt from the {@code CHART_GROUP_DECLARED} tool row with every member converged to
     * {@code error} / {@code TURN_INCOMPLETE}; with neither, no {@code groups[]}.
     */
    static ArrayNode groupsFromTail(List<ValueCollection> tail) throws Exception {
        ArrayNode out = JSON.createArrayNode();
        for (int i = tail.size() - 1; i >= 0; i--) {
            ValueCollection dataRow = tail.get(i);
            if (!"assistant".equals(stringField(dataRow, "role").trim().toLowerCase())) {
                continue;
            }
            String json = stringField(dataRow, AgentMessageStreamAppender.FIELD_CHART_GROUPS_JSON).trim();
            if (!json.isEmpty()) {
                JsonNode parsed = JSON.readTree(json);
                if (parsed.isArray()) {
                    for (JsonNode g : parsed) {
                        if (g.isObject()) {
                            out.add(g);
                        }
                    }
                }
                return out;
            }
            break;
        }
        for (ValueCollection dataRow : tail) {
            if (!"tool".equals(stringField(dataRow, "role").trim().toLowerCase())) {
                continue;
            }
            String body = ParlerPlaybookArtifactWireConstants.stripInternalWireMetadataForArtifactHydration(
                    stringField(dataRow, "content"));
            JSONObject declared;
            try {
                declared = new JSONObject(body);
            } catch (RuntimeException e) {
                continue;
            }
            if (!"CHART_GROUP_DECLARED".equals(declared.optString("code")) || !declared.has("members")) {
                continue;
            }
            java.util.List<String[]> keysAndNames = new java.util.ArrayList<>();
            org.json.JSONArray members = declared.getJSONArray("members");
            for (int m = 0; m < members.length(); m++) {
                JSONObject mm = members.getJSONObject(m);
                keysAndNames.add(new String[]{mm.optString("key", ""), mm.optString("name", "")});
            }
            com.thingworx.things.agent.tools.ChartGroupState state = new com.thingworx.things.agent.tools.ChartGroupState(
                    declared.optString("groupId", "g1"), declared.optString("title", ""), declared.optString("layout", "auto"),
                    keysAndNames);
            state.convergeIncomplete();
            out.add(JSON.readTree(state.nextManifest(true).toString()));
            break;
        }
        return out;
    }

    static ArrayNode chartsFromToolRows(List<ValueCollection> tail) throws Exception {
        ArrayNode out = JSON.createArrayNode();
        for (ValueCollection dataRow : tail) {
            String role = stringField(dataRow, "role").trim().toLowerCase();
            if (!"tool".equals(role)) {
                continue;
            }
            String body = ParlerPlaybookArtifactWireConstants.stripInternalWireMetadataForArtifactHydration(
                    stringField(dataRow, "content"));
            Optional<JSONObject> chartOpt =
                    ParlerChartWireSupport.chartBlockFromChartEmittedToolResult(body, false);
            if (chartOpt.isEmpty()) {
                chartOpt = ParlerChartWireSupport.chartBlockFromNumericCompactToolResult(body, false);
            }
            if (chartOpt.isEmpty()) {
                chartOpt = ParlerChartWireSupport.chartBlockFromNumericHistoryToolResult(body, false);
            }
            if (chartOpt.isEmpty()) {
                continue;
            }
            JsonNode node = JSON.readTree(chartOpt.get().toString());
            out.add(node);
        }
        return out;
    }

    /** Reconstructs {@code tables[]} for {@code ai-parler-history-v1} assistant rows (parity with live {@code wireTable}). */
    static ArrayNode tablesFromToolRows(List<ValueCollection> tail) throws Exception {
        ArrayNode out = JSON.createArrayNode();
        for (ValueCollection dataRow : tail) {
            String role = stringField(dataRow, "role").trim().toLowerCase();
            if (!"tool".equals(role)) {
                continue;
            }
            String body = ParlerPlaybookArtifactWireConstants.stripInternalWireMetadataForArtifactHydration(
                    stringField(dataRow, "content"));
            String etn = stringField(dataRow, AgentMessageStreamAppender.FIELD_EXECUTED_TOOL_NAME).trim();
            JSONObject table = etn.isEmpty()
                    ? ParlerToolTableWireUtil.tableBlockFromListClassToolJson(body)
                    : ParlerToolTableWireUtil.tableBlockFromListClassToolJson(body, etn);
            if (table == null) {
                continue;
            }
            ParlerTableExportSidecar.mergeToolRootSidecarIntoTable(body, table);
            out.add(JSON.readTree(table.toString()));
        }
        return out;
    }

    static int effectiveMaxItems(Double maxItems) {
        if (maxItems == null || maxItems.isNaN() || maxItems.isInfinite()) {
            return DEFAULT_MAX_STREAM_ROWS;
        }
        int n = maxItems.intValue();
        if (n < 1) {
            return DEFAULT_MAX_STREAM_ROWS;
        }
        return Math.min(n, HARD_CAP_STREAM_ROWS);
    }

    private static String emptyDocumentJson() throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("format", "ai-parler-history-v1");
        root.set("rows", JSON.createArrayNode());
        return JSON.writeValueAsString(root);
    }

    private static Thing resolveStreamThing() {
        try {
            RootEntity e = PlatformAccess.findProgrammatic(AgentMessageStreamAppender.STREAM_THING_NAME,
                    RelationshipTypes.ThingworxRelationshipTypes.Thing);
            return e instanceof Thing ? (Thing) e : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String stringField(ValueCollection row, String name) {
        if (row == null) {
            return "";
        }
        try {
            Object v = row.getValue(name);
            return v == null ? "" : String.valueOf(v);
        } catch (Exception e) {
            return "";
        }
    }

}
