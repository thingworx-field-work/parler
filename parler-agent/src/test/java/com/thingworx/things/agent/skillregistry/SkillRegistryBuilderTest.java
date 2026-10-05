package com.thingworx.things.agent.skillregistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

class SkillRegistryBuilderTest {

    @Test
    void missing_skills_tree_is_silent_no_diagnostics() throws Exception {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public com.thingworx.types.InfoTable getFileListing(String path, String nameMask) throws Exception {
                throw new java.io.FileNotFoundException("path not found");
            }

            @Override
            public String loadText(String path) {
                return null;
            }
        };
        SkillRegistrySnapshot s =
                SkillRegistryBuilder.buildFromRepositoryReader(reader, "RepoThing", "AgentThing", NOPLogger.NOP_LOGGER);
        assertTrue(s.descriptorsByShortId().isEmpty());
        assertTrue(s.diagnostics().isEmpty());
    }

    @Test
    void null_reader_yields_empty_registry() {
        SkillRegistrySnapshot s =
                SkillRegistryBuilder.buildFromRepositoryReader(null, "Repo", "Agent", NOPLogger.NOP_LOGGER);
        assertTrue(s.descriptorsByShortId().isEmpty());
    }
}
