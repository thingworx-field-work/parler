package com.thingworx.things.agent.skillregistry;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class SkillRegistryLoaderTest {

    @Test
    void loadBody_nullRegistry_throwsSkillRegistryUnavailable() {
        assertThrows(SkillRegistryUnavailableException.class, () -> SkillRegistryLoader.loadBody(null, null, "x"));
    }

    @Test
    void loadBody_nullAgent_throwsIllegalArgument() {
        SkillRegistrySnapshot reg = SkillRegistrySnapshot.empty(Instant.now());
        assertThrows(IllegalArgumentException.class, () -> SkillRegistryLoader.loadBody(null, reg, "any"));
    }
}
