package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * One row from {@code chunks.jsonl}.
 */
public final class DocumentKnowledgeChunk {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String docId;
    private final String chunkId;
    private final String contentType;
    private final String heading;
    private final List<String> sectionPath;
    private final int pageStart;
    private final int pageEnd;
    private final List<String> tags;
    private final List<DocumentKnowledgeChunk.SignalEntry> signals;
    private final String summary;
    private final String markdown;

    public static final class SignalEntry {
        private final String kind;
        private final String name;

        public SignalEntry(String kind, String name) {
            this.kind = kind;
            this.name = name;
        }

        public String kind() {
            return kind;
        }

        public String name() {
            return name;
        }
    }

    private DocumentKnowledgeChunk(String docId, String chunkId, String contentType, String heading,
            List<String> sectionPath, int pageStart, int pageEnd, List<String> tags,
            List<DocumentKnowledgeChunk.SignalEntry> signals, String summary, String markdown) {
        this.docId = docId;
        this.chunkId = chunkId;
        this.contentType = contentType;
        this.heading = heading;
        this.sectionPath = sectionPath;
        this.pageStart = pageStart;
        this.pageEnd = pageEnd;
        this.tags = tags;
        this.signals = signals;
        this.summary = summary;
        this.markdown = markdown;
    }

    public String docId() {
        return docId;
    }

    public String chunkId() {
        return chunkId;
    }

    public String contentType() {
        return contentType;
    }

    public String heading() {
        return heading;
    }

    public List<String> sectionPath() {
        return sectionPath;
    }

    public int pageStart() {
        return pageStart;
    }

    public int pageEnd() {
        return pageEnd;
    }

    public List<String> tags() {
        return tags;
    }

    public List<SignalEntry> signals() {
        return signals;
    }

    public String summary() {
        return summary;
    }

    public String markdown() {
        return markdown;
    }

    /**
     * @return parsed chunk or empty when JSON/shape is unusable
     */
    public static Optional<DocumentKnowledgeChunk> parseJsonLine(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(line);
            String docId = requiredText(root, "docId");
            String chunkId = requiredText(root, "chunkId");
            if (docId == null || chunkId == null) {
                return Optional.empty();
            }
            int pageStart = root.path("pageStart").asInt(0);
            int pageEnd = root.path("pageEnd").asInt(pageStart);
            List<String> sectionPath = readStringArray(root.get("sectionPath"));
            List<String> tags = readStringArray(root.get("tags"));
            List<SignalEntry> signals = readSignals(root.get("signals"));
            return Optional.of(new DocumentKnowledgeChunk(
                    docId,
                    chunkId,
                    optionalText(root, "contentType"),
                    optionalText(root, "heading"),
                    sectionPath,
                    pageStart,
                    pageEnd,
                    tags,
                    signals,
                    optionalText(root, "summary"),
                    optionalText(root, "markdown")));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static List<String> readStringArray(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : arr) {
            if (item != null && item.isTextual()) {
                out.add(item.asText());
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static List<SignalEntry> readSignals(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<SignalEntry> out = new ArrayList<>();
        for (JsonNode item : arr) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String name = optionalText(item, "name");
            if (name != null && !name.isBlank()) {
                out.add(new SignalEntry(optionalText(item, "kind"), name.trim()));
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull() || !n.isTextual()) {
            return null;
        }
        String t = n.asText().trim();
        return t.isEmpty() ? null : t;
    }

    private static String optionalText(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull() || !n.isTextual()) {
            return null;
        }
        return n.asText();
    }
}
