package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Properties;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.configrepo.ParlerPackageVersion;

class ParlerRuntimeVersionTest {

    @Test
    void displayVersionForProperties_prefersArtifactVersion() {
        Properties p = new Properties();
        p.setProperty("artifactVersion", "9.8.7");
        p.setProperty("implementationVersion", "9.8.7.0-SNAPSHOT");
        assertEquals("9.8.7", ParlerRuntimeVersion.displayVersionForProperties(p));
    }

    @Test
    void implementationVersionForProperties_readsImplementationKey() {
        Properties p = new Properties();
        p.setProperty("artifactVersion", "0.1.1");
        p.setProperty("implementationVersion", "0.1.1.0-SNAPSHOT");
        assertEquals("0.1.1.0-SNAPSHOT", ParlerRuntimeVersion.implementationVersionForProperties(p));
    }

    @Test
    void implementationVersionForProperties_fallsBackToDisplayWhenImplMissing() {
        Properties p = new Properties();
        p.setProperty("artifactVersion", "2.0.0");
        assertEquals("2.0.0", ParlerRuntimeVersion.implementationVersionForProperties(p));
    }

    @Test
    void displayVersionForProperties_emptyArtifactFallsBackToManifestOrEmpty() {
        Properties p = new Properties();
        String got = ParlerRuntimeVersion.displayVersionForProperties(p);
        String manifest = ParlerPackageVersion.fromAnchorClass(AgentThing.class);
        if (manifest == null || manifest.isBlank()) {
            assertEquals("", got);
        } else {
            assertEquals(manifest.trim(), got);
        }
    }
}
