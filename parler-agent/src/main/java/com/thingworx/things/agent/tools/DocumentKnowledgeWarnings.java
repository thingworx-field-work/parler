package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded warning aggregation for document-knowledge tool results (§8).
 */
public final class DocumentKnowledgeWarnings {

    private static final int MAX_DISTINCT_CODES = 20;

    private final Map<String, Entry> byCode = new LinkedHashMap<>();

    public void add(String code, String message) {
        increment(code, message, 1);
    }

    public void increment(String code, String message, int delta) {
        if (code == null || code.isBlank() || delta <= 0) {
            return;
        }
        Entry existing = byCode.get(code);
        if (existing == null) {
            if (byCode.size() >= MAX_DISTINCT_CODES) {
                return;
            }
            byCode.put(code, new Entry(code, message, delta));
        } else {
            existing.count += delta;
            if (message != null && !message.isBlank()) {
                existing.message = message;
            }
        }
    }

    public boolean isEmpty() {
        return byCode.isEmpty();
    }

    public List<Map<String, Object>> toJsonList() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Entry e : byCode.values()) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("code", e.code);
            w.put("message", e.message);
            if (e.count > 1) {
                w.put("count", e.count);
            }
            out.add(w);
        }
        return out;
    }

    private static final class Entry {
        private final String code;
        private String message;
        private int count;

        private Entry(String code, String message, int count) {
            this.code = code;
            this.message = message != null ? message : "";
            this.count = count;
        }
    }
}
