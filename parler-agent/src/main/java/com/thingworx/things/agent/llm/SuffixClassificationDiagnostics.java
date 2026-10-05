package com.thingworx.things.agent.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Content-free diagnostics for unclassified non-leading {@code SYSTEM} rows encountered by provider serializers.
 */
public final class SuffixClassificationDiagnostics {

    public static final int DIGEST_HEX_LENGTH = 16;

    private final List<Entry> entries;

    private SuffixClassificationDiagnostics(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public static SuffixClassificationDiagnostics fromPlannedMessages(List<ChatMessage> messages) {
        List<Entry> found = new ArrayList<>();
        if (messages != null) {
            for (int i = 0; i < messages.size(); i++) {
                ChatMessage message = messages.get(i);
                String content = message.getContent() != null ? message.getContent() : "";
                if (message.getRole() == ChatMessage.Role.SYSTEM
                        && !(i == 0 && LeadingSystemRow.isStableFirstSystemRow(messages))
                        && !ParlerSuffixFraming.isClassified(content)) {
                    found.add(new Entry(i, message.getRole(), content.length(), digest(content)));
                }
            }
        }
        return new SuffixClassificationDiagnostics(found);
    }

    public List<Entry> getEntries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    private static String digest(String content) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.substring(0, DIGEST_HEX_LENGTH);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static final class Entry {
        private final int plannedIndex;
        private final ChatMessage.Role role;
        private final int charCount;
        private final String contentDigest;

        private Entry(int plannedIndex, ChatMessage.Role role, int charCount, String contentDigest) {
            this.plannedIndex = plannedIndex;
            this.role = role;
            this.charCount = charCount;
            this.contentDigest = contentDigest;
        }

        public int getPlannedIndex() {
            return plannedIndex;
        }

        public ChatMessage.Role getRole() {
            return role;
        }

        public int getCharCount() {
            return charCount;
        }

        public String getContentDigest() {
            return contentDigest;
        }
    }
}
