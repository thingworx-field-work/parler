package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SkillShortIdGrammarTest {

    @Test
    void validIds() {
        assertTrue(SkillShortIdGrammar.isValid("a"));
        assertTrue(SkillShortIdGrammar.isValid("OrderWorkflow"));
        assertTrue(SkillShortIdGrammar.isValid("a_b-1"));
    }

    @Test
    void invalidIds() {
        assertFalse(SkillShortIdGrammar.isValid(null));
        assertFalse(SkillShortIdGrammar.isValid(""));
        assertFalse(SkillShortIdGrammar.isValid("1bad"));
        assertFalse(SkillShortIdGrammar.isValid("_skill_Foo"));
        assertFalse(SkillShortIdGrammar.isValid("bad.id"));
    }
}
