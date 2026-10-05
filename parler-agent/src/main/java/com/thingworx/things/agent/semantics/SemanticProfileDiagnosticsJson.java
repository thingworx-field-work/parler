package com.thingworx.things.agent.semantics;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Safe operator/diagnostics JSON for semantic-profile refresh services (no profile dump). */
public final class SemanticProfileDiagnosticsJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SemanticProfileDiagnosticsJson() {}

    public static String unavailable() {
        return "{\"status\":\"error\",\"code\":\"SEMANTIC_PROFILE_UNAVAILABLE\","
                + "\"message\":\"No semantic profile snapshot is available.\"}";
    }

    public static String diagnosticsJson(SemanticProfileSnapshot snap, boolean refreshed) {
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", snap != null && snap.isLoaded() ? "success" : "error");
            if (refreshed) {
                o.put("refreshed", true);
            }
            if (snap == null) {
                o.put("loaded", false);
                o.put("snapshotStatus", "unavailable");
                o.putArray("diagnostics");
                return MAPPER.writeValueAsString(o);
            }
            o.put("loaded", snap.isLoaded());
            o.put("stale", snap.isStale());
            o.put("snapshotStatus", snap.snapshotStatus());
            o.put("profileId", snap.profileId());
            o.put("version", snap.version());
            o.put("digest", snap.digest());
            o.put("assetTypeCount", snap.assetTypeCount());
            o.put("roleCount", snap.roleCount());
            String src = snap.effectiveSourcePath();
            if (src != null && !src.isEmpty()) {
                o.put("sourcePath", src);
            }
            if (snap.lastSuccessfulRefresh() != null) {
                o.put("lastSuccessfulRefresh", snap.lastSuccessfulRefresh().toString());
            }
            if (snap.lastAttemptedRefresh() != null) {
                o.put("lastAttemptedRefresh", snap.lastAttemptedRefresh().toString());
            }
            o.set("diagnostics", diagnosticsArray(snap.diagnostics()));
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"SEMANTIC_PROFILE_DIAGNOSTICS_ERROR\",\"message\":\""
                    + (e.getMessage() != null ? e.getMessage().replace("\"", "'") : "") + "\"}";
        }
    }

    public static ArrayNode diagnosticsArray(List<SemanticProfileDiagnostic> diagnostics) {
        ArrayNode arr = MAPPER.createArrayNode();
        if (diagnostics == null) {
            return arr;
        }
        for (SemanticProfileDiagnostic d : diagnostics) {
            ObjectNode n = arr.addObject();
            n.put("severity", d.severity().name().toLowerCase());
            n.put("code", d.code());
            n.put("message", d.message());
        }
        return arr;
    }
}
