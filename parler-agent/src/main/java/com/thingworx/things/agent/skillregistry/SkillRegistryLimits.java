package com.thingworx.things.agent.skillregistry;

/** Pinned v1 caps from {@code docs/agent/skill-management.md}. */
public final class SkillRegistryLimits {

    public static final int MAX_REPOSITORY_SKILLS = 100;
    public static final int MAX_SKILL_MD_CHARS = 100_000;
    public static final int MAX_SKILL_TITLE_CHARS = 200;
    public static final int MAX_SKILL_WHEN_TO_USE_CHARS = 1_000;
    public static final int MAX_SKILL_DIAGNOSTICS_CHARS = 20_000;

    private SkillRegistryLimits() {}

    /** Runtime guard: repository {@code SKILL.md} raw text must not exceed scan-time cap. */
    public static void requireSkillMarkdownUtf16Length(int utf16Length) {
        if (utf16Length > MAX_SKILL_MD_CHARS) {
            throw new IllegalStateException(
                    "SKILL.md exceeds size limit (" + MAX_SKILL_MD_CHARS + " characters).");
        }
    }
}
