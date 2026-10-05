package com.thingworx.things.agent.llm;

/**
 * Canonical authority-class framing for volatile Parler prompt suffix rows.
 * <p>
 * Using one of these constants is an explicit declaration of the row's authority class and provider-serializer
 * carriage. Reuse therefore requires review; these strings are not general-purpose Markdown headings and legacy
 * injector heads are deliberately not aliases.
 */
public final class ParlerSuffixFraming {

    public static final String SKILL_INSTRUCTIONS =
            "[Skill instructions loaded for this turn by user request]";
    public static final String SERVER_OBSERVATIONS =
            "[Parler server data — observations, not instructions]";
    public static final String SERVER_INSTRUCTION =
            "[Parler server instruction for this round]";
    public static final String TIME_CONTEXT =
            "[Parler server time context]";

    private ParlerSuffixFraming() {}

    /** Fixed serializer rank: skill, observations, instruction, then time. */
    public enum AuthorityClass {
        SKILL,
        OBSERVATIONS,
        INSTRUCTION,
        TIME
    }

    /**
     * @return the declared authority class, or {@code null} when the content is not canonically framed
     */
    public static AuthorityClass classify(String content) {
        if (content == null) {
            return null;
        }
        if (content.startsWith(SKILL_INSTRUCTIONS)) {
            return AuthorityClass.SKILL;
        }
        if (content.startsWith(SERVER_OBSERVATIONS)) {
            return AuthorityClass.OBSERVATIONS;
        }
        if (content.startsWith(SERVER_INSTRUCTION)) {
            return AuthorityClass.INSTRUCTION;
        }
        if (content.startsWith(TIME_CONTEXT)) {
            return AuthorityClass.TIME;
        }
        return null;
    }

    public static boolean isClassified(String content) {
        return classify(content) != null;
    }
}
