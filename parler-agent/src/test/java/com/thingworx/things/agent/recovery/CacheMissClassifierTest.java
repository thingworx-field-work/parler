package com.thingworx.things.agent.recovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.tools.AgentToolContext;

class CacheMissClassifierTest {

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void malformedAndBlank_unproven() {
        assertFalse(CacheMissClassifier.isLiveNotFoundProven(null));
        assertFalse(CacheMissClassifier.isLiveNotFoundProven(""));
        assertFalse(CacheMissClassifier.isLiveNotFoundProven("not-a-uuid"));
        assertFalse(CacheMissClassifier.isLiveNotFoundProven("legacy-compact-id"));
    }

    @Test
    void foreignWellFormedUuid_unprovenWithoutDescriptor() {
        String foreign = UUID.randomUUID().toString();
        assertFalse(CacheMissClassifier.isLiveNotFoundProven(foreign));
    }

    @Test
    void wellFormedWithCurrentPrincipalDescriptor_proven() {
        String id = UUID.randomUUID().toString();
        TabularArtifactHub.rememberDescriptorForProofTests(id,
                SourceDescriptor.builder().sourceRouteId("query_numeric_property_history").build());
        assertTrue(CacheMissClassifier.isLiveNotFoundProven(id));
    }
}
