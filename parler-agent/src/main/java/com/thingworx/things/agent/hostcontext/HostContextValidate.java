package com.thingworx.things.agent.hostcontext;

import org.json.JSONArray;
import org.json.JSONObject;

import com.thingworx.things.agent.AgentThing;

/**
 * Diagnostics for {@code ValidateHostContext(hostScopeJson)} (docs/architecture/host-context.md §13).
 */
public final class HostContextValidate {

    private HostContextValidate() {
    }

    public static JSONObject validate(String hostScopeJson, AgentThing agent) {
        JSONObject out = new JSONObject();
        out.put("parseable", false);
        if (hostScopeJson == null || hostScopeJson.isEmpty()) {
            out.put("absent", true);
            return out;
        }
        out.put("absent", false);
        int utf8Len = hostScopeJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        out.put("utf8Bytes", utf8Len);
        if (utf8Len > HostContextUplink.MAX_UTF8_BYTES) {
            out.put("oversize", true);
            out.put("limitUtf8Bytes", HostContextUplink.MAX_UTF8_BYTES);
            return out;
        }
        out.put("oversize", false);
        JSONObject root;
        try {
            root = new JSONObject(hostScopeJson);
            out.put("parseable", true);
        } catch (Exception e) {
            out.put("parseError", e.getMessage());
            return out;
        }
        String key = root.optString("key", "").trim();
        out.put("key", key.isEmpty() ? JSONObject.NULL : key);
        if (key.isEmpty()) {
            return out;
        }
        HostContextTemplate template = HostContextTemplateRegistry.find(key, agent);
        out.put("templateFound", template != null);
        if (template == null) {
            HostContextGenericFallback.RenderResult fallback = HostContextGenericFallback.render(key, root);
            out.put("genericFallback", true);
            out.put("outcome", HostContextUplink.Outcome.UNREGISTERED_GENERIC_FALLBACK.name());
            out.put("renderedLength", fallback.rendered.length());
            out.put("renderTruncated", fallback.truncated);
            out.put("renderedPromptPreview", fallback.rendered);
            return out;
        }
        out.put("templateDescription", template.description());
        JSONArray reqFields = new JSONArray();
        for (String f : template.requiredContextFields()) {
            reqFields.put(f);
        }
        out.put("requiredContextFields", reqFields);
        JSONObject context = root.optJSONObject("context");
        out.put("contextPresent", context != null);
        if (context != null) {
            JSONArray missing = new JSONArray();
            for (String f : template.requiredContextFields()) {
                if (!context.has(f) || context.isNull(f)) {
                    missing.put(f);
                }
            }
            out.put("missingRequiredFields", missing);
        }
        HostContextTemplateRenderer.RenderResult rr = context != null
                ? HostContextTemplateRenderer.render(template, context)
                : new HostContextTemplateRenderer.RenderResult("", java.util.List.of("context missing"), false);
        out.put("renderedLength", rr.rendered.length());
        out.put("renderTruncated", rr.truncated);
        out.put("renderedPromptPreview", rr.rendered);
        JSONArray diags = new JSONArray();
        for (String d : rr.diagnostics) {
            diags.put(d);
        }
        out.put("diagnostics", diags);
        return out;
    }
}
