package com.thingworx.things.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.joda.time.DateTime;
import org.slf4j.Logger;

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
 * Reads {@link AgentMessageStreamAppender#STREAM_THING_NAME} rows via {@code QueryStreamData} for LLM rehydration.
 */
public final class AgentMessageStreamReader {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(AgentMessageStreamReader.class);

    private AgentMessageStreamReader() {}

    /**
     * Newest-first query, reversed to chronological order (same shape as {@link AgentMessageStreamHistoryExporter}).
     */
    public static List<ValueCollection> queryChronologicalRows(String conversationSource, int maxItems,
            DateTime startDateExclusiveOrNull) throws Exception {
        String src = conversationSource != null ? conversationSource : "";
        Thing stream = resolveStreamThing();
        if (stream == null) {
            LOG.warn("AgentMessageStreamReader: Stream Thing [{}] missing or lookup failed; conversationSource={} — "
                    + "LLM rehydrate treats history as empty (contrast: UI exporter logs the same condition)",
                    AgentMessageStreamAppender.STREAM_THING_NAME, src);
            return Collections.emptyList();
        }
        int cap = Math.max(1, Math.min(maxItems, AgentMessageStreamHistoryExporter.HARD_CAP_STREAM_ROWS));
        ValueCollection params = new ValueCollection();
        params.put("maxItems", new NumberPrimitive((double) cap));
        params.put("source", new StringPrimitive(src));
        params.put("tags", null);
        params.put("sourceTags", null);
        params.put("startDate", startDateExclusiveOrNull != null ? new DatetimePrimitive(startDateExclusiveOrNull) : null);
        params.put("endDate", null);
        params.put("oldestFirst", new BooleanPrimitive(false));
        params.put("query", null);

        InfoTable streamRows = PlatformAccess.invokeProgrammatic(stream, "QueryStreamData", params);
        List<ValueCollection> dataRows = new ArrayList<>(streamRows.getRowCount());
        for (int i = 0; i < streamRows.getRowCount(); i++) {
            dataRows.add(streamRows.getRow(i));
        }
        Collections.reverse(dataRows);
        return dataRows;
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

    public static String stringField(ValueCollection row, String name) {
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
