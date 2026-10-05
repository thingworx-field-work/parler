package com.thingworx.things.agent.compaction;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Context-aware JSON Schema walk shared by {@link M1aEditableDescriptionMetrics} and
 * {@link M1aDescriptionProjection}. Distinguishes schema nodes from property-name maps and literal values.
 */
final class M1aSchemaWalk {

    private static final Set<String> COMBINATOR_KEYS = Set.of("allof", "anyof", "oneof");
    private static final Set<String> DEF_MAP_KEYS = Set.of("$defs", "definitions");
    private static final Set<String> LITERAL_KEYS = Set.of("default", "examples", "example", "enum", "const");

    private M1aSchemaWalk() {}

    enum Frame {
        SCHEMA,
        PROPERTY_MAP,
        DEFINITION_MAP,
        COMBINATOR_LIST
    }

    static int countEditableDescriptions(Object node) {
        return walkCount(node, Frame.SCHEMA);
    }

    static Object stripEditableDescriptions(Object node) {
        return walkStrip(node, Frame.SCHEMA);
    }

    private static int walkCount(Object node, Frame frame) {
        if (node instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) node;
            if (frame == Frame.PROPERTY_MAP || frame == Frame.DEFINITION_MAP) {
                int total = 0;
                for (Object value : map.values()) {
                    total += walkCount(value, Frame.SCHEMA);
                }
                return total;
            }
            if (frame == Frame.COMBINATOR_LIST) {
                return 0;
            }
            int total = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    continue;
                }
                String key = (String) entry.getKey();
                Object value = entry.getValue();
                String lower = key.toLowerCase(Locale.ROOT);
                if ("description".equals(key) && value instanceof String) {
                    total += ((String) value).getBytes(StandardCharsets.UTF_8).length;
                    continue;
                }
                if ("properties".equals(key) && value instanceof Map<?, ?>) {
                    total += walkCount(value, Frame.PROPERTY_MAP);
                    continue;
                }
                if (DEF_MAP_KEYS.contains(lower) && value instanceof Map<?, ?>) {
                    total += walkCount(value, Frame.DEFINITION_MAP);
                    continue;
                }
                if ("items".equals(key)) {
                    total += walkCount(value, Frame.SCHEMA);
                    continue;
                }
                if (COMBINATOR_KEYS.contains(lower) && value instanceof List<?>) {
                    total += walkCount(value, Frame.COMBINATOR_LIST);
                    continue;
                }
                if ("additionalproperties".equals(lower) && value instanceof Map<?, ?>) {
                    total += walkCount(value, Frame.SCHEMA);
                    continue;
                }
                if (LITERAL_KEYS.contains(lower)) {
                    continue;
                }
                if (value instanceof Map<?, ?> || value instanceof List<?>) {
                    total += walkCount(value, Frame.SCHEMA);
                }
            }
            return total;
        }
        if (node instanceof List) {
            List<?> list = (List<?>) node;
            if (frame == Frame.COMBINATOR_LIST) {
                int total = 0;
                for (Object item : list) {
                    total += walkCount(item, Frame.SCHEMA);
                }
                return total;
            }
            int total = 0;
            for (Object item : list) {
                total += walkCount(item, frame);
            }
            return total;
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static Object walkStrip(Object node, Frame frame) {
        if (node instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) node;
            if (frame == Frame.PROPERTY_MAP || frame == Frame.DEFINITION_MAP) {
                LinkedHashMap<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() instanceof String) {
                        out.put((String) entry.getKey(), walkStrip(entry.getValue(), Frame.SCHEMA));
                    }
                }
                return out;
            }
            if (frame == Frame.COMBINATOR_LIST) {
                return node;
            }
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    continue;
                }
                String key = (String) entry.getKey();
                Object value = entry.getValue();
                String lower = key.toLowerCase(Locale.ROOT);
                if ("description".equals(key)) {
                    continue;
                }
                if ("properties".equals(key) && value instanceof Map<?, ?>) {
                    out.put(key, walkStrip(value, Frame.PROPERTY_MAP));
                    continue;
                }
                if (DEF_MAP_KEYS.contains(lower) && value instanceof Map<?, ?>) {
                    out.put(key, walkStrip(value, Frame.DEFINITION_MAP));
                    continue;
                }
                if ("items".equals(key)) {
                    out.put(key, walkStrip(value, Frame.SCHEMA));
                    continue;
                }
                if (COMBINATOR_KEYS.contains(lower) && value instanceof List<?>) {
                    out.put(key, walkStrip(value, Frame.COMBINATOR_LIST));
                    continue;
                }
                if ("additionalproperties".equals(lower) && value instanceof Map<?, ?>) {
                    out.put(key, walkStrip(value, Frame.SCHEMA));
                    continue;
                }
                if (LITERAL_KEYS.contains(lower)) {
                    out.put(key, value);
                    continue;
                }
                if (value instanceof Map<?, ?> || value instanceof List<?>) {
                    out.put(key, walkStrip(value, Frame.SCHEMA));
                } else {
                    out.put(key, value);
                }
            }
            return out;
        }
        if (node instanceof List) {
            List<?> list = (List<?>) node;
            if (frame == Frame.COMBINATOR_LIST) {
                List<Object> out = new ArrayList<>(list.size());
                for (Object item : list) {
                    out.add(walkStrip(item, Frame.SCHEMA));
                }
                return out;
            }
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(walkStrip(item, frame));
            }
            return out;
        }
        return node;
    }
}
