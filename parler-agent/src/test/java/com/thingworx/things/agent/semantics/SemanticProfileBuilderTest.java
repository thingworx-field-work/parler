package com.thingworx.things.agent.semantics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import com.thingworx.things.agent.configrepo.ConfigurationRepositoryPaths;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.types.InfoTable;

class SemanticProfileBuilderTest {

    private static String readFixture(String name) throws Exception {
        String path = "/nearterm/semantics/" + name;
        try (InputStream in = SemanticProfileBuilderTest.class.getResourceAsStream(path)) {
            Objects.requireNonNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void pathConstant_matchesSp1() {
        assertEquals("/semantics/semantic-profile.json", ConfigurationRepositoryPaths.SEMANTIC_PROFILE_JSON);
        assertEquals("parler-semantic-profile-v1", SemanticProfileBuilder.SCHEMA_V1);
    }

    @Test
    void stackingRobotExample_loadsAndDigestsDeterministically() throws Exception {
        String json = readFixture("stacking-robot.example.json");
        SemanticProfileBuilder.ParseOutcome a = SemanticProfileBuilder.parse(json);
        SemanticProfileBuilder.ParseOutcome b = SemanticProfileBuilder.parse(json);
        assertTrue(a.valid());
        assertTrue(a.snapshot().isLoaded());
        assertEquals("cell-a-operations", a.snapshot().profileId());
        assertEquals("2026.07.1", a.snapshot().version());
        assertEquals(1, a.snapshot().assetTypeCount());
        assertEquals(2, a.snapshot().roleCount());
        assertEquals(a.snapshot().digest(), b.snapshot().digest());
        assertFalse(a.snapshot().digest().isBlank());

        SemanticPropertyRole temp = a.snapshot().rolesForAssetType("StackingRobot").stream()
                .filter(r -> "operating_temperature".equals(r.roleId())).findFirst().orElseThrow();
        assertEquals(SemanticBindingKind.PROPERTY, temp.binding().kind());
        assertEquals("Wrst1", temp.binding().propertyName());
        assertEquals("PT5S", temp.expectedCadence());
        assertEquals("StackingRobot.propertyRole.operating_temperature",
                temp.propertyRoleRef("StackingRobot"));
    }

    @Test
    void template_loads() throws Exception {
        assertTrue(SemanticProfileBuilder.parse(readFixture("semantic-profile.template.json")).valid());
    }

    @Test
    void unknownField_rejected() throws Exception {
        SemanticProfileBuilder.ParseOutcome o = SemanticProfileBuilder.parse(readFixture("invalid-unknown-field.json"));
        assertFalse(o.valid());
        assertTrue(o.diagnostics().stream().anyMatch(d -> d.message().contains("futurePolicy")));
    }

    @Test
    void unitDimensionMismatch_rejected() throws Exception {
        SemanticProfileBuilder.ParseOutcome o = SemanticProfileBuilder.parse(readFixture("invalid-unit-mismatch.json"));
        assertFalse(o.valid());
        assertTrue(o.diagnostics().stream().anyMatch(d -> "SEMANTIC_PROFILE_UNIT".equals(d.code())));
    }

    @Test
    void ambiguousAlias_rejected() throws Exception {
        SemanticProfileBuilder.ParseOutcome o = SemanticProfileBuilder.parse(readFixture("ambiguous-alias.json"));
        assertFalse(o.valid());
        assertTrue(o.diagnostics().stream().anyMatch(d -> "SEMANTIC_PROFILE_AMBIGUOUS".equals(d.code())));
    }

    @Test
    void serviceMissingResultField_rejected() throws Exception {
        SemanticProfileBuilder.ParseOutcome o = SemanticProfileBuilder.parse(readFixture("invalid-service-target.json"));
        assertFalse(o.valid());
        assertTrue(o.diagnostics().stream().anyMatch(d -> "SEMANTIC_PROFILE_TARGET".equals(d.code())));
    }

    @Test
    void wrongSchema_rejected() throws Exception {
        SemanticProfileBuilder.ParseOutcome o = SemanticProfileBuilder.parse(readFixture("invalid-schema.json"));
        assertFalse(o.valid());
        assertTrue(o.diagnostics().stream().anyMatch(d -> d.message().contains("parler-semantic-profile-v1")));
    }

    @Test
    void taxonomyRef_requiredWhenKnownKeysProvided() throws Exception {
        String json = readFixture("stacking-robot.example.json");
        SemanticProfileBuilder.ParseOutcome bad =
                SemanticProfileBuilder.parse(json, Instant.now(), Set.of("OtherType"));
        assertFalse(bad.valid());
        assertTrue(bad.diagnostics().stream().anyMatch(d -> "SEMANTIC_PROFILE_TAXONOMY_REF".equals(d.code())));

        SemanticProfileBuilder.ParseOutcome good =
                SemanticProfileBuilder.parse(json, Instant.now(), Set.of("StackingRobot"));
        assertTrue(good.valid());
    }

    @Test
    void repositoryReader_missingFile_isNotConfigured() {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) {
                return null;
            }
        };
        SemanticProfileSnapshot s = SemanticProfileBuilder.buildFromRepositoryReader(reader, NOPLogger.NOP_LOGGER,
                "Agent", Instant.now());
        assertFalse(s.isLoaded());
        assertEquals("not_configured", s.snapshotStatus());
    }

    @Test
    void repositoryReader_loadsExample() throws Exception {
        String json = readFixture("stacking-robot.example.json");
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) {
                if (ConfigurationRepositoryPaths.SEMANTIC_PROFILE_JSON.equals(path)) {
                    return json;
                }
                return null;
            }
        };
        SemanticProfileSnapshot s = SemanticProfileBuilder.buildFromRepositoryReader(reader, NOPLogger.NOP_LOGGER,
                "Agent", Instant.now(), Set.of("StackingRobot"));
        assertTrue(s.isLoaded());
        assertEquals(ConfigurationRepositoryPaths.SEMANTIC_PROFILE_JSON, s.effectiveSourcePath());
        assertNotNull(s.digest());
    }

    @Test
    void staleFromPrior_retainsValidSnapshot() throws Exception {
        SemanticProfileSnapshot prior = SemanticProfileBuilder.parse(readFixture("stacking-robot.example.json"))
                .snapshot();
        SemanticProfileSnapshot stale = SemanticProfileSnapshot.staleFromPrior(prior, Instant.now(),
                List.of(new SemanticProfileDiagnostic(SemanticProfileDiagnostic.Severity.ERROR,
                        "SEMANTIC_PROFILE_CONFIG_INVALID", "refresh failed")));
        assertTrue(stale.isLoaded());
        assertTrue(stale.isStale());
        assertEquals(prior.digest(), stale.digest());
        assertEquals("stale", stale.snapshotStatus());
        assertNotEquals(prior.digest(), "");
    }

    @Test
    void oversizedFile_rejected() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"schema\":\"parler-semantic-profile-v1\",\"profileId\":\"x\",\"version\":\"1\",\"assetTypes\":{");
        while (sb.length() < SemanticProfileBuilder.MAX_UTF8_BYTES + 10) {
            sb.append(' ');
        }
        sb.append("}}");
        SemanticProfileBuilder.ParseOutcome o = SemanticProfileBuilder.parse(sb.toString());
        assertFalse(o.valid());
        assertTrue(o.diagnostics().stream().anyMatch(d -> d.message().contains("max UTF-8")));
    }
}
