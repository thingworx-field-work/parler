package com.thingworx.things.agent.compaction;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * CC-1.4 description-removal projection for M1a baseline pinning.
 */
public final class M1aDescriptionProjection {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private M1aDescriptionProjection() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> stripEditableDescriptions(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return schema;
        }
        Object projected = M1aSchemaWalk.stripEditableDescriptions(schema);
        if (projected instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) projected;
            return map;
        }
        return Map.of();
    }

    public static String structuralProjectionJson(Map<String, Object> schema) throws JsonProcessingException {
        return JSON.writeValueAsString(stripEditableDescriptions(schema));
    }
}
