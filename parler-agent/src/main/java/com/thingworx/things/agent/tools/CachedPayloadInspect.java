package com.thingworx.things.agent.tools;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.cache.ArtifactCacheException;
import com.thingworx.things.agent.cache.ArtifactCacheFaultCode;
import com.thingworx.things.agent.cache.JsonArtifactHub;
import com.thingworx.things.agent.cache.LargeJsonCaps;
import com.thingworx.things.agent.cache.NestedPathGrammar;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * U2 bounded inspect / extract over a cached JSON payload ({@code inspect_cached_payload}).
 * Advertised in M3 with {@code invoke_service} oversized→{@code LARGE_JSON} cutover (E6).
 *
 * <p>Modes: {@code inspect} (structure hints) and {@code extract} (path-selected subtree). When
 * the selected payload exceeds the invoke classify cap, returns another LARGE/compact envelope +
 * handle rather than apparently-complete truncated JSON.
 */
public final class CachedPayloadInspect {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_HINT_KEYS = 32;
    private static final int MAX_HINT_ARRAY_LEN = 8;
    private static final int MAX_HINT_DEPTH = 4;
    private static final int MAX_HINT_CHARS = 4_096;

    private CachedPayloadInspect() {}

    /** BuiltInTools entrypoint. */
    public static String executeInspectCachedPayload(ToolCall call) {
        try {
            JsonNode root = MAPPER.readTree(call == null || call.getArguments() == null
                    || call.getArguments().isBlank() ? "{}" : call.getArguments());
            String cacheId = textOrNull(root, "cacheId");
            String mode = textOrNull(root, "mode");
            String path = textOrNull(root, "path");
            return execute(cacheId, mode, path);
        } catch (ArtifactCacheException e) {
            throw e;
        } catch (Exception e) {
            return error("INVALID_PARAMETERS",
                    e.getMessage() == null ? "Tool arguments must be valid JSON" : e.getMessage());
        }
    }

    public static String execute(String cacheId, String mode, String path) {
        try {
            if (cacheId == null || cacheId.isBlank()) {
                return error("INVALID_ARGUMENT", "cacheId is required");
            }
            String m = mode == null ? "" : mode.trim().toLowerCase();
            if (!"inspect".equals(m) && !"extract".equals(m)) {
                return error("INVALID_ARGUMENT", "mode must be inspect or extract");
            }
            String payload = JsonArtifactHub.lookup(cacheId.trim());
            if (payload == null) {
                return com.thingworx.things.agent.recovery.TypedToolErrorJson.cacheMiss(cacheId.trim(),
                        "cacheId not found in current scope");
            }
            LargeJsonCaps.SizeReport fullSize = LargeJsonCaps.classify(payload);
            if ("inspect".equals(m)) {
                return inspect(cacheId.trim(), payload, fullSize);
            }
            return extract(cacheId.trim(), payload, path, fullSize);
        } catch (ArtifactCacheException e) {
            if (e.code() == ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE) {
                throw e;
            }
            return error(e.code().name(), e.getMessage());
        } catch (Exception e) {
            return error("INSPECT_FAILED", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static String inspect(String cacheId, String payload, LargeJsonCaps.SizeReport fullSize)
            throws Exception {
        JsonNode root;
        try {
            root = MAPPER.readTree(payload);
        } catch (Exception e) {
            ObjectNode o = baseSuccess(cacheId, "inspect", fullSize);
            o.put("resultKind", "LARGE_TEXT_HINTS");
            o.put("parseableJson", false);
            o.put("contentExcerpt", excerpt(payload, 512));
            return MAPPER.writeValueAsString(o);
        }
        ObjectNode o = baseSuccess(cacheId, "inspect", fullSize);
        o.put("resultKind", "LARGE_JSON_HINTS");
        o.put("parseableJson", true);
        o.set("structure", structureHints(root, 0));
        String encoded = MAPPER.writeValueAsString(o);
        if (encoded.length() > MAX_HINT_CHARS) {
            o.remove("structure");
            o.put("structureOmitted", true);
            o.put("structureOmittedReason", "hint envelope exceeded " + MAX_HINT_CHARS + " chars");
        }
        return MAPPER.writeValueAsString(o);
    }

    private static String extract(String cacheId, String payload, String path,
            LargeJsonCaps.SizeReport fullSize) throws Exception {
        if (path == null || path.isBlank()) {
            return error("INVALID_ARGUMENT", "path is required for extract mode");
        }
        List<NestedPathGrammar.Segment> segments;
        try {
            segments = NestedPathGrammar.parse(path);
        } catch (NestedPathGrammar.ParseException e) {
            return error(e.code(), e.getMessage());
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(payload);
        } catch (Exception e) {
            return error("NOT_JSON", "cached payload is not parseable JSON");
        }
        JsonNode selected;
        try {
            selected = walkJson(root, segments);
        } catch (WalkException e) {
            return error(e.code, e.getMessage());
        }
        String selectedJson = MAPPER.writeValueAsString(selected);
        LargeJsonCaps.SizeReport selectedSize = LargeJsonCaps.classify(selectedJson);
        if (selectedSize.exceedsInvokeClassifyCap()) {
            String extractCacheId = storeLargeExtract(cacheId, path, selectedJson);
            ObjectNode o = baseSuccess(cacheId, "extract", fullSize);
            o.put("resultKind", "LARGE_JSON_CACHED");
            o.put("cellPath", path.trim());
            o.put("extractCacheId", extractCacheId);
            o.put("selectedUtf16Chars", selectedSize.utf16Chars());
            o.put("selectedUtf8Bytes", selectedSize.utf8Bytes());
            o.put("selectedSizeClass", selectedSize.sizeClass().name());
            o.put("truncated", false);
            o.put("completeInline", false);
            return MAPPER.writeValueAsString(o);
        }
        ObjectNode o = baseSuccess(cacheId, "extract", fullSize);
        o.put("resultKind", "JSON_EXTRACT");
        o.put("cellPath", path.trim());
        o.put("selectedUtf16Chars", selectedSize.utf16Chars());
        o.put("selectedUtf8Bytes", selectedSize.utf8Bytes());
        o.put("selectedSizeClass", selectedSize.sizeClass().name());
        o.set("value", selected);
        return MAPPER.writeValueAsString(o);
    }

    private static String storeLargeExtract(String parentCacheId, String path, String selectedJson)
            throws Exception {
        com.thingworx.things.agent.source.SourceDescriptor parent =
                com.thingworx.things.agent.cache.TabularArtifactHub.lookupDescriptor(parentCacheId);
        com.thingworx.things.agent.source.SourceDescriptor.Builder b =
                com.thingworx.things.agent.source.SourceDescriptor.builder()
                        .sourceRouteId("inspect_cached_payload.extract:" + path.trim())
                        .completenessStatus(
                                com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus.UNKNOWN)
                        .requestId(AgentToolContext.getParlerRequestId())
                        .addParentSourceCacheId(parentCacheId);
        if (parent != null && parent.executionScopeId() != null) {
            b.executionScopeId(parent.executionScopeId());
        }
        if (parent != null) {
            com.thingworx.things.agent.source.SourceDescriptor.copySemanticProvenance(b, parent);
        }
        return JsonArtifactHub.store(selectedJson, b.build());
    }

    private static JsonNode walkJson(JsonNode root, List<NestedPathGrammar.Segment> segments)
            throws WalkException {
        JsonNode cur = root;
        for (NestedPathGrammar.Segment seg : segments) {
            if (cur == null || cur.isMissingNode() || cur.isNull()) {
                throw new WalkException("PATH_NOT_FOUND", "path resolved to null/missing");
            }
            if (seg.kind() == NestedPathGrammar.SegmentKind.INDEX) {
                if (!cur.isArray()) {
                    throw new WalkException("PATH_NOT_ARRAY", "index applied to non-array");
                }
                if (seg.index() < 0 || seg.index() >= cur.size()) {
                    throw new WalkException("PATH_INDEX_OUT_OF_RANGE",
                            "array index " + seg.index() + " out of range (size=" + cur.size() + ")");
                }
                cur = cur.get(seg.index());
            } else {
                if (!cur.isObject()) {
                    throw new WalkException("PATH_NOT_OBJECT", "field applied to non-object");
                }
                if (!cur.has(seg.fieldName())) {
                    throw new WalkException("PATH_NOT_FOUND",
                            "field \"" + seg.fieldName() + "\" not found");
                }
                cur = cur.get(seg.fieldName());
            }
        }
        return cur;
    }

    private static JsonNode structureHints(JsonNode node, int depth) {
        if (depth >= MAX_HINT_DEPTH) {
            ObjectNode leaf = MAPPER.createObjectNode();
            leaf.put("type", typeName(node));
            leaf.put("depthLimited", true);
            return leaf;
        }
        if (node == null || node.isNull()) {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("type", "null");
            return o;
        }
        if (node.isObject()) {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("type", "object");
            ObjectNode fields = o.putObject("fields");
            int n = 0;
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext() && n < MAX_HINT_KEYS) {
                Map.Entry<String, JsonNode> e = it.next();
                fields.set(e.getKey(), structureHints(e.getValue(), depth + 1));
                n++;
            }
            if (it.hasNext()) {
                o.put("fieldsOmitted", true);
            }
            return o;
        }
        if (node.isArray()) {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("type", "array");
            o.put("length", node.size());
            ArrayNode sample = o.putArray("sample");
            int limit = Math.min(node.size(), MAX_HINT_ARRAY_LEN);
            for (int i = 0; i < limit; i++) {
                sample.add(structureHints(node.get(i), depth + 1));
            }
            if (node.size() > limit) {
                o.put("sampleTruncated", true);
            }
            return o;
        }
        ObjectNode o = MAPPER.createObjectNode();
        o.put("type", typeName(node));
        if (node.isTextual()) {
            o.put("utf16Chars", node.asText().length());
        } else if (node.isNumber()) {
            o.put("number", true);
        } else if (node.isBoolean()) {
            o.put("value", node.asBoolean());
        }
        return o;
    }

    private static String typeName(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            return "object";
        }
        if (node.isArray()) {
            return "array";
        }
        if (node.isTextual()) {
            return "string";
        }
        if (node.isNumber()) {
            return "number";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        return "other";
    }

    private static ObjectNode baseSuccess(String cacheId, String mode, LargeJsonCaps.SizeReport size) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("status", "success");
        o.put("mode", mode);
        o.put("cacheId", cacheId);
        o.put("utf16Chars", size.utf16Chars());
        o.put("utf8Bytes", size.utf8Bytes());
        o.put("sizeClass", size.sizeClass().name());
        return o;
    }

    private static String excerpt(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        if (s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, maxChars) + "…";
    }

    private static String textOrNull(JsonNode root, String field) {
        if (root == null || !root.has(field) || root.get(field).isNull()) {
            return null;
        }
        String t = root.get(field).asText(null);
        return t == null || t.isBlank() ? null : t;
    }

    private static String error(String code, String message) {
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", code == null ? "INSPECT_FAILED" : code);
            o.put("message", message == null ? "" : message);
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"INSPECT_FAILED\",\"message\":\"encode failed\"}";
        }
    }

    private static final class WalkException extends Exception {
        final String code;

        WalkException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
