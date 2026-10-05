package com.thingworx.things.agent.llm.usage;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.llm.LlmUsageWireIds;

/**
 * One immutable row destined for {@link AgentLlmCallStreamWriter} (CC-7.2).
 */
public final class LlmCallEvent {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String eventId;
    private final String callId;
    private final String logicalCallId;
    private final String turnRequestId;
    private final String conversationId;
    private final String agentThing;
    private final String providerThingName;
    private final String providerFamily;
    private final String apiShapeId;
    private final String requestedModel;
    private final LlmCallKind callKind;
    private final LlmCallEventType eventType;
    private final int sequence;
    private final Instant callStartedAt;
    private final Instant occurredAt;
    private final int schemaVersion;
    private final String eventJson;

    private LlmCallEvent(Builder builder) {
        this.eventId = requireNonBlank(builder.eventId, "eventId");
        this.callId = builder.callId;
        this.logicalCallId = builder.logicalCallId;
        this.turnRequestId = nullToEmpty(builder.turnRequestId);
        this.conversationId = nullToEmpty(builder.conversationId);
        this.agentThing = nullToEmpty(builder.agentThing);
        this.providerThingName = nullToEmpty(builder.providerThingName);
        this.providerFamily = nullToEmpty(builder.providerFamily);
        this.apiShapeId = nullToEmpty(builder.apiShapeId);
        this.requestedModel = nullToEmpty(builder.requestedModel);
        this.callKind = builder.callKind;
        this.eventType = Objects.requireNonNull(builder.eventType, "eventType");
        this.sequence = builder.sequence;
        this.callStartedAt = Objects.requireNonNull(builder.callStartedAt, "callStartedAt");
        this.occurredAt = Objects.requireNonNull(builder.occurredAt, "occurredAt");
        this.schemaVersion = builder.schemaVersion > 0 ? builder.schemaVersion : LlmUsageSnapshot.SCHEMA_VERSION;
        this.eventJson = builder.eventJson != null ? builder.eventJson : "{}";
    }

    public static Builder builder(LlmCallEventType eventType) {
        return new Builder(eventType);
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public String getEventId() {
        return eventId;
    }

    public String getCallId() {
        return callId;
    }

    public String getLogicalCallId() {
        return logicalCallId;
    }

    public String getTurnRequestId() {
        return turnRequestId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getAgentThing() {
        return agentThing;
    }

    public String getProviderThingName() {
        return providerThingName;
    }

    public String getProviderFamily() {
        return providerFamily;
    }

    public String getApiShapeId() {
        return apiShapeId;
    }

    public String getRequestedModel() {
        return requestedModel;
    }

    public LlmCallKind getCallKind() {
        return callKind;
    }

    public LlmCallEventType getEventType() {
        return eventType;
    }

    public int getSequence() {
        return sequence;
    }

    public Instant getCallStartedAt() {
        return callStartedAt;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public String getEventJson() {
        return eventJson;
    }

    public static String providerFamilyFromShape(String apiShapeId) {
        if (apiShapeId == null) {
            return "";
        }
        String shape = apiShapeId.toLowerCase();
        if (shape.startsWith("anthropic-")) {
            return "anthropic";
        }
        if (shape.startsWith("openai-") || shape.startsWith("azure-openai-")) {
            return "openai";
        }
        return "";
    }

    public static String buildEventJson(
            LlmCallContext context,
            LlmCallEventType eventType,
            int sequence,
            LlmCallDispatchState dispatchState,
            LlmCallOutcome outcome,
            Instant cancelObservedAt,
            Instant endedAt,
            Long durationMs,
            Integer httpStatus,
            String providerRequestId,
            String providerResponseId,
            String responseModel,
            String finishReason,
            String errorCategory,
            String errorSummary,
            LlmUsageSnapshot usage,
            String collectorInstanceId) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("schemaVersion", LlmUsageSnapshot.SCHEMA_VERSION);
            ObjectNode identity = JSON.createObjectNode();
            if (context != null) {
                identity.put("logicalCallId", context.getLogicalCallId());
                identity.put("turnRequestId", context.getTurnRequestId());
                identity.put("conversationId", context.getConversationId());
                identity.put("agentThing", context.getAgentThing());
                identity.put("callKind", context.getCallKind().wireValue());
                if (context.getRoundIndex() != null) {
                    identity.put("roundIndex", context.getRoundIndex());
                }
                identity.put("attemptIndex", context.getAttemptIndex());
                if (context.getRetryOfCallId() != null) {
                    identity.put("retryOfCallId", context.getRetryOfCallId());
                }
                LlmUsageWireIds ids = context.getWireIds();
                if (ids != null) {
                    identity.put("providerThingName", ids.getProviderThingName());
                    identity.put("apiShapeId", ids.getApiShapeId());
                    identity.put("requestedModel", ids.getModel());
                }
            }
            root.set("identity", identity);
            if (collectorInstanceId != null) {
                root.put("collectorInstanceId", collectorInstanceId);
            }
            if (dispatchState != null) {
                root.put("dispatchState", dispatchState.wireValue());
            }
            if (outcome != null) {
                root.put("outcome", outcome.wireValue());
            }
            if (cancelObservedAt != null) {
                root.put("cancelObservedAt", cancelObservedAt.toString());
            }
            if (endedAt != null) {
                root.put("endedAt", endedAt.toString());
            }
            if (durationMs != null) {
                root.put("durationMs", durationMs);
            }
            if (httpStatus != null) {
                root.put("httpStatus", httpStatus);
            }
            if (providerRequestId != null) {
                root.put("providerRequestId", providerRequestId);
            }
            if (providerResponseId != null) {
                root.put("providerResponseId", providerResponseId);
            }
            if (responseModel != null) {
                root.put("responseModel", responseModel);
            }
            if (finishReason != null) {
                root.put("finishReason", finishReason);
            }
            if (errorCategory != null || errorSummary != null) {
                ObjectNode error = JSON.createObjectNode();
                if (errorCategory != null) {
                    error.put("category", errorCategory);
                }
                if (errorSummary != null) {
                    error.put("summary", errorSummary);
                }
                root.set("error", error);
            }
            if (usage != null) {
                root.set("usage", usage.toEnvelopeUsageNode(JSON));
            }
            if (context != null && context.getContextPlan() != null && !context.getContextPlan().isEmpty()) {
                root.set("contextPlan", JSON.valueToTree(context.getContextPlan().asMap()));
            }
            String json = JSON.writeValueAsString(root);
            if (json.length() > LlmUsageSnapshot.MAX_EVENT_JSON_CHARS) {
                return oversizeEnvelope(root, usage);
            }
            return json;
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String oversizeEnvelope(ObjectNode root, LlmUsageSnapshot usage) {
        try {
            ObjectNode usageNode = JSON.createObjectNode();
            if (usage != null) {
                usageNode.put("status", LlmUsageSnapshot.UsageStatus.PARTIAL.wireValue());
                usageNode.put("revision", usage.getRevision());
                ObjectNode normalizedNode = JSON.createObjectNode();
                for (Map.Entry<String, Long> entry : usage.getNormalized().entrySet()) {
                    if (entry.getValue() != null) {
                        normalizedNode.put(entry.getKey(), entry.getValue());
                    }
                }
                usageNode.set("normalized", normalizedNode);
                ObjectNode presenceNode = JSON.createObjectNode();
                for (Map.Entry<String, LlmUsageSnapshot.FieldPresence> entry : usage.getPresence().entrySet()) {
                    presenceNode.put(entry.getKey(), entry.getValue().wireValue());
                }
                usageNode.set("presence", presenceNode);
            } else {
                usageNode.put("status", LlmUsageSnapshot.UsageStatus.PARTIAL.wireValue());
            }
            usageNode.put("captureError", "usage_oversize");
            String rawJson = rawUsageJson(usage);
            int rawChars = rawJson.length();
            usageNode.put("rawUsageChars", rawChars);
            if (rawChars > 0) {
                usageNode.put("rawUsageSha256", sha256Hex(rawJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            root.set("usage", usageNode);
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String rawUsageJson(LlmUsageSnapshot usage) {
        if (usage == null || usage.getRawUsage() == null || usage.getRawUsage().isMissingNode()) {
            return "";
        }
        try {
            return JSON.writeValueAsString(usage.getRawUsage());
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] rawUsageBytes(LlmUsageSnapshot usage) {
        if (usage == null || usage.getRawUsage() == null || usage.getRawUsage().isMissingNode()) {
            return new byte[0];
        }
        try {
            return JSON.writeValueAsBytes(usage.getRawUsage());
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            sb.append(String.format("%02x", value));
        }
        return sb.toString();
    }

    public static final class Builder {
        private final LlmCallEventType eventType;
        private String eventId = newId();
        private String callId;
        private String logicalCallId;
        private String turnRequestId;
        private String conversationId;
        private String agentThing;
        private String providerThingName;
        private String providerFamily;
        private String apiShapeId;
        private String requestedModel;
        private LlmCallKind callKind;
        private int sequence;
        private Instant callStartedAt;
        private Instant occurredAt;
        private int schemaVersion = LlmUsageSnapshot.SCHEMA_VERSION;
        private String eventJson;

        private Builder(LlmCallEventType eventType) {
            this.eventType = eventType;
        }

        public Builder eventId(String value) {
            this.eventId = value;
            return this;
        }

        public Builder callId(String value) {
            this.callId = value;
            return this;
        }

        public Builder logicalCallId(String value) {
            this.logicalCallId = value;
            return this;
        }

        public Builder turnRequestId(String value) {
            this.turnRequestId = value;
            return this;
        }

        public Builder conversationId(String value) {
            this.conversationId = value;
            return this;
        }

        public Builder agentThing(String value) {
            this.agentThing = value;
            return this;
        }

        public Builder providerThingName(String value) {
            this.providerThingName = value;
            return this;
        }

        public Builder providerFamily(String value) {
            this.providerFamily = value;
            return this;
        }

        public Builder apiShapeId(String value) {
            this.apiShapeId = value;
            return this;
        }

        public Builder requestedModel(String value) {
            this.requestedModel = value;
            return this;
        }

        public Builder callKind(LlmCallKind value) {
            this.callKind = value;
            return this;
        }

        public Builder sequence(int value) {
            this.sequence = value;
            return this;
        }

        public Builder callStartedAt(Instant value) {
            this.callStartedAt = value;
            return this;
        }

        public Builder occurredAt(Instant value) {
            this.occurredAt = value;
            return this;
        }

        public Builder schemaVersion(int value) {
            this.schemaVersion = value;
            return this;
        }

        public Builder eventJson(String value) {
            this.eventJson = value;
            return this;
        }

        public LlmCallEvent build() {
            return new LlmCallEvent(this);
        }
    }

    private static String requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
