package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds standalone {@code /SkillName} directives in the user message. A token counts only if it has proper
 * boundaries and {@code SkillName} is in the caller-provided whitelist (registered repository skill short ids).
 * Only whitelisted tokens are removed from the cleaned message; unknown or boundary-invalid text stays verbatim.
 */
public final class SkillSlashParser {

    /**
     * Left: start of string or whitespace. Right: end, whitespace, or common sentence punctuation (ASCII + CJK).
     * Skill id: {@code [A-Za-z][A-Za-z0-9_-]*} (group 2). Full token including slash is group 1.
     */
    private static final Pattern STANDALONE_SLASH_SKILL = Pattern.compile(
            "(?<=\\A|[\\s\\u00A0\\u3000])(/([A-Za-z][A-Za-z0-9_-]*))(?=[\\s\\u00A0\\u3000.,:;!?\\)\\]\\}，。！？；：、]|\\z)");

    private SkillSlashParser() {}

    public static final class Result {
        private final String cleanedMessage;
        private final List<String> skillShortNamesInOrder;

        public Result(String cleanedMessage, List<String> skillShortNamesInOrder) {
            this.cleanedMessage = cleanedMessage;
            this.skillShortNamesInOrder = skillShortNamesInOrder;
        }

        public String cleanedMessage() {
            return cleanedMessage;
        }

        public List<String> skillShortNamesInOrder() {
            return skillShortNamesInOrder;
        }
    }

    /**
     * @param validShortIds exact short ids from {@code SkillRegistrySnapshot} (typically {@code /skills/<id>/SKILL.md}
     *                      on {@code configurationRepository}); if null or empty, no tokens are treated as skills and
     *                      the message is returned unchanged
     */
    public static Result parse(String message, Set<String> validShortIds) {
        if (message == null) {
            return new Result("", List.of());
        }
        if (message.isEmpty()) {
            return new Result("", List.of());
        }
        if (validShortIds == null || validShortIds.isEmpty()) {
            return new Result(message, List.of());
        }

        List<int[]> spansToRemove = new ArrayList<>();
        LinkedHashSet<String> orderedUnique = new LinkedHashSet<>();
        Matcher m = STANDALONE_SLASH_SKILL.matcher(message);
        while (m.find()) {
            String id = m.group(2);
            if (validShortIds.contains(id)) {
                orderedUnique.add(id);
                spansToRemove.add(new int[] { m.start(1), m.end(1) });
            }
        }

        String cleaned = message;
        for (int i = spansToRemove.size() - 1; i >= 0; i--) {
            int[] sp = spansToRemove.get(i);
            cleaned = cleaned.substring(0, sp[0]) + cleaned.substring(sp[1]);
        }
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return new Result(cleaned, new ArrayList<>(orderedUnique));
    }
}
