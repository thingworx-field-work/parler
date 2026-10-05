package com.thingworx.things.agent;

import java.util.List;

import org.joda.time.DateTime;
import org.slf4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.datashape.DataShape;
import com.thingworx.entities.RootEntity;
import com.thingworx.logging.LogUtilities;
import com.thingworx.system.managers.DataShapeManager;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.Thing;
import com.thingworx.types.BaseTypes;
import com.thingworx.things.agent.cache.LargeJsonCaps;
import com.thingworx.things.agent.compaction.ConversationCheckpointCodec;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.ProtectedValuePolicy;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.IntegerPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * Appends {@link ChatMessage} rows to Thing {@value #STREAM_THING_NAME} via {@code AddStreamEntry}.
 * Stream metadata {@code source} is the conversation identity (AlwaysOn ParlerGateway / conversationId, DataTable thread id, or
 * {@code adhoc-*}), not the agent Thing. {@code sourceType} is {@value #STREAM_SOURCETYPE_THING} (ThingWorx stream convention: source names a Thing).
 * Row field {@code agentThing} names the {@link AgentThing}.
 * Failures are logged and swallowed so chat is not blocked if Stream is missing or misconfigured.
 */
public final class AgentMessageStreamAppender {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(AgentMessageStreamAppender.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String STREAM_THING_NAME = "AgentMessageStream";
    /** {@code AddStreamEntry.sourceType} — Stream metadata (ThingWorx convention); history export uses {@code QueryStreamData} + {@code source} only. */
    static final String STREAM_SOURCETYPE_THING = "Thing";
    private static final String DATA_SHAPE_NAME = "AgentMessageData";
    /** Stream row column for {@link com.thingworx.things.agent.llm.ChatMessage#getExecutedToolName()} (table title). */
    public static final String FIELD_EXECUTED_TOOL_NAME = "executedToolName";
    /** Persisted JSON snapshot for user-turn host context (host-context-turn-state). */
    public static final String FIELD_HOST_CONTEXT_SNAPSHOT_JSON = "hostContextSnapshotJson";
    /** C3b-1: final chart-group manifests (JSON array) on the final assistant row; empty otherwise. */
    public static final String FIELD_CHART_GROUPS_JSON = "chartGroupsJson";
    /** Prefix for single-turn chats with no {@code conversationId}. */
    static final String ADHOC_PREFIX = "adhoc-";
    /**
     * §9.1 internal Stream role for a persisted conversation checkpoint.
     *
     * <p>A literal, deliberately <b>not</b> a {@link ChatMessage.Role} constant: adding a fifth role would leak an
     * unroutable value into every provider adapter, planner branch, and tool-pairing check, and
     * {@code toValueRow} derives its {@code role} cell from {@code msg.getRole()} — so a checkpoint row can only be
     * written through a dedicated appender.
     */
    public static final String ROLE_CONTEXT_CHECKPOINT = "context_checkpoint";
    /** Avoid oversized Stream TEXT / provider limits (U2 M2 SoT: {@link LargeJsonCaps}). */
    private static final int MAX_CONTENT_CHARS = LargeJsonCaps.STREAM_PERSISTENCE_CHAR_CAP;

    private static volatile DataShapeDefinition cachedShape;

    private AgentMessageStreamAppender() {}

    /**
     * Appends a {@code ChatMessage} row. For final assistant rows pass a non-empty {@code assistantMessageId} so the UI
     * and {@code RecordAssistantFeedback} can anchor feedback; use {@code null} or blank for other roles.
     *
     * @param streamThreadKey logical thread key (same as {@code streamEntrySource} for all current callers)
     * @param streamEntrySource {@code AddStreamEntry.source} — conversation Thing name, thread id, or adhoc id
     * @param agentThingName {@link AgentThing} name that produced the row (stored in {@code agentThing} field)
     * @param usage per-row token totals and optional {@code llmUsageJson} for assistant rows; {@link StreamTokenUsage#ZERO} for user/tool
     */
    public static void append(String streamThreadKey, ChatMessage msg, String streamEntrySource,
            String agentThingName, StreamTokenUsage usage) {
        append(streamThreadKey, msg, streamEntrySource, agentThingName, usage, null);
    }

    /**
     * @param assistantMessageIdOrNull server-generated id for final {@code assistant} rows without tool calls; otherwise null
     */
    public static void append(String streamThreadKey, ChatMessage msg, String streamEntrySource,
            String agentThingName, StreamTokenUsage usage, String assistantMessageIdOrNull) {
        append(streamThreadKey, msg, streamEntrySource, agentThingName, usage, assistantMessageIdOrNull, null);
    }

    /**
     * @param hostContextSnapshotJsonOrNull persisted on {@code role=user} rows only
     */
    public static void append(String streamThreadKey, ChatMessage msg, String streamEntrySource,
            String agentThingName, StreamTokenUsage usage, String assistantMessageIdOrNull,
            String hostContextSnapshotJsonOrNull) {
        if (streamThreadKey == null || streamThreadKey.isEmpty() || msg == null) {
            return;
        }
        if (msg.getRole() == ChatMessage.Role.SYSTEM) {
            return;
        }
        if (usage == null) {
            usage = StreamTokenUsage.ZERO;
        }
        try {
            Thing stream = resolveStreamThing();
            if (stream == null) {
                LOG.warn("AgentMessageStream: Thing [{}] not found; skip append", STREAM_THING_NAME);
                return;
            }
            DataShapeDefinition shape = loadMessageDataShape(stream);
            if (shape == null) {
                LOG.warn("AgentMessageStream: could not resolve row DataShape; skip append");
                return;
            }
            InfoTable values = new InfoTable(shape);
            values.addRow(toValueRow(msg, agentThingName, usage.getPromptTokens(), usage.getCompletionTokens(),
                    usage.getLlmUsageJson(), assistantMessageIdOrNull, shape, hostContextSnapshotJsonOrNull));

            String src = streamEntrySource != null && !streamEntrySource.isEmpty() ? streamEntrySource : streamThreadKey;
            ValueCollection params = new ValueCollection();
            params.put("timestamp", new DatetimePrimitive(DateTime.now()));
            params.put("location", null);
            params.put("source", new StringPrimitive(src));
            params.put("sourceType", new StringPrimitive(STREAM_SOURCETYPE_THING));
            params.put("tags", null);
            params.SetInfoTableValue("values", values);

            stream.processServiceRequest("AddStreamEntry", params);
            LOG.info("AgentMessageStream append: thread={} role={}", streamThreadKey, msg.getRole());
        } catch (Exception e) {
            LOG.warn("AgentMessageStream append failed (thread={} role={}): {}",
                    streamThreadKey, msg.getRole(), e.getMessage());
        }
    }

    private static Thing resolveStreamThing() {
        try {
            RootEntity e = PlatformAccess.findProgrammatic(STREAM_THING_NAME,
                    RelationshipTypes.ThingworxRelationshipTypes.Thing);
            return e instanceof Thing ? (Thing) e : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolves row shape for {@code AddStreamEntry} values.
     * <ol>
     *   <li>{@link DataShapeManager#getEntity(String)} — recommended for named DataShapes on platform</li>
     *   <li>Stream Thing’s configured shape (reflection)</li>
     *   <li>{@link PlatformAccess#findProgrammatic}</li>
     *   <li>Inline shape matching {@code AgentMessageData.xml}</li>
     * </ol>
     */
    private static DataShapeDefinition loadMessageDataShape(Thing streamThing) {
        if (cachedShape != null) {
            return cachedShape;
        }
        synchronized (AgentMessageStreamAppender.class) {
            if (cachedShape != null) {
                return cachedShape;
            }
            DataShapeDefinition fromManager = tryDataShapeFromManager();
            if (fromManager != null && !isShapeEmpty(fromManager)) {
                cachedShape = fromManager;
                LOG.info("AgentMessageStream: using DataShapeManager.getEntity({})", DATA_SHAPE_NAME);
                return cachedShape;
            }
            DataShapeDefinition fromStream = tryDataShapeFromStreamThing(streamThing);
            if (fromStream != null && !isShapeEmpty(fromStream)) {
                cachedShape = fromStream;
                LOG.info("AgentMessageStream: using DataShape from Stream Thing [{}]", STREAM_THING_NAME);
                return cachedShape;
            }
            try {
                RootEntity ent = PlatformAccess.findProgrammatic(DATA_SHAPE_NAME,
                        RelationshipTypes.ThingworxRelationshipTypes.DataShape);
                if (ent != null) {
                    for (String method : new String[] {"getDataShapeDefinition", "GetDataShapeDefinition"}) {
                        try {
                            Object def = ent.getClass().getMethod(method).invoke(ent);
                            if (def instanceof DataShapeDefinition && !isShapeEmpty((DataShapeDefinition) def)) {
                                cachedShape = (DataShapeDefinition) def;
                                LOG.info("AgentMessageStream: using DataShape entity [{}]", DATA_SHAPE_NAME);
                                return cachedShape;
                            }
                        } catch (NoSuchMethodException ignored) {
                            // next
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("AgentMessageStream: entity lookup for {}: {}", DATA_SHAPE_NAME, e.getMessage());
            }
            for (RelationshipTypes.ThingworxRelationshipTypes rel
                    : RelationshipTypes.ThingworxRelationshipTypes.values()) {
                try {
                    RootEntity ent = PlatformAccess.findProgrammatic(DATA_SHAPE_NAME, rel);
                    if (ent == null) {
                        continue;
                    }
                    Object def = tryInvokeDataShapeDef(ent);
                    if (def instanceof DataShapeDefinition && !isShapeEmpty((DataShapeDefinition) def)) {
                        cachedShape = (DataShapeDefinition) def;
                        LOG.info("AgentMessageStream: using DataShape [{}] via relationship {}", DATA_SHAPE_NAME, rel.name());
                        return cachedShape;
                    }
                } catch (Exception ignored) {
                    // next relationship
                }
            }
            cachedShape = buildInlineAgentMessageDataShape();
            LOG.info("AgentMessageStream: using inline DataShapeDefinition (matches AgentMessageData)");
            return cachedShape;
        }
    }

    /** Preferred: {@link DataShapeManager#getEntity(String)} then definition via bean/reflection (SDK method names vary). */
    private static DataShapeDefinition tryDataShapeFromManager() {
        try {
            DataShape ds = DataShapeManager.getInstance().getEntity(DATA_SHAPE_NAME);
            if (ds == null) {
                return null;
            }
            Object def = tryInvokeDataShapeDefFromDataShape(ds);
            return def instanceof DataShapeDefinition ? (DataShapeDefinition) def : null;
        } catch (Exception e) {
            LOG.warn("AgentMessageStream: DataShapeManager.getEntity({}): {}", DATA_SHAPE_NAME, e.getMessage());
            return null;
        }
    }

    private static Object tryInvokeDataShapeDefFromDataShape(DataShape ds) {
        for (String method : new String[] {"getDataShapeDefinition", "GetDataShapeDefinition"}) {
            try {
                return ds.getClass().getMethod(method).invoke(ds);
            } catch (NoSuchMethodException ignored) {
                // next
            } catch (Exception e) {
                LOG.warn("AgentMessageStream: DataShape.{}: {}", method, e.getMessage());
            }
        }
        return null;
    }

    private static Object tryInvokeDataShapeDef(RootEntity ent) throws Exception {
        for (String method : new String[] {"getDataShapeDefinition", "GetDataShapeDefinition"}) {
            try {
                return ent.getClass().getMethod(method).invoke(ent);
            } catch (NoSuchMethodException ignored) {
                // next
            }
        }
        return null;
    }

    private static DataShapeDefinition tryDataShapeFromStreamThing(Thing stream) {
        for (String method : new String[] {
                "getDataShapeDefinition", "GetDataShapeDefinition",
                "getStreamDataShape", "GetStreamDataShape"
        }) {
            try {
                Object o = stream.getClass().getMethod(method).invoke(stream);
                if (o instanceof DataShapeDefinition && !isShapeEmpty((DataShapeDefinition) o)) {
                    return (DataShapeDefinition) o;
                }
            } catch (NoSuchMethodException | IllegalArgumentException ignored) {
                // next
            } catch (Exception e) {
                LOG.warn("AgentMessageStream: {} on Stream: {}", method, e.getMessage());
            }
        }
        return null;
    }

    private static boolean isShapeEmpty(DataShapeDefinition shape) {
        if (shape == null || shape.getFields() == null) {
            return true;
        }
        for (FieldDefinition f : shape.getFields().values()) {
            if (f.getName() != null) {
                return false;
            }
        }
        return true;
    }

    /** Same fields/order as Entities/DataShapes/AgentMessageData.xml */
    private static DataShapeDefinition buildInlineAgentMessageDataShape() {
        DataShapeDefinition dsd = new DataShapeDefinition();
        dsd.addFieldDefinition(field("agentThing", BaseTypes.STRING, 0));
        dsd.addFieldDefinition(field("role", BaseTypes.STRING, 1));
        dsd.addFieldDefinition(field("content", BaseTypes.TEXT, 2));
        dsd.addFieldDefinition(field("toolCallId", BaseTypes.STRING, 3));
        dsd.addFieldDefinition(field("toolCalls", BaseTypes.TEXT, 4));
        dsd.addFieldDefinition(field("promptTokens", BaseTypes.INTEGER, 5));
        dsd.addFieldDefinition(field("completionTokens", BaseTypes.INTEGER, 6));
        dsd.addFieldDefinition(field("llmUsageJson", BaseTypes.TEXT, 7));
        dsd.addFieldDefinition(field("assistantMessageId", BaseTypes.STRING, 8));
        dsd.addFieldDefinition(field(FIELD_EXECUTED_TOOL_NAME, BaseTypes.STRING, 9));
        dsd.addFieldDefinition(field(FIELD_HOST_CONTEXT_SNAPSHOT_JSON, BaseTypes.TEXT, 10));
        dsd.addFieldDefinition(field(FIELD_CHART_GROUPS_JSON, BaseTypes.TEXT, 11));
        return dsd;
    }

    private static boolean shapeHasField(DataShapeDefinition shape, String fieldName) {
        if (shape == null || shape.getFields() == null || fieldName == null || fieldName.isEmpty()) {
            return false;
        }
        for (FieldDefinition f : shape.getFields().values()) {
            if (fieldName.equals(f.getName())) {
                return true;
            }
        }
        return false;
    }

    private static FieldDefinition field(String name, BaseTypes bt, int ordinal) {
        FieldDefinition f = new FieldDefinition();
        f.setName(name);
        f.setBaseType(bt);
        f.setOrdinal(ordinal);
        return f;
    }

    /**
     * Persists a {@code ui_feedback} Stream row (append-only feedback); not an LLM {@link ChatMessage}.
     *
     * @return {@code false} when nothing was appended (missing args, stream unavailable, shape resolution failure, or
     *         platform exception)
     */
    public static boolean appendUiFeedback(String streamThreadKey, String streamEntrySource, String agentThingName,
            String feedbackJson) {
        if (streamThreadKey == null || streamThreadKey.isEmpty() || feedbackJson == null) {
            return false;
        }
        try {
            Thing stream = resolveStreamThing();
            if (stream == null) {
                LOG.warn("AgentMessageStream: Thing [{}] not found; skip ui_feedback append", STREAM_THING_NAME);
                return false;
            }
            DataShapeDefinition shape = loadMessageDataShape(stream);
            if (shape == null) {
                LOG.warn("AgentMessageStream: could not resolve row DataShape; skip ui_feedback append");
                return false;
            }
            InfoTable values = new InfoTable(shape);
            ValueCollection vc = new ValueCollection();
            vc.put("agentThing", new StringPrimitive(agentThingName != null ? agentThingName : ""));
            vc.put("role", new StringPrimitive("ui_feedback"));
            String body = feedbackJson.length() > MAX_CONTENT_CHARS
                    ? feedbackJson.substring(0, MAX_CONTENT_CHARS) + "\n...[truncated]"
                    : feedbackJson;
            vc.put("content", new StringPrimitive(body));
            vc.put("toolCallId", new StringPrimitive(""));
            vc.put("toolCalls", new StringPrimitive(""));
            vc.put("promptTokens", new IntegerPrimitive(0));
            vc.put("completionTokens", new IntegerPrimitive(0));
            vc.put("llmUsageJson", new StringPrimitive(""));
            vc.put("assistantMessageId", new StringPrimitive(""));
            if (shapeHasField(shape, FIELD_EXECUTED_TOOL_NAME)) {
                vc.put(FIELD_EXECUTED_TOOL_NAME, new StringPrimitive(""));
            }
            if (shapeHasField(shape, FIELD_HOST_CONTEXT_SNAPSHOT_JSON)) {
                vc.put(FIELD_HOST_CONTEXT_SNAPSHOT_JSON, new StringPrimitive(""));
            }
            if (shapeHasField(shape, FIELD_CHART_GROUPS_JSON)) {
                vc.put(FIELD_CHART_GROUPS_JSON, new StringPrimitive(""));
            }
            values.addRow(vc);

            String src = streamEntrySource != null && !streamEntrySource.isEmpty() ? streamEntrySource : streamThreadKey;
            ValueCollection params = new ValueCollection();
            params.put("timestamp", new DatetimePrimitive(DateTime.now()));
            params.put("location", null);
            params.put("source", new StringPrimitive(src));
            params.put("sourceType", new StringPrimitive(STREAM_SOURCETYPE_THING));
            params.put("tags", null);
            params.SetInfoTableValue("values", values);

            stream.processServiceRequest("AddStreamEntry", params);
            LOG.info("AgentMessageStream append ui_feedback: thread={}", streamThreadKey);
            return true;
        } catch (Exception e) {
            LOG.warn("AgentMessageStream ui_feedback append failed (thread={}): {}", streamThreadKey, e.getMessage());
            return false;
        }
    }

    /**
     * Persists a {@code context_checkpoint} Stream row (§9.1); not an LLM {@link ChatMessage}.
     *
     * <p><b>The envelope cap is re-asserted, never truncated.</b> {@code toValueRow} and
     * {@link #appendUiFeedback} silently truncate above {@link LargeJsonCaps#STREAM_PERSISTENCE_CHAR_CAP}, which is
     * harmless for prose and fatal for checkpoint JSON: a truncated envelope is unparseable and would fail
     * validation on every future rehydrate, so the conversation would carry a row that looks like continuity and
     * can never be used. §5 invariant 8's {@code 250000} envelope cap is deliberately half the persistence cap, so
     * a checkpoint that passed validation can never reach the truncation branch — and if one ever did, refusing the
     * append is the only outcome that does not persist a lie.
     *
     * <p>The size assertion runs ahead of Stream resolution rather than beside the row build: an over-cap envelope
     * is a caller defect whose answer is the same whether or not the platform is reachable, and failing there keeps
     * the refusal observable rather than conditional on deployment state.
     *
     * <p>Callers own the §12 {@code STREAM_APPEND_FAILED} event; like {@link #appendUiFeedback} this returns a
     * boolean and never throws into the post-turn path.
     *
     * @param checkpointEnvelopeJson a serialized {@code parler.conversation_checkpoint.v1} envelope
     * @return {@code false} when nothing was appended
     */
    public static boolean appendContextCheckpoint(String streamThreadKey, String streamEntrySource,
            String agentThingName, String checkpointEnvelopeJson) {
        if (streamThreadKey == null || streamThreadKey.isEmpty()
                || checkpointEnvelopeJson == null || checkpointEnvelopeJson.isEmpty()) {
            return false;
        }
        if (checkpointEnvelopeJson.length() > ConversationCheckpointCodec.MAX_ENVELOPE_CHARS) {
            LOG.warn("AgentMessageStream: checkpoint envelope is {} chars (cap {}); skip context_checkpoint append "
                            + "rather than persist a truncated envelope",
                    checkpointEnvelopeJson.length(), ConversationCheckpointCodec.MAX_ENVELOPE_CHARS);
            return false;
        }
        try {
            Thing stream = resolveStreamThing();
            if (stream == null) {
                LOG.warn("AgentMessageStream: Thing [{}] not found; skip context_checkpoint append",
                        STREAM_THING_NAME);
                return false;
            }
            DataShapeDefinition shape = loadMessageDataShape(stream);
            if (shape == null) {
                LOG.warn("AgentMessageStream: could not resolve row DataShape; skip context_checkpoint append");
                return false;
            }
            InfoTable values = new InfoTable(shape);
            values.addRow(buildContextCheckpointRow(shape, agentThingName, checkpointEnvelopeJson));

            String src = streamEntrySource != null && !streamEntrySource.isEmpty()
                    ? streamEntrySource : streamThreadKey;
            ValueCollection params = new ValueCollection();
            params.put("timestamp", new DatetimePrimitive(DateTime.now()));
            params.put("location", null);
            params.put("source", new StringPrimitive(src));
            params.put("sourceType", new StringPrimitive(STREAM_SOURCETYPE_THING));
            params.put("tags", null);
            params.SetInfoTableValue("values", values);

            stream.processServiceRequest("AddStreamEntry", params);
            LOG.info("AgentMessageStream append context_checkpoint: thread={} envelopeChars={}",
                    streamThreadKey, checkpointEnvelopeJson.length());
            return true;
        } catch (Exception e) {
            LOG.warn("AgentMessageStream context_checkpoint append failed (thread={}): {}",
                    streamThreadKey, e.getMessage());
            return false;
        }
    }

    /**
     * The §9.1 row as field decisions, independent of the platform primitive types.
     *
     * <p>Split out from {@link #buildContextCheckpointRow} because the decisions are the contract — the literal
     * role, the envelope verbatim, and every user-visible usage column zeroed or empty so no consumer can mistake
     * the row for a billable assistant round — while wrapping them in {@code StringPrimitive} /
     * {@code IntegerPrimitive} is mechanical. {@code IntegerPrimitive} also cannot be loaded in an offline unit
     * test, so keeping the decisions separable is what makes them assertable at all.
     *
     * <p>The two optional fields are omitted when the deployed shape lacks them, exactly as
     * {@link #appendUiFeedback} does, so a deployment still running an older shape accepts the row.
     *
     * @return field name to value, in row order; values are {@link String} or {@link Integer}
     */
    static java.util.LinkedHashMap<String, Object> contextCheckpointRowFields(String agentThingName,
            String checkpointEnvelopeJson, boolean hasExecutedToolName, boolean hasHostContextSnapshotJson) {
        java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("agentThing", agentThingName != null ? agentThingName : "");
        row.put("role", ROLE_CONTEXT_CHECKPOINT);
        row.put("content", checkpointEnvelopeJson != null ? checkpointEnvelopeJson : "");
        row.put("toolCallId", "");
        row.put("toolCalls", "");
        row.put("promptTokens", Integer.valueOf(0));
        row.put("completionTokens", Integer.valueOf(0));
        row.put("llmUsageJson", "");
        row.put("assistantMessageId", "");
        if (hasExecutedToolName) {
            row.put(FIELD_EXECUTED_TOOL_NAME, "");
        }
        if (hasHostContextSnapshotJson) {
            row.put(FIELD_HOST_CONTEXT_SNAPSHOT_JSON, "");
        }
        return row;
    }

    /** Projects {@link #contextCheckpointRowFields} into the platform row type. */
    static ValueCollection buildContextCheckpointRow(DataShapeDefinition shape, String agentThingName,
            String checkpointEnvelopeJson) {
        ValueCollection vc = new ValueCollection();
        for (java.util.Map.Entry<String, Object> e : contextCheckpointRowFields(agentThingName,
                checkpointEnvelopeJson, shapeHasField(shape, FIELD_EXECUTED_TOOL_NAME),
                shapeHasField(shape, FIELD_HOST_CONTEXT_SNAPSHOT_JSON)).entrySet()) {
            vc.put(e.getKey(), e.getValue() instanceof Integer
                    ? new IntegerPrimitive(((Integer) e.getValue()).intValue())
                    : new StringPrimitive((String) e.getValue()));
        }
        if (shapeHasField(shape, FIELD_CHART_GROUPS_JSON)) {
            vc.put(FIELD_CHART_GROUPS_JSON, new StringPrimitive(""));
        }
        return vc;
    }

    private static ValueCollection toValueRow(ChatMessage msg, String agentThingName, int promptTokens, int completionTokens,
            String llmUsageJson, String assistantMessageIdOrNull, DataShapeDefinition shape,
            String hostContextSnapshotJsonOrNull)
            throws Exception {
        ValueCollection vc = new ValueCollection();
        vc.put("agentThing", new StringPrimitive(agentThingName != null ? agentThingName : ""));
        vc.put("role", new StringPrimitive(msg.getRole().name().toLowerCase()));
        String content = msg.getContent();
        if (content != null && content.length() > MAX_CONTENT_CHARS) {
            LOG.warn("AgentMessageStream: truncating content from {} to {} chars", content.length(), MAX_CONTENT_CHARS);
            content = content.substring(0, MAX_CONTENT_CHARS) + "\n...[truncated]";
        }
        vc.put("content", new StringPrimitive(content != null ? content : ""));
        vc.put("toolCallId", new StringPrimitive(msg.getToolCallId() != null ? msg.getToolCallId() : ""));
        if (msg.hasToolCalls()) {
            vc.put("toolCalls", new StringPrimitive(serializeToolCalls(msg.getToolCalls())));
        } else {
            vc.put("toolCalls", new StringPrimitive(""));
        }
        vc.put("promptTokens", new IntegerPrimitive(promptTokens));
        vc.put("completionTokens", new IntegerPrimitive(completionTokens));
        vc.put("llmUsageJson", new StringPrimitive(llmUsageJson != null ? llmUsageJson : ""));
        String amid = assistantMessageIdOrNull != null ? assistantMessageIdOrNull.trim() : "";
        vc.put("assistantMessageId", new StringPrimitive(amid));
        if (shapeHasField(shape, FIELD_CHART_GROUPS_JSON)) {
            // C3b-1: only the final assistant row (the one with an assistantMessageId) carries the manifests.
            String groups = msg.getRole() == ChatMessage.Role.ASSISTANT && !amid.isEmpty()
                    ? com.thingworx.things.agent.tools.AgentToolContext.takeChartGroupsJsonForFinalAssistantRow()
                    : "";
            vc.put(FIELD_CHART_GROUPS_JSON, new StringPrimitive(groups));
        }
        if (shapeHasField(shape, FIELD_EXECUTED_TOOL_NAME)) {
            String etn = "";
            if (msg.getRole() == ChatMessage.Role.TOOL) {
                String t = msg.getExecutedToolName();
                etn = t != null ? t : "";
            }
            vc.put(FIELD_EXECUTED_TOOL_NAME, new StringPrimitive(etn));
        }
        if (shapeHasField(shape, FIELD_HOST_CONTEXT_SNAPSHOT_JSON)) {
            String snap = "";
            if (msg.getRole() == ChatMessage.Role.USER && hostContextSnapshotJsonOrNull != null) {
                snap = hostContextSnapshotJsonOrNull;
            }
            vc.put(FIELD_HOST_CONTEXT_SNAPSHOT_JSON, new StringPrimitive(snap));
        }
        return vc;
    }

    private static String serializeToolCalls(List<ToolCall> toolCalls) throws Exception {
        ArrayNode arr = JSON.createArrayNode();
        for (ToolCall tc : toolCalls) {
            ObjectNode o = JSON.createObjectNode();
            o.put("id", tc.getId() != null ? tc.getId() : "");
            String fn = tc.getFunctionName() != null ? tc.getFunctionName() : "";
            o.put("name", fn);
            String rawArgs = tc.getArguments() != null ? tc.getArguments() : "{}";
            o.put("arguments", ProtectedValuePolicy.redactPersistedToolArgumentsJson(rawArgs, fn));
            arr.add(o);
        }
        return JSON.writeValueAsString(arr);
    }
}
