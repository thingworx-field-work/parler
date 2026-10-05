package com.thingworx.things.agent.skillregistry;

import java.util.Map;

import com.thingworx.things.agent.KeyValueDescriptionParser;

/**
 * Parses repository {@code SKILL.md}: optional leading simple key/value frontmatter slab, then body for LLM.
 *
 * @see docs/agent/skill-management.md
 */
public final class SkillMarkdownParser {

    public static final class Result {
        private final String title;
        private final String whenToUse;
        private final String bodyForLlm;
        private final boolean nameMismatch;

        public Result(String title, String whenToUse, String bodyForLlm, boolean nameMismatch) {
            this.title = title != null ? title : "";
            this.whenToUse = whenToUse != null ? whenToUse : "";
            this.bodyForLlm = bodyForLlm != null ? bodyForLlm : "";
            this.nameMismatch = nameMismatch;
        }

        public String title() {
            return title;
        }

        public String whenToUse() {
            return whenToUse;
        }

        public String bodyForLlm() {
            return bodyForLlm;
        }

        public boolean nameMismatch() {
            return nameMismatch;
        }
    }

    private SkillMarkdownParser() {}

    /**
     * @param directoryShortId validated skill directory name (short id)
     */
    public static Result parse(String raw, String directoryShortId) {
        String text = stripBom(raw != null ? raw : "");
        if (text.isEmpty()) {
            return new Result(directoryShortId, "", "", false);
        }
        if (!text.startsWith("---")) {
            return new Result(directoryShortId, "", text, false);
        }
        int second = text.indexOf("\n---", 3);
        if (second < 0) {
            return new Result(directoryShortId, "", text, false);
        }
        String slab = text.substring(3, second).trim();
        String body = text.substring(second + 4).stripLeading();
        Map<String, String> meta = KeyValueDescriptionParser.parse(slab);
        String fmName = meta.get("name");
        boolean nameMismatch = fmName != null && !fmName.isEmpty() && !fmName.equals(directoryShortId);
        String title = meta.getOrDefault("title", directoryShortId);
        if (title == null || title.isEmpty()) {
            title = directoryShortId;
        }
        String whenToUse = meta.get("when_to_use");
        if (whenToUse == null || whenToUse.isEmpty()) {
            whenToUse = meta.getOrDefault("description", "");
        }
        return new Result(title, whenToUse, body, nameMismatch);
    }

    private static String stripBom(String s) {
        if (s.startsWith("\uFEFF")) {
            return s.substring(1);
        }
        return s;
    }

}
