package com.thingworx.things.agent.skillregistry;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SkillRegistryCatalogFormatterTest {

    @Test
    void emitsBothSourcesSortedByShortId() {
        List<SkillRegistryDescriptor> ds = List.of(
                new SkillRegistryDescriptor("Foo", "Foo T", "Foo W", SkillSourceKind.SERVICE, "_skill_Foo", null, null),
                new SkillRegistryDescriptor("Bar", "Bar T", "Bar W", SkillSourceKind.REPOSITORY, null, "Repo", "/Bar/SKILL.md"));
        String md = SkillRegistryCatalogFormatter.format(ds);
        assertTrue(md.contains("(`Bar`, source `repository`)"));
        assertTrue(md.contains("(`Foo`, source `service`)"));
        assertTrue(md.indexOf("Bar") < md.indexOf("Foo"));
    }
}
