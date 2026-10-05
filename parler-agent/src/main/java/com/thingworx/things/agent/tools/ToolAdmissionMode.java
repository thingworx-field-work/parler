package com.thingworx.things.agent.tools;

import java.util.Locale;

/**
 * Tool-schema admission mode (docs/operations/tool-schema-admission-control.md §2.2). The agentThing's
 * {@code toolAdmissionMode} AgentSettings string parses to one of these; unknown/blank values fall back to
 * {@link #OFF} (current behavior).
 */
public enum ToolAdmissionMode {
    /** Advertise all tools — current behavior, default, A/B baseline and fallback. No-op pass-through. */
    OFF,
    /** Drop irrelevant buckets up front by deterministic host-context/intent signals; keep the core set. */
    NARROW,
    /** Advertise core + a deferred {@code load_tool_schemas} catalog; load tool schemas on demand (§2.8). */
    LAZY;

    /** Parse the AgentSettings string; null/blank/unknown → {@link #OFF}. Case-insensitive. */
    public static ToolAdmissionMode parse(String raw) {
        if (raw == null) {
            return OFF;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "narrow":
                return NARROW;
            case "lazy":
                return LAZY;
            case "off":
            case "":
            default:
                return OFF;
        }
    }
}
