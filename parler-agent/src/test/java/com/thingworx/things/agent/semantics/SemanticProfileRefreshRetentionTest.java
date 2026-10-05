package com.thingworx.things.agent.semantics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import com.thingworx.things.agent.PromptContextCacheSnapshot;
import com.thingworx.things.agent.configrepo.ConfigurationRepositoryPaths;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.types.InfoTable;

/**
 * M1 refresh retention: invalid rebuild keeps prior loaded profile on the prompt-context snapshot
 * (same pattern as taxonomy {@code staleFromPrior}).
 */
class SemanticProfileRefreshRetentionTest {

    private static String readExample() throws Exception {
        String path = "/nearterm/semantics/stacking-robot.example.json";
        try (InputStream in = SemanticProfileRefreshRetentionTest.class.getResourceAsStream(path)) {
            Objects.requireNonNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void snapshotWithSemanticProfile_retainsPriorOnInvalidRefresh() throws Exception {
        SemanticProfileSnapshot prior = SemanticProfileBuilder.parse(readExample()).snapshot();
        assertTrue(prior.isLoaded());

        PromptContextCacheSnapshot snap = new PromptContextCacheSnapshot("", List.of(), List.of(), "", "", Instant.now())
                .withSemanticProfile(prior);
        assertEquals(prior.digest(), snap.getSemanticProfile().digest());

        RepositoryReader badReader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) {
                if (ConfigurationRepositoryPaths.SEMANTIC_PROFILE_JSON.equals(path)) {
                    return "{\"schema\":\"parler-semantic-profile-v0\"}";
                }
                return null;
            }
        };
        SemanticProfileSnapshot built = SemanticProfileBuilder.buildFromRepositoryReader(badReader,
                NOPLogger.NOP_LOGGER, "Agent", Instant.now());
        assertFalse(built.isLoaded());
        SemanticProfileSnapshot retained =
                SemanticProfileSnapshot.staleFromPrior(snap.getSemanticProfile(), Instant.now(), built.diagnostics());
        PromptContextCacheSnapshot refreshed = snap.withSemanticProfile(retained);
        assertTrue(refreshed.getSemanticProfile().isLoaded());
        assertTrue(refreshed.getSemanticProfile().isStale());
        assertEquals(prior.digest(), refreshed.getSemanticProfile().digest());
        assertEquals("cell-a-operations", refreshed.getSemanticProfile().profileId());
    }

    @Test
    void missingProfileFile_retainsPriorAsStale_sameAsInvalidRefresh() throws Exception {
        // Operator note: deleting semantic-profile.json yields not_configured on
        // rebuild; AgentThing keeps the prior loaded profile via staleFromPrior until restart.
        SemanticProfileSnapshot prior = SemanticProfileBuilder.parse(readExample()).snapshot();
        assertTrue(prior.isLoaded());

        PromptContextCacheSnapshot snap = new PromptContextCacheSnapshot("", List.of(), List.of(), "", "", Instant.now())
                .withSemanticProfile(prior);

        RepositoryReader missingReader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) {
                return null;
            }
        };
        SemanticProfileSnapshot built = SemanticProfileBuilder.buildFromRepositoryReader(missingReader,
                NOPLogger.NOP_LOGGER, "Agent", Instant.now());
        assertFalse(built.isLoaded());
        assertEquals("not_configured", built.snapshotStatus());

        SemanticProfileSnapshot retained =
                SemanticProfileSnapshot.staleFromPrior(snap.getSemanticProfile(), Instant.now(), built.diagnostics());
        PromptContextCacheSnapshot refreshed = snap.withSemanticProfile(retained);
        assertTrue(refreshed.getSemanticProfile().isLoaded());
        assertTrue(refreshed.getSemanticProfile().isStale());
        assertEquals(prior.digest(), refreshed.getSemanticProfile().digest());
        assertEquals("cell-a-operations", refreshed.getSemanticProfile().profileId());
    }
}
