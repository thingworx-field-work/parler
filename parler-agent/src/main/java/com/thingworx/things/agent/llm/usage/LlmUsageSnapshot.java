package com.thingworx.things.agent.llm.usage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Parsed provider usage with raw preservation and normalized counters (CC-7.5).
 */
public final class LlmUsageSnapshot {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_EVENT_JSON_CHARS = 262_144;

    public enum UsageStatus {
        PENDING("pending"),
        COMPLETE("complete"),
        PARTIAL("partial"),
        UNAVAILABLE("unavailable"),
        INVALID("invalid"),
        NOT_APPLICABLE("not_applicable");

        private final String wireValue;

        UsageStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }

        public static UsageStatus fromWire(String value) {
            if (value == null || value.isBlank()) {
                return UNAVAILABLE;
            }
            for (UsageStatus status : values()) {
                if (status.wireValue.equals(value)) {
                    return status;
                }
            }
            return UNAVAILABLE;
        }
    }

    public enum FieldPresence {
        REPORTED("reported"),
        DERIVED("derived"),
        ABSENT("absent"),
        NOT_APPLICABLE("not_applicable");

        private final String wireValue;

        FieldPresence(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    private final UsageStatus status;
    private final int revision;
    private final JsonNode rawUsage;
    private final Map<String, Long> normalized;
    private final Map<String, FieldPresence> presence;
    private final String captureError;

    private LlmUsageSnapshot(
            UsageStatus status,
            int revision,
            JsonNode rawUsage,
            Map<String, Long> normalized,
            Map<String, FieldPresence> presence,
            String captureError) {
        this.status = status != null ? status : UsageStatus.UNAVAILABLE;
        this.revision = Math.max(0, revision);
        this.rawUsage = rawUsage;
        this.normalized = normalized != null ? normalized : Collections.emptyMap();
        this.presence = presence != null ? presence : Collections.emptyMap();
        this.captureError = captureError;
    }

    public static LlmUsageSnapshot unavailable() {
        return new LlmUsageSnapshot(UsageStatus.UNAVAILABLE, 0, null, Collections.emptyMap(),
                Collections.emptyMap(), null);
    }

    public static LlmUsageSnapshot notApplicable() {
        return new LlmUsageSnapshot(UsageStatus.NOT_APPLICABLE, 0, null, Collections.emptyMap(),
                Collections.emptyMap(), null);
    }

    public UsageStatus getStatus() {
        return status;
    }

    public int getRevision() {
        return revision;
    }

    public JsonNode getRawUsage() {
        return rawUsage;
    }

    public Map<String, Long> getNormalized() {
        return normalized;
    }

    public Map<String, FieldPresence> getPresence() {
        return presence;
    }

    public String getCaptureError() {
        return captureError;
    }

    public ObjectNode toEnvelopeUsageNode(ObjectMapper mapper) {
        ObjectNode usage = mapper.createObjectNode();
        usage.put("status", status.wireValue());
        usage.put("revision", revision);
        if (captureError != null && !captureError.isEmpty()) {
            usage.put("captureError", captureError);
        }
        if (rawUsage != null && !rawUsage.isMissingNode()) {
            usage.set("rawUsage", rawUsage.deepCopy());
        }
        ObjectNode normalizedNode = mapper.createObjectNode();
        for (Map.Entry<String, Long> entry : normalized.entrySet()) {
            if (entry.getValue() != null) {
                normalizedNode.put(entry.getKey(), entry.getValue());
            }
        }
        usage.set("normalized", normalizedNode);
        ObjectNode presenceNode = mapper.createObjectNode();
        for (Map.Entry<String, FieldPresence> entry : presence.entrySet()) {
            presenceNode.put(entry.getKey(), entry.getValue().wireValue());
        }
        usage.set("presence", presenceNode);
        return usage;
    }

    public static LlmUsageSnapshot withRevision(LlmUsageSnapshot base, int revision) {
        return new LlmUsageSnapshot(base.status, revision, base.rawUsage, base.normalized, base.presence,
                base.captureError);
    }

    public static final class Builder {
        private UsageStatus status = UsageStatus.UNAVAILABLE;
        private int revision;
        private JsonNode rawUsage;
        private final Map<String, Long> normalized = new LinkedHashMap<>();
        private final Map<String, FieldPresence> presence = new LinkedHashMap<>();
        private String captureError;

        public Builder status(UsageStatus value) {
            this.status = value;
            return this;
        }

        public Builder revision(int value) {
            this.revision = value;
            return this;
        }

        public Builder rawUsage(JsonNode value) {
            this.rawUsage = value;
            return this;
        }

        public Builder putNormalized(String key, Long value, FieldPresence fieldPresence) {
            if (value != null) {
                normalized.put(key, value);
            }
            if (fieldPresence != null) {
                presence.put(key, fieldPresence);
            }
            return this;
        }

        public Builder captureError(String value) {
            this.captureError = value;
            return this;
        }

        public LlmUsageSnapshot build() {
            return new LlmUsageSnapshot(status, revision, rawUsage, normalized, presence, captureError);
        }
    }
}
