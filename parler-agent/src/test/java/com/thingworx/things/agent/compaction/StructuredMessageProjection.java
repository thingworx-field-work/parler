package com.thingworx.things.agent.compaction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Canonical structured projection of provider wire bodies for CC-8 stability baselines.
 * Preserves message roles, block order, text, tool-call structure, and tool-result pairing;
 * strips only block-level {@code cache_control} and request-level control fields (handled by
 * partition helpers). Tool-argument keys inside content are preserved.
 */
public final class StructuredMessageProjection {

    public enum WireKind {
        CHAT_COMPLETIONS,
        ANTHROPIC
    }

    private static final ObjectMapper CANONICAL_JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private StructuredMessageProjection() {}

    public static Object projectSystem(Map<String, Object> wireBody, WireKind kind) {
        if (wireBody == null) {
            return null;
        }
        if (kind == WireKind.ANTHROPIC) {
            return stripTopLevelCacheControlFromValue(wireBody.get("system"));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) wireBody.get("messages");
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        Map<String, Object> first = messages.get(0);
        if (first != null && "system".equals(first.get("role"))) {
            return first.get("content");
        }
        return null;
    }

    public static List<Map<String, Object>> projectTools(Map<String, Object> wireBody, WireKind kind) {
        if (wireBody == null || !wireBody.containsKey("tools")) {
            return List.of();
        }
        Object tools = wireBody.get("tools");
        if (!(tools instanceof List<?>)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : (List<?>) tools) {
            if (item instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) item;
                out.add(stripTopLevelCacheControl(typed));
            }
        }
        return out;
    }

    public static List<Map<String, Object>> projectMessages(Map<String, Object> wireBody, WireKind kind) {
        if (wireBody == null) {
            return List.of();
        }
        if (kind == WireKind.ANTHROPIC) {
            return projectAnthropicMessages(wireBody);
        }
        return projectChatCompletionsMessages(wireBody);
    }

    public static String canonicalJson(Object value) {
        try {
            return CANONICAL_JSON.writeValueAsString(normalize(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("canonical JSON serialization failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> projectAnthropicMessages(Map<String, Object> wireBody) {
        Object raw = wireBody.get("messages");
        if (!(raw instanceof List<?>)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> row = (Map<String, Object>) item;
            Map<String, Object> projected = new LinkedHashMap<>();
            projected.put("role", row.get("role"));
            projected.put("blocks", projectAnthropicBlocks(row.get("content")));
            out.add(projected);
        }
        return out;
    }

    private static List<Map<String, Object>> projectAnthropicBlocks(Object content) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (content instanceof String) {
            blocks.add(textBlock((String) content));
            return blocks;
        }
        if (!(content instanceof List<?>)) {
            return blocks;
        }
        for (Object block : (List<?>) content) {
            if (!(block instanceof Map<?, ?>)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = stripTopLevelCacheControl((Map<String, Object>) block);
            String type = String.valueOf(map.get("type"));
            switch (type) {
                case "text":
                    blocks.add(textBlock(stringValue(map.get("text"))));
                    break;
                case "tool_use":
                    blocks.add(toolUseBlock(
                            stringValue(map.get("id")),
                            stringValue(map.get("name")),
                            map.get("input")));
                    break;
                case "tool_result":
                    blocks.add(toolResultBlock(
                            stringValue(map.get("tool_use_id")),
                            stringValue(map.get("content"))));
                    break;
                default:
                    blocks.add(copyBlock(map));
                    break;
            }
        }
        return blocks;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> projectChatCompletionsMessages(Map<String, Object> wireBody) {
        Object raw = wireBody.get("messages");
        if (!(raw instanceof List<?>)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int rowIndex = 0;
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map<?, ?>)) {
                rowIndex++;
                continue;
            }
            Map<String, Object> row = (Map<String, Object>) item;
            String role = stringValue(row.get("role"));
            if (rowIndex == 0 && "system".equals(role)) {
                rowIndex++;
                continue;
            }
            rowIndex++;
            Map<String, Object> projected = new LinkedHashMap<>();
            projected.put("role", role);
            projected.put("blocks", projectOpenAiBlocks(row));
            out.add(projected);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> projectOpenAiBlocks(Map<String, Object> row) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        Object content = row.get("content");
        if (content instanceof String && !((String) content).isEmpty()) {
            blocks.add(textBlock((String) content));
        }
        Object toolCalls = row.get("tool_calls");
        if (toolCalls instanceof List<?>) {
            for (Object tc : (List<?>) toolCalls) {
                if (!(tc instanceof Map<?, ?>)) {
                    continue;
                }
                Map<String, Object> call = (Map<String, Object>) tc;
                Object fnObj = call.get("function");
                if (!(fnObj instanceof Map<?, ?>)) {
                    continue;
                }
                Map<String, Object> fn = (Map<String, Object>) fnObj;
                blocks.add(toolUseBlock(
                        stringValue(call.get("id")),
                        stringValue(fn.get("name")),
                        stringValue(fn.get("arguments"))));
            }
        }
        if ("tool".equals(row.get("role"))) {
            blocks.clear();
            blocks.add(toolResultBlock(
                    stringValue(row.get("tool_call_id")),
                    stringValue(content)));
        }
        return blocks;
    }

    private static Map<String, Object> textBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text != null ? text : "");
        return block;
    }

    private static Map<String, Object> toolUseBlock(String id, String name, Object input) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_use");
        block.put("id", id != null ? id : "");
        block.put("name", name != null ? name : "");
        if (input instanceof String) {
            block.put("arguments", input);
        } else {
            block.put("input", normalize(input));
        }
        return block;
    }

    private static Map<String, Object> toolResultBlock(String toolUseId, String content) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolUseId != null ? toolUseId : "");
        block.put("content", content != null ? content : "");
        return block;
    }

    private static Map<String, Object> copyBlock(Map<String, Object> source) {
        @SuppressWarnings("unchecked")
        Map<String, Object> copy = (Map<String, Object>) normalize(source);
        return copy;
    }

    private static Object stripTopLevelCacheControlFromValue(Object value) {
        if (value instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) value;
            return stripTopLevelCacheControl(typed);
        }
        if (value instanceof List<?>) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<?>) value) {
                out.add(stripTopLevelCacheControlFromValue(item));
            }
            return out;
        }
        return value;
    }

    private static Map<String, Object> stripTopLevelCacheControl(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if ("cache_control".equals(entry.getKey())) {
                continue;
            }
            out.put(entry.getKey(), normalize(entry.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> out = new TreeMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                out.put(String.valueOf(entry.getKey()), normalize(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List<?>) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<?>) value) {
                out.add(normalize(item));
            }
            return out;
        }
        return value;
    }

    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value) : "";
    }
}
