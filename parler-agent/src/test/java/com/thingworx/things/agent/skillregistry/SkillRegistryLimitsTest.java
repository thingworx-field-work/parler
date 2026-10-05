package com.thingworx.things.agent.skillregistry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SkillRegistryLimitsTest {

    @Test
    void requireSkillMarkdownUtf16Length_atLimitAllowed() {
        assertDoesNotThrow(() -> SkillRegistryLimits.requireSkillMarkdownUtf16Length(SkillRegistryLimits.MAX_SKILL_MD_CHARS));
    }

    @Test
    void requireSkillMarkdownUtf16Length_overLimitThrows() {
        assertThrows(IllegalStateException.class,
                () -> SkillRegistryLimits.requireSkillMarkdownUtf16Length(SkillRegistryLimits.MAX_SKILL_MD_CHARS + 1));
    }
}
