package com.thingworx.things.agent.configrepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.LeadingStablePromptComposer;
import com.thingworx.things.agent.PromptContextCacheSnapshot;

class ExternalSystemPromptAssemblyTest {

    @Test
    void externalFileMode_replacesEntireStableBlock_andIgnoresPerCallOverride() {
        ExternalSystemPromptSelection external =
                ExternalSystemPromptSelection.externalFile("/SystemPrompt/x.md", "  Whole external block  ");
        String out = ExternalSystemPromptAssembly.resolveLeadingStableSystemPrompt(
                external,
                "per-call override must not apply",
                "agent settings base",
                true,
                null,
                "full_table",
                "workflow catalog");
        assertEquals("Whole external block", out);
        assertFalse(out.contains("per-call override"));
        assertFalse(out.contains(LeadingStablePromptComposer.FINAL_ANSWER_EVIDENCE_RULE));
    }

    @Test
    void defaultMode_usesExistingComposer_andPerCallOverride() {
        String out = ExternalSystemPromptAssembly.resolveLeadingStableSystemPrompt(
                ExternalSystemPromptSelection.defaultSelection(),
                "override base",
                "agent settings",
                false,
                null,
                "full_table",
                "");
        assertTrue(out.startsWith("override base"));
        assertTrue(out.contains(LeadingStablePromptComposer.FINAL_ANSWER_EVIDENCE_RULE));
    }

    @Test
    void fallbackSelection_usesDefaultAssembly() {
        ExternalSystemPromptSelection external =
                ExternalSystemPromptSelection.fallback("multiple_files: a.md, b.md");
        String out = ExternalSystemPromptAssembly.resolveLeadingStableSystemPrompt(
                external,
                "",
                "settings",
                false,
                new PromptContextCacheSnapshot("", null, null, "", "", null),
                "full_table",
                "");
        assertTrue(out.contains(LeadingStablePromptComposer.FINAL_ANSWER_EVIDENCE_RULE));
    }
}
