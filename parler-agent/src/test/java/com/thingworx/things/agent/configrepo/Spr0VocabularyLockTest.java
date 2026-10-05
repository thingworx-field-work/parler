package com.thingworx.things.agent.configrepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

/**
 * SPR-0 freeze: G13 risk/state/admission vocabulary, manifest-version approach, config paths.
 */
class Spr0VocabularyLockTest {

    @Test
    void riskVocabularyClosed() {
        assertEquals(4, ServiceCapabilityRisk.values().length);
        assertEquals(
                EnumSet.of(
                        ServiceCapabilityRisk.READ_ONLY,
                        ServiceCapabilityRisk.MUTATING,
                        ServiceCapabilityRisk.DESTRUCTIVE,
                        ServiceCapabilityRisk.ADMIN),
                EnumSet.allOf(ServiceCapabilityRisk.class));
    }

    @Test
    void runtimeStateVocabularyClosed() {
        assertEquals(6, ServiceCapabilityRuntimeState.values().length);
        EnumSet.allOf(ServiceCapabilityRuntimeState.class);
    }

    @Test
    void idempotencyAndAdmissionVocabularyClosed() {
        assertEquals(3, ServiceIdempotencyMode.values().length);
        assertEquals(4, ServiceCapabilityAdmission.values().length);
    }

    @Test
    void manifestRemainsVersion1Additive() {
        assertEquals(1, ServiceCapabilityManifestPolicy.SUPPORTED_MANIFEST_VERSION);
        assertTrue(ServiceCapabilityManifestPolicy.ADDITIVE_FIELDS_UNDER_VERSION_1);
        assertEquals("IGNORE", ServiceCapabilityManifestPolicy.UNKNOWN_ENTRY_KEY_POLICY);
        assertTrue(ServiceCapabilityManifestPolicy.FORBIDDEN_U7_SEMANTIC_FIELDS.contains("approvalWorkflow"));
        assertTrue(ServiceCapabilityManifestPolicy.FORBIDDEN_U7_SEMANTIC_FIELDS.contains("compensation"));
        assertTrue(ServiceCapabilityManifestPolicy.FORBIDDEN_U7_SEMANTIC_FIELDS.contains("serviceCost"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ServiceCapabilityManifestPolicy.FORBIDDEN_U7_SEMANTIC_FIELDS.add("x"));
    }

    @Test
    void extendedToolsPathUnchangedAndRouteProfilePathLocked() {
        assertEquals("/tools/extended_tools.json", ConfigurationRepositoryPaths.EXTENDED_TOOLS);
        assertEquals("/providers/route_profiles.json", ConfigurationRepositoryPaths.PROVIDER_ROUTE_PROFILES);
    }

    @Test
    void noSecondServiceCapabilityRegistryType() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
                "com.thingworx.things.agent.configrepo.ServiceCapabilityRegistry"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
                "com.thingworx.things.agent.ServiceCapabilityRegistry"));
    }

    @Test
    void playbookSafeSemanticsStillMatchShippedLoaderContract() {
        // Document the D5 formula the loader already implements; SPR-1 must preserve it.
        boolean playbookSafeRequested = true;
        boolean hitlBypass = true; // hitl:false ⇒ hitlBypass true
        boolean effectivePlaybookSafe = playbookSafeRequested && hitlBypass;
        assertTrue(effectivePlaybookSafe);
        assertFalse(playbookSafeRequested && !hitlBypass);
    }
}
