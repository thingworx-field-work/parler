package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.PromptContextCacheSnapshot;
import com.thingworx.things.agent.skillregistry.SkillRegistryDescriptor;
import com.thingworx.things.agent.skillregistry.SkillRegistrySnapshot;
import com.thingworx.things.agent.skillregistry.SkillSourceKind;

class ModelFacingSkillAdmissionTest {

    @Test
    void nullSnapshotFalse() {
        assertFalse(ModelFacingSkillAdmission.hasModelFacingSkills(null));
    }

    @Test
    void emptyRegistryFalse() {
        Instant now = Instant.now();
        SkillRegistrySnapshot empty = SkillRegistrySnapshot.empty(now);
        PromptContextCacheSnapshot snap = new PromptContextCacheSnapshot("", Collections.emptyList(),
                Collections.emptyList(), "", "", empty, now);
        assertFalse(ModelFacingSkillAdmission.hasModelFacingSkills(snap));
    }

    @Test
    void oneDescriptorTrue() {
        Instant now = Instant.now();
        Map<String, SkillRegistryDescriptor> m = new LinkedHashMap<>();
        m.put("a",
                new SkillRegistryDescriptor("a", "t", "w", SkillSourceKind.REPOSITORY, null, "repo", "/skills/a/SKILL.md"));
        SkillRegistrySnapshot reg = new SkillRegistrySnapshot(now, m, Collections.emptyList());
        PromptContextCacheSnapshot snap = new PromptContextCacheSnapshot("", Collections.emptyList(),
                Collections.emptyList(), "", "", reg, now);
        assertTrue(ModelFacingSkillAdmission.hasModelFacingSkills(snap));
    }
}
