package com.thingworx.things.agent.skillregistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.playbook.PlaybookCatalogEntry;
import com.thingworx.things.agent.playbook.PlaybookRegistrySnapshot;

class AgentWorkflowCatalogFormatterTest {

    private static SkillRegistryDescriptor skill(String id) {
        return new SkillRegistryDescriptor(id, id + " T", "When " + id, SkillSourceKind.REPOSITORY, null, "Repo",
                "/skills/" + id + "/SKILL.md");
    }

    private static PlaybookRegistrySnapshot playbook(String id) {
        PlaybookCatalogEntry e = new PlaybookCatalogEntry(id, id + " PB", "", "PB when", "/playbooks/" + id + "/playbook.json",
                new JSONObject(), new JSONObject());
        return PlaybookRegistrySnapshot.loaded(Instant.now(), Map.of(id, e), Map.of(), List.of());
    }

    @Test
    void skillsOnly() {
        String md = AgentWorkflowCatalogFormatter.format(List.of(skill("alpha")), null);
        assertTrue(md.contains("Agent skills"));
        assertFalse(md.contains("Agent playbooks"));
        assertFalse(md.contains("---"));
    }

    @Test
    void playbooksOnly() {
        String md = AgentWorkflowCatalogFormatter.format(List.of(), playbook("pb1"));
        assertFalse(md.contains("Agent skills"));
        assertTrue(md.contains("Agent playbooks"));
        assertTrue(md.contains("`pb1`"));
    }

    @Test
    void bothSectionsSeparated() {
        String md = AgentWorkflowCatalogFormatter.format(List.of(skill("skill_a")), playbook("pb_a"));
        assertTrue(md.contains("Agent skills"));
        assertTrue(md.contains("Agent playbooks"));
        assertTrue(md.indexOf("Agent skills") < md.indexOf("---"));
        assertTrue(md.indexOf("---") < md.indexOf("Agent playbooks"));
    }

    @Test
    void emptyRegistry_returnsEmpty() {
        assertEquals("", AgentWorkflowCatalogFormatter.format(List.of(), null));
        assertEquals("", AgentWorkflowCatalogFormatter.format(List.of(),
                PlaybookRegistrySnapshot.empty(Instant.now())));
    }
}
