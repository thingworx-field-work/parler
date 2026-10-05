package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SkillSlashParserTest {

    @Test
    void registeredSkillWithSpaces() {
        SkillSlashParser.Result r = SkillSlashParser.parse("请使用 /Demo 帮我分析", Set.of("Demo"));
        assertEquals(List.of("Demo"), r.skillShortNamesInOrder());
        assertEquals("请使用 帮我分析", r.cleanedMessage());
    }

    @Test
    void punctuationAfterSkill_fullWidthComma() {
        SkillSlashParser.Result r = SkillSlashParser.parse("请使用 /Demo，帮我分析", Set.of("Demo"));
        assertEquals(List.of("Demo"), r.skillShortNamesInOrder());
        assertTrue(r.cleanedMessage().contains("请使用"));
        assertTrue(r.cleanedMessage().contains("帮我分析"));
        assertTrue(!r.cleanedMessage().contains("/Demo"));
    }

    @Test
    void multipleSkills() {
        SkillSlashParser.Result r = SkillSlashParser.parse("/Demo /SafetyCheck 请一起参考", Set.of("Demo", "SafetyCheck"));
        assertEquals(List.of("Demo", "SafetyCheck"), r.skillShortNamesInOrder());
        assertEquals("请一起参考", r.cleanedMessage().trim());
    }

    @Test
    void registeredSkillOnlyLeavesZeroLengthModelFacingMessage() {
        SkillSlashParser.Result r = SkillSlashParser.parse("/Demo", Set.of("Demo"));
        assertEquals(List.of("Demo"), r.skillShortNamesInOrder());
        assertEquals("", r.cleanedMessage());
    }

    @Test
    void urlDoesNotMatch() {
        String msg = "https://host/api/orders";
        SkillSlashParser.Result r = SkillSlashParser.parse(msg, Set.of("host", "api", "orders"));
        assertTrue(r.skillShortNamesInOrder().isEmpty());
        assertEquals(msg, r.cleanedMessage());
    }

    @Test
    void pathDoesNotMatch() {
        SkillSlashParser.Result r = SkillSlashParser.parse("C:/Users/alex/project", Set.of("Users"));
        assertTrue(r.skillShortNamesInOrder().isEmpty());
    }

    @Test
    void unknownSkillNotStripped() {
        SkillSlashParser.Result r = SkillSlashParser.parse("请参考 /NotRegistered。", Set.of("Demo"));
        assertTrue(r.skillShortNamesInOrder().isEmpty());
        assertTrue(r.cleanedMessage().contains("/NotRegistered"));
    }

    @Test
    void emptyWhitelistLeavesMessage() {
        SkillSlashParser.Result r = SkillSlashParser.parse("/Demo test", Set.of());
        assertTrue(r.skillShortNamesInOrder().isEmpty());
        assertEquals("/Demo test", r.cleanedMessage());
    }

    @Test
    void deduplicatesSameSkill() {
        SkillSlashParser.Result r = SkillSlashParser.parse("/Demo /Demo end", Set.of("Demo"));
        assertEquals(List.of("Demo"), r.skillShortNamesInOrder());
        assertEquals("end", r.cleanedMessage().trim());
    }
}
