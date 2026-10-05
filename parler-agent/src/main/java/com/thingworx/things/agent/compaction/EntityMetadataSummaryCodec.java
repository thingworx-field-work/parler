package com.thingworx.things.agent.compaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Tier B compaction for {@code get_entity} tool results stamped with
 * {@value #FORMAT_ENTITY_METADATA_V1}. Emits {@value #FORMAT_ENTITY_METADATA_SUMMARY_V1}.
 */
public final class EntityMetadataSummaryCodec {

    /** Pre-promotion marker on {@code get_entity} tool-result bodies. */
    public static final String FORMAT_ENTITY_METADATA_V1 = "parler.entity.metadata.v1";

    public static final String FORMAT_ENTITY_METADATA_SUMMARY_V1 = "parler.entity.metadata.summary.v1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EntityMetadataSummaryCodec() {}

    /**
     * Whether Tier B may compact this tool-result object to {@link #FORMAT_ENTITY_METADATA_SUMMARY_V1}.
     */
    public static boolean isPromotableEntityMetadata(ObjectNode root, String toolName) {
        if (root == null) {
            return false;
        }
        String fmt = text(root, "$format");
        if (FORMAT_ENTITY_METADATA_SUMMARY_V1.equals(fmt)) {
            return false;
        }
        if (InfoTableMatrixCodec.FORMAT_MATRIX_V1.equals(fmt)
                || LlmToolResultCohortMerger.FORMAT_BUNDLE.equals(fmt)
                || LlmToolResultCohortMerger.FORMAT_MEMBER.equals(fmt)
                || (fmt != null && (fmt.startsWith("parler.infotable.summary") || fmt.startsWith("parler.evidence.stub")))) {
            return false;
        }
        return FORMAT_ENTITY_METADATA_V1.equals(fmt);
    }

    public static String toSummaryJson(ObjectNode source, String toolName, String toolCallId) {
        ObjectNode summary = buildSummary(source, toolName, toolCallId);
        if (summary == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(summary);
        } catch (Exception e) {
            return null;
        }
    }

    static ObjectNode buildSummary(ObjectNode source, String toolName, String toolCallId) {
        if (!FORMAT_ENTITY_METADATA_V1.equals(text(source, "$format")) || !hasEntityIdentity(source)) {
            return null;
        }
        if (!hasEntityMetadataPayload(source)) {
            return null;
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("$format", FORMAT_ENTITY_METADATA_SUMMARY_V1);
        out.put("status", source.has("status") ? source.path("status").asText("success") : "success");
        out.put("originalToolName", toolName != null ? toolName : "unknown_tool");
        out.put("originalToolCallId", toolCallId);
        out.put("entityType", text(source, "entityType"));
        out.put("entityName", text(source, "entityName"));
        String template = text(source, "template");
        if (template != null && !template.isEmpty()) {
            out.put("template", template);
        }
        String description = text(source, "description");
        if (description != null && !description.isEmpty()) {
            out.put("description", description);
        }
        JsonNode tags = source.get("tags");
        if (tags != null && tags.isArray() && tags.size() > 0) {
            out.set("tags", tags.deepCopy());
        }
        ArrayNode props = compactProperties(source.get("properties"));
        if (props != null) {
            out.set("properties", props);
        }
        ArrayNode services = compactNamedMembers(source.get("services"));
        if (services != null) {
            out.set("services", services);
        }
        ArrayNode events = compactNamedMembers(source.get("events"));
        if (events != null) {
            out.set("events", events);
        }
        if (!out.has("properties") && !out.has("services") && !out.has("events")) {
            return null;
        }
        return out;
    }

    private static boolean hasEntityIdentity(ObjectNode root) {
        String et = text(root, "entityType");
        String en = text(root, "entityName");
        return et != null && !et.isEmpty() && en != null && !en.isEmpty();
    }

    private static boolean hasEntityMetadataPayload(ObjectNode root) {
        return arrayPresent(root, "properties") || arrayPresent(root, "services") || arrayPresent(root, "events");
    }

    private static ArrayNode compactProperties(JsonNode properties) {
        if (properties == null || !properties.isArray() || properties.size() == 0) {
            return null;
        }
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode p : properties) {
            if (p == null || !p.isObject()) {
                continue;
            }
            String name = text((ObjectNode) p, "name");
            if (name == null || name.isEmpty()) {
                continue;
            }
            ObjectNode row = MAPPER.createObjectNode();
            row.put("name", name);
            String bt = text((ObjectNode) p, "baseType");
            row.put("baseType", bt != null && !bt.isEmpty() ? bt : "STRING");
            out.add(row);
        }
        return out.size() > 0 ? out : null;
    }

    private static ArrayNode compactNamedMembers(JsonNode members) {
        if (members == null || !members.isArray() || members.size() == 0) {
            return null;
        }
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode m : members) {
            if (m == null || !m.isObject()) {
                continue;
            }
            String name = text((ObjectNode) m, "name");
            if (name == null || name.isEmpty()) {
                continue;
            }
            ObjectNode row = MAPPER.createObjectNode();
            row.put("name", name);
            out.add(row);
        }
        return out.size() > 0 ? out : null;
    }

    private static boolean arrayPresent(ObjectNode root, String field) {
        JsonNode n = root.get(field);
        return n != null && n.isArray() && n.size() > 0;
    }

    private static String text(ObjectNode o, String field) {
        JsonNode n = o.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        return n.asText();
    }
}
