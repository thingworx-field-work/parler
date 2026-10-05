package com.thingworx.things.agent.skillregistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SkillMarkdownParserTest {

    @Test
    void noFrontmatter_wholeFileIsBody() {
        SkillMarkdownParser.Result r = SkillMarkdownParser.parse("# Hi\n\nBody", "Foo");
        assertEquals("Foo", r.title());
        assertEquals("", r.whenToUse());
        assertEquals("# Hi\n\nBody", r.bodyForLlm());
        assertFalse(r.nameMismatch());
    }

    @Test
    void frontmatterMapsDescriptionToWhen() {
        String md = "---\ntitle: T\ndescription: Use when testing\n---\n# X\n\nHello";
        SkillMarkdownParser.Result r = SkillMarkdownParser.parse(md, "X");
        assertEquals("T", r.title());
        assertEquals("Use when testing", r.whenToUse());
        assertTrue(r.bodyForLlm().contains("Hello"));
    }

    @Test
    void whenToUseWinsOverDescription() {
        String md = "---\nwhen_to_use: A\ndescription: B\n---\nBody";
        SkillMarkdownParser.Result r = SkillMarkdownParser.parse(md, "S");
        assertEquals("A", r.whenToUse());
    }

    @Test
    void nameMismatchDetected() {
        String md = "---\nname: Other\n---\nB";
        SkillMarkdownParser.Result r = SkillMarkdownParser.parse(md, "S");
        assertTrue(r.nameMismatch());
    }

    @Test
    void bomAndCrlfFrontmatterParsed() {
        String md = "\uFEFF---\r\ntitle: T\r\nwhen_to_use: W\r\n---\r\nBody";
        SkillMarkdownParser.Result r = SkillMarkdownParser.parse(md, "X");
        assertEquals("T", r.title());
        assertEquals("W", r.whenToUse());
        assertEquals("Body", r.bodyForLlm());
    }

    @Test
    void emptyBodyAfterFrontmatter() {
        SkillMarkdownParser.Result r = SkillMarkdownParser.parse("---\ntitle: T\n---\n", "X");
        assertEquals("T", r.title());
        assertEquals("", r.bodyForLlm());
    }
}
