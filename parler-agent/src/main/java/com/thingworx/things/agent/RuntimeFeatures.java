package com.thingworx.things.agent;

import java.util.List;

/** Installed behavioral guarantees, independent of snapshot include-options and active turn state. */
public final class RuntimeFeatures {
    private RuntimeFeatures() {}

    /** Round 6 tool-result guarantees, appended whatever the topic-loader state. */
    static final List<String> TOOL_RESULT_FEATURES = List.of("tool-result-json-node/1", "tool-result-completeness/1",
            "tool-result-budget/1", "tool-result-table-recovery/1", "document-index-refresh/1");

    public static List<String> names(boolean javaTopicLoaderRegistered) {
        List<String> base = javaTopicLoaderRegistered
                ? List.of("topic-load/1", "active-method-tools/1", "entity-document-scope/1", "topic-offer/1", "topic-lane/1",
                        "search-unit-scope/1", "topic-signal-exact/1", "topic-markdown-file/1")
                : List.of("active-method-tools/1");
        List<String> out = new java.util.ArrayList<>(base);
        out.addAll(TOOL_RESULT_FEATURES);
        return List.copyOf(out);
    }
}
