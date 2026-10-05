package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class AlertPromptDefaultsTest {

    @Test
    public void defaultMarkdownIsNonEmptyAndCoversRouting() {
        String md = AlertPromptDefaults.DEFAULT_ALERT_PROMPT_MARKDOWN;
        assertFalse(md.isEmpty());
        assertTrue(md.contains("query_alert_summary"));
        assertTrue(md.contains("thingNames"));
        assertTrue(md.contains("ALERT_SUMMARY_MULTI"));
        assertTrue(md.contains("query_alert_history"));
        assertTrue(md.contains("acknowledge_alerts"));
        assertTrue(md.contains("specific_alerts"));
        assertTrue(md.contains("property_all"));
        assertTrue(md.startsWith("## Alerts"));
        assertTrue(md.contains("AlertSummary fields"));
        assertTrue(md.contains("AlertHistory fields"));
        assertTrue(md.contains("AcknowledgeAlert"));
        // The stable prompt carries the concise block only; the skill body is owned separately.
        assertFalse(md.contains(AlertPromptDefaults.SKILL_ALERT_QUERY_MARKDOWN));
        assertFalse(md.contains("### Skill-style decision tree"));
    }

    @Test
    public void skillBodyIsStandaloneForSkillService() {
        String skill = AlertPromptDefaults.SKILL_ALERT_QUERY_MARKDOWN;
        assertTrue(skill.contains("### Skill-style decision tree"));
        assertTrue(skill.contains("### Ack warnings"));
        assertFalse(skill.contains("### Routing guide"));
    }
}
