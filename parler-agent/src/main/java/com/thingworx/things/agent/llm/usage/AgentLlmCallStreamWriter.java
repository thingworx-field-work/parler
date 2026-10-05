package com.thingworx.things.agent.llm.usage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.joda.time.DateTime;
import org.slf4j.Logger;

import com.thingworx.datashape.DataShape;
import com.thingworx.entities.RootEntity;
import com.thingworx.logging.LogUtilities;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.system.managers.DataShapeManager;
import com.thingworx.things.Thing;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.IntegerPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * Persists {@link LlmCallEvent} rows via platform {@code AddStreamEntry} (CC-7.4).
 */
public final class AgentLlmCallStreamWriter {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(AgentLlmCallStreamWriter.class);

    public static final String STREAM_THING_NAME = "AgentLlmCallStream";
    public static final String STREAM_SOURCETYPE_THING = "Thing";
    private static final String DATA_SHAPE_NAME = "AgentLlmCallData";

    private static volatile DataShapeDefinition cachedShape;
    private static volatile Consumer<LlmCallEvent> testSink;
    private static volatile WriteResult testForcedResult;
    private static volatile Predicate<LlmCallEvent> testFailPredicate;
    private static final CopyOnWriteArrayList<LlmCallEvent> testCapture = new CopyOnWriteArrayList<>();

    private AgentLlmCallStreamWriter() {}

    public enum WriteResult {
        WRITTEN,
        SKIPPED,
        FAILED
    }

    public static void setTestSink(Consumer<LlmCallEvent> sink) {
        testSink = sink;
    }

    public static void clearTestHooks() {
        testSink = null;
        testForcedResult = null;
        testFailPredicate = null;
        testCapture.clear();
    }

    public static void setTestForcedResult(WriteResult result) {
        testForcedResult = result;
    }

    public static void setTestFailPredicate(Predicate<LlmCallEvent> predicate) {
        testFailPredicate = predicate;
    }

    public static List<LlmCallEvent> testCapture() {
        return Collections.unmodifiableList(new ArrayList<>(testCapture));
    }

    public static WriteResult append(LlmCallEvent event) {
        if (event == null) {
            return WriteResult.SKIPPED;
        }
        if (testFailPredicate != null && testFailPredicate.test(event)) {
            return WriteResult.FAILED;
        }
        if (testForcedResult != null) {
            if (testForcedResult == WriteResult.WRITTEN) {
                testCapture.add(event);
            }
            return testForcedResult;
        }
        Consumer<LlmCallEvent> sink = testSink;
        if (sink != null) {
            sink.accept(event);
            testCapture.add(event);
            return WriteResult.WRITTEN;
        }
        try {
            Thing stream = resolveStreamThing();
            if (stream == null) {
                LOG.warn("AgentLlmCallStream: Thing [{}] not found; skip append eventType={}",
                        STREAM_THING_NAME, event.getEventType());
                return WriteResult.FAILED;
            }
            DataShapeDefinition shape = loadDataShape(stream);
            if (shape == null) {
                LOG.warn("AgentLlmCallStream: could not resolve row DataShape; skip append");
                return WriteResult.FAILED;
            }
            InfoTable values = new InfoTable(shape);
            values.addRow(toValueRow(event, shape));

            ValueCollection params = new ValueCollection();
            params.put("timestamp", new DatetimePrimitive(new DateTime(event.getCallStartedAt().toEpochMilli())));
            params.put("location", null);
            params.put("source", new StringPrimitive(event.getEventId()));
            params.put("sourceType", new StringPrimitive(STREAM_SOURCETYPE_THING));
            params.put("tags", null);
            params.SetInfoTableValue("values", values);

            stream.processServiceRequest("AddStreamEntry", params);
            return WriteResult.WRITTEN;
        } catch (Exception e) {
            LOG.warn("AgentLlmCallStream append failed eventType={}: {}",
                    event.getEventType(), e.getMessage());
            return WriteResult.FAILED;
        }
    }

    private static ValueCollection toValueRow(LlmCallEvent event, DataShapeDefinition shape) {
        ValueCollection row = new ValueCollection();
        putString(row, shape, "eventId", event.getEventId());
        putString(row, shape, "callId", event.getCallId());
        putString(row, shape, "logicalCallId", event.getLogicalCallId());
        putString(row, shape, "turnRequestId", event.getTurnRequestId());
        putString(row, shape, "conversationId", event.getConversationId());
        putString(row, shape, "agentThing", event.getAgentThing());
        putString(row, shape, "providerThingName", event.getProviderThingName());
        putString(row, shape, "providerFamily", event.getProviderFamily());
        putString(row, shape, "apiShapeId", event.getApiShapeId());
        putString(row, shape, "requestedModel", event.getRequestedModel());
        putString(row, shape, "callKind", event.getCallKind() != null ? event.getCallKind().wireValue() : "");
        putString(row, shape, "eventType", event.getEventType().wireValue());
        putInteger(row, shape, "sequence", event.getSequence());
        putDatetime(row, shape, "callStartedAt", event.getCallStartedAt());
        putDatetime(row, shape, "occurredAt", event.getOccurredAt());
        putInteger(row, shape, "schemaVersion", event.getSchemaVersion());
        putString(row, shape, "eventJson", event.getEventJson());
        return row;
    }

    private static void putString(ValueCollection row, DataShapeDefinition shape, String field, String value) {
        if (!shapeHasField(shape, field)) {
            return;
        }
        row.put(field, new StringPrimitive(value != null ? value : ""));
    }

    private static void putInteger(ValueCollection row, DataShapeDefinition shape, String field, int value) {
        if (!shapeHasField(shape, field)) {
            return;
        }
        row.put(field, new IntegerPrimitive(value));
    }

    private static void putDatetime(ValueCollection row, DataShapeDefinition shape, String field, Instant instant) {
        if (!shapeHasField(shape, field)) {
            return;
        }
        row.put(field, new DatetimePrimitive(new DateTime(instant.toEpochMilli())));
    }

    private static boolean shapeHasField(DataShapeDefinition shape, String fieldName) {
        if (shape == null || fieldName == null) {
            return false;
        }
        for (FieldDefinition fd : shape.getFields().values()) {
            if (fieldName.equals(fd.getName())) {
                return true;
            }
        }
        return false;
    }

    private static Thing resolveStreamThing() {
        try {
            RootEntity entity = PlatformAccess.findProgrammatic(STREAM_THING_NAME,
                    RelationshipTypes.ThingworxRelationshipTypes.Thing);
            return entity instanceof Thing ? (Thing) entity : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static DataShapeDefinition loadDataShape(Thing streamThing) {
        if (cachedShape != null) {
            return cachedShape;
        }
        synchronized (AgentLlmCallStreamWriter.class) {
            if (cachedShape != null) {
                return cachedShape;
            }
            DataShapeDefinition fromManager = tryDataShapeFromManager();
            if (fromManager != null) {
                cachedShape = fromManager;
                return cachedShape;
            }
            try {
                RootEntity ent = PlatformAccess.findProgrammatic(DATA_SHAPE_NAME,
                        RelationshipTypes.ThingworxRelationshipTypes.DataShape);
                if (ent instanceof DataShape) {
                    Object def = tryInvokeDataShapeDef((DataShape) ent);
                    if (def instanceof DataShapeDefinition) {
                        cachedShape = (DataShapeDefinition) def;
                        return cachedShape;
                    }
                }
            } catch (Exception ignored) {
                // fall through
            }
            cachedShape = inlineShape();
            return cachedShape;
        }
    }

    private static Object tryInvokeDataShapeDef(DataShape ds) {
        for (String method : new String[] {"getDataShapeDefinition", "GetDataShapeDefinition", "getInstanceDataShape"}) {
            try {
                return ds.getClass().getMethod(method).invoke(ds);
            } catch (NoSuchMethodException ignored) {
                // next
            } catch (Exception ignored) {
                // next
            }
        }
        return null;
    }

    private static DataShapeDefinition tryDataShapeFromManager() {
        try {
            DataShape ds = DataShapeManager.getInstance().getEntity(DATA_SHAPE_NAME);
            if (ds != null) {
                Object def = tryInvokeDataShapeDef(ds);
                return def instanceof DataShapeDefinition ? (DataShapeDefinition) def : null;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private static DataShapeDefinition inlineShape() {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(field("eventId", BaseTypes.STRING, 0));
        shape.addFieldDefinition(field("callId", BaseTypes.STRING, 1));
        shape.addFieldDefinition(field("logicalCallId", BaseTypes.STRING, 2));
        shape.addFieldDefinition(field("turnRequestId", BaseTypes.STRING, 3));
        shape.addFieldDefinition(field("conversationId", BaseTypes.STRING, 4));
        shape.addFieldDefinition(field("agentThing", BaseTypes.STRING, 5));
        shape.addFieldDefinition(field("providerThingName", BaseTypes.STRING, 6));
        shape.addFieldDefinition(field("providerFamily", BaseTypes.STRING, 7));
        shape.addFieldDefinition(field("apiShapeId", BaseTypes.STRING, 8));
        shape.addFieldDefinition(field("requestedModel", BaseTypes.STRING, 9));
        shape.addFieldDefinition(field("callKind", BaseTypes.STRING, 10));
        shape.addFieldDefinition(field("eventType", BaseTypes.STRING, 11));
        shape.addFieldDefinition(field("sequence", BaseTypes.INTEGER, 12));
        shape.addFieldDefinition(field("callStartedAt", BaseTypes.DATETIME, 13));
        shape.addFieldDefinition(field("occurredAt", BaseTypes.DATETIME, 14));
        shape.addFieldDefinition(field("schemaVersion", BaseTypes.INTEGER, 15));
        shape.addFieldDefinition(field("eventJson", BaseTypes.TEXT, 16));
        return shape;
    }

    private static FieldDefinition field(String name, BaseTypes bt, int ordinal) {
        FieldDefinition f = new FieldDefinition();
        f.setName(name);
        f.setBaseType(bt);
        f.setOrdinal(ordinal);
        return f;
    }
}
