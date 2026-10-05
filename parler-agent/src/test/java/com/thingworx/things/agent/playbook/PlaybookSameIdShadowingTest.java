package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.skillregistry.AgentWorkflowCatalogFormatter;
import com.thingworx.things.agent.skillregistry.RepositorySkillScanner;
import com.thingworx.things.agent.skillregistry.SkillRegistryDescriptor;
import com.thingworx.things.agent.skillregistry.SkillRegistrySnapshot;
import com.thingworx.things.agent.tools.SkillSlashParser;

/**
 * Same-id promotion: playbook is model-visible; shadowed skill is not.
 */
class PlaybookSameIdShadowingTest {

    private static String skillMd() {
        return "---\ntitle: Shadowed Skill\nwhen_to_use: Skill when\n---\n# Body";
    }

    @Test
    void sameId_playbookInCatalog_skillAbsent_slashRoutesToPlaybook() throws Exception {
        String sharedId = "foo";
        Set<String> reserved = Set.of(sharedId);
        Map<String, SkillRegistryDescriptor> skillMap = new LinkedHashMap<>();
        List<String> diagnostics = new ArrayList<>();
        RepositorySkillScanner.scanAndMergeSortedDirectories(
                List.of(sharedId),
                "Repo",
                id -> skillMd(),
                skillMap,
                diagnostics,
                null,
                null,
                "Agent",
                reserved);
        assertFalse(skillMap.containsKey(sharedId));
        assertTrue(diagnostics.stream().anyMatch(d -> d.contains("reserved by playbook")));

        SkillRegistrySnapshot skillReg = new SkillRegistrySnapshot(Instant.now(), skillMap, diagnostics);
        assertFalse(skillReg.hasSkill(sharedId),
                "get_agent_skill precondition: shadowed skill id must be absent from SkillRegistrySnapshot");

        PlaybookCatalogEntry playbookEntry = new PlaybookCatalogEntry(
                sharedId,
                "Foo Playbook",
                "Long description not in catalog",
                "When playbook",
                "/playbooks/foo/playbook.json",
                new JSONObject(),
                new JSONObject());
        PlaybookRegistrySnapshot playbookReg = PlaybookRegistrySnapshot.loaded(
                Instant.now(), Map.of(sharedId, playbookEntry), Map.of(), List.of());

        String catalog = AgentWorkflowCatalogFormatter.format(new ArrayList<>(skillMap.values()), playbookReg);
        assertTrue(catalog.contains("Agent playbooks"));
        assertTrue(catalog.contains("(`foo`)"));
        assertFalse(catalog.contains("Shadowed Skill"));

        ToolDefinition start = PlaybookStartToolDefinitionBuilder.build(playbookReg);
        assertNotNull(start);
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) start.getParametersSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> playbookId = (Map<String, Object>) props.get("playbook_id");
        assertTrue(((String) playbookId.get("description")).contains("foo"));

        Set<String> validSkillShortIds = new HashSet<>(skillMap.keySet());
        validSkillShortIds.removeAll(playbookReg.reservedSlashIds());
        SkillSlashParser.Result skillSlash = SkillSlashParser.parse("/foo compare things", validSkillShortIds);
        assertTrue(skillSlash.skillShortNamesInOrder().isEmpty());

        PlaybookSlashParser.Result playbookSlash = PlaybookSlashParser.parse(
                "/foo {\"assetType\":\"X\"}", playbookReg.reservedSlashIds());
        assertEquals(sharedId, playbookSlash.playbookId());
    }
}
