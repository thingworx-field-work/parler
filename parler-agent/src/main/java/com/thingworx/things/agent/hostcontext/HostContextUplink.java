package com.thingworx.things.agent.hostcontext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;

import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.RejectReason;

/**
 * Fail-open evaluation of AlwaysOn {@code hostContext} ({@code key + context} Host Scope JSON).
 *
 * @see docs/architecture/host-context.md §4, §12
 */
public final class HostContextUplink {

    /** docs/architecture/host-context.md — whole-document cap aligned with prior v1 byte budget */
    public static final int MAX_UTF8_BYTES = 16384;

    public enum Outcome {
        ABSENT,
        ACCEPTED,
        OVERSIZE,
        INVALID_JSON,
        MISSING_KEY,
        UNREGISTERED_GENERIC_FALLBACK,
        SCHEMA_REJECT,
        RENDER_FAILED
    }

    public static final class Decision {
        public final Outcome outcome;
        /** Rendered prompt fragment for LLM ephemeral system row; {@code null} unless {@link Outcome#ACCEPTED} or {@link Outcome#UNREGISTERED_GENERIC_FALLBACK}. */
        public final String renderedPrompt;
        public final String templateKey;
        public final int measuredUtf8Bytes;
        public final String rejectDetail;
        public final RejectReason rejectReason;
        public final List<String> renderDiagnostics;
        public final boolean renderTruncated;

        Decision(
                Outcome outcome,
                String renderedPrompt,
                String templateKey,
                int measuredUtf8Bytes,
                String rejectDetail,
                RejectReason rejectReason,
                List<String> renderDiagnostics,
                boolean renderTruncated) {
            this.outcome = outcome;
            this.renderedPrompt = renderedPrompt;
            this.templateKey = templateKey;
            this.measuredUtf8Bytes = measuredUtf8Bytes;
            this.rejectDetail = rejectDetail;
            this.rejectReason = rejectReason;
            this.renderDiagnostics = renderDiagnostics != null ? List.copyOf(renderDiagnostics) : List.of();
            this.renderTruncated = renderTruncated;
        }

        public boolean genericFallback() {
            return outcome == Outcome.UNREGISTERED_GENERIC_FALLBACK;
        }

        public String renderedPromptOrNull() {
            return outcome == Outcome.ACCEPTED || outcome == Outcome.UNREGISTERED_GENERIC_FALLBACK
                    ? renderedPrompt
                    : null;
        }
    }

    private HostContextUplink() {
    }

    public static Decision evaluate(String hostContextRaw, AgentThing agent) {
        if (hostContextRaw == null || hostContextRaw.isEmpty()) {
            return absent();
        }
        int utf8Len = hostContextRaw.getBytes(StandardCharsets.UTF_8).length;
        if (utf8Len > MAX_UTF8_BYTES) {
            RejectReason r = RejectReason.oversizeUtf8(utf8Len, MAX_UTF8_BYTES);
            return reject(Outcome.OVERSIZE, r, utf8Len);
        }
        final JSONObject root;
        try {
            root = new JSONObject(hostContextRaw);
        } catch (JSONException e) {
            RejectReason r = RejectReason.invalidJson(e.getMessage());
            return reject(Outcome.INVALID_JSON, r, 0);
        }
        String key = root.optString("key", "").trim();
        if (key.isEmpty()) {
            RejectReason r = RejectReason.structured("key", "missing_or_empty", null, null, "key: missing_or_empty");
            return reject(Outcome.MISSING_KEY, r, 0);
        }
        HostContextTemplate template = HostContextTemplateRegistry.find(key, agent);
        if (template == null) {
            HostContextGenericFallback.RenderResult fallback =
                    HostContextGenericFallback.render(key, root);
            if (fallback.rendered.isEmpty()) {
                RejectReason r = RejectReason.structured(null, "generic_fallback_empty", key, null,
                        "generic_fallback_empty key=" + key);
                return reject(Outcome.RENDER_FAILED, r, utf8Len);
            }
            return new Decision(
                    Outcome.UNREGISTERED_GENERIC_FALLBACK,
                    fallback.rendered,
                    key,
                    utf8Len,
                    null,
                    null,
                    List.of(),
                    fallback.truncated);
        }
        JSONObject context = root.optJSONObject("context");
        if (context == null) {
            RejectReason r = RejectReason.structured("context", "missing_or_wrong_type", null, null,
                    "context: missing_or_wrong_type");
            return reject(Outcome.SCHEMA_REJECT, r, 0);
        }
        for (String req : template.requiredContextFields()) {
            if (!context.has(req) || context.isNull(req)) {
                RejectReason r = RejectReason.structured("context." + req, "missing_or_empty", null, null,
                        "context." + req + ": missing_or_empty");
                return reject(Outcome.SCHEMA_REJECT, r, 0);
            }
        }
        HostContextTemplateRenderer.RenderResult rr = HostContextTemplateRenderer.render(template, context);
        if (rr.rendered.isEmpty()) {
            RejectReason r = RejectReason.structured(null, "render_empty", key, null, "render_empty key=" + key);
            return reject(Outcome.RENDER_FAILED, r, 0);
        }
        return new Decision(
                Outcome.ACCEPTED,
                rr.rendered,
                key,
                utf8Len,
                null,
                null,
                rr.diagnostics,
                rr.truncated);
    }

    public static Decision evaluate(String hostContextRaw) {
        return evaluate(hostContextRaw, null);
    }

    private static Decision absent() {
        return new Decision(Outcome.ABSENT, null, null, 0, null, null, List.of(), false);
    }

    private static Decision reject(Outcome outcome, RejectReason r, int utf8Len) {
        return new Decision(outcome, null, null, utf8Len, r.toRejectDetail(), r, List.of(), false);
    }
}
