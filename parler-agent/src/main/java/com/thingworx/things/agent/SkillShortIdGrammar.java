package com.thingworx.things.agent;

import java.util.regex.Pattern;

/**
 * Short id grammar for repository-backed skills under {@code /skills/<id>/SKILL.md} and the same token shape used in
 * {@code /SkillName} user directives.
 *
 * @see docs/agent/skill-management.md
 */
public final class SkillShortIdGrammar {

    private static final Pattern SHORT_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");

    private SkillShortIdGrammar() {}

    public static boolean isValid(String shortId) {
        return shortId != null && SHORT_ID.matcher(shortId).matches();
    }
}
