package com.thingworx.things.agent.taxonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import com.thingworx.things.agent.configrepo.ConfigurationRepositoryPaths;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.things.agent.taxonomy.TaxonomyDiagnostic;
import com.thingworx.types.InfoTable;

class ApplicationSemanticTaxonomyBuilderTest {

    private static final String V3_IDENTITY_ONE_RULE = "[{\"baseThingTemplate\":\"PTC.MfgModel.DefaultWorkunit_TT\","
            + "\"identityProperties\":[\"name\"],\"criticalProperties\":[]}]";

    private static String readClasspath(String path) throws Exception {
        try (InputStream in = ApplicationSemanticTaxonomyBuilderTest.class.getResourceAsStream(path)) {
            Objects.requireNonNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void v3_identity_loads_when_asset_types_file_missing() throws Exception {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) throws Exception {
                if (ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON.equals(path)) {
                    return V3_IDENTITY_ONE_RULE;
                }
                if (ConfigurationRepositoryPaths.ASSET_TYPES_JSON.equals(path)) {
                    return null;
                }
                return null;
            }
        };
        ApplicationSemanticTaxonomySnapshot s =
                ApplicationSemanticTaxonomyBuilder.buildFromRepositoryReader(reader, NOPLogger.NOP_LOGGER, "A",
                        Instant.now());
        assertTrue(s.isLoaded());
        assertEquals(1, s.thingIdentityRules().size());
        assertEquals(0, s.assetTypeCount());
        assertEquals("loaded", s.snapshotStatus());
        assertTrue(s.diagnostics().stream().anyMatch(d -> "TAXONOMY_ASSET_TYPES_ABSENT".equals(d.code())));
    }

    @Test
    void v3_identity_loads_when_asset_types_empty_object() throws Exception {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) throws Exception {
                if (ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON.equals(path)) {
                    return V3_IDENTITY_ONE_RULE;
                }
                if (ConfigurationRepositoryPaths.ASSET_TYPES_JSON.equals(path)) {
                    return "{}";
                }
                return null;
            }
        };
        ApplicationSemanticTaxonomySnapshot s =
                ApplicationSemanticTaxonomyBuilder.buildFromRepositoryReader(reader, NOPLogger.NOP_LOGGER, "A",
                        Instant.now());
        assertTrue(s.isLoaded());
        assertEquals(0, s.assetTypeCount());
        assertTrue(s.diagnostics().stream().anyMatch(d -> "TAXONOMY_ASSET_TYPES_EMPTY".equals(d.code())));
    }

    @Test
    void v3_asset_types_only_loads_when_identity_missing() throws Exception {
        String assets = readClasspath("/taxonomy/cellfab-v3/asset-types.json");
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) throws Exception {
                if (ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON.equals(path)) {
                    return null;
                }
                if (ConfigurationRepositoryPaths.ASSET_TYPES_JSON.equals(path)) {
                    return assets;
                }
                return null;
            }
        };
        ApplicationSemanticTaxonomySnapshot s =
                ApplicationSemanticTaxonomyBuilder.buildFromRepositoryReader(reader, NOPLogger.NOP_LOGGER, "A",
                        Instant.now());
        assertTrue(s.isLoaded());
        assertTrue(s.assetTypeCount() > 0);
        assertTrue(s.thingIdentityRules().isEmpty());
        assertEquals(ConfigurationRepositoryPaths.ASSET_TYPES_JSON, s.effectiveSourcePath());
        assertTrue(s.diagnostics().stream().anyMatch(d -> "TAXONOMY_IDENTITY_RULES_ABSENT".equals(d.code())));
    }

    @Test
    void v3_invalid_identity_empty_array_still_loads_valid_asset_types() throws Exception {
        String assets = readClasspath("/taxonomy/cellfab-v3/asset-types.json");
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) throws Exception {
                if (ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON.equals(path)) {
                    return "[]";
                }
                if (ConfigurationRepositoryPaths.ASSET_TYPES_JSON.equals(path)) {
                    return assets;
                }
                return null;
            }
        };
        ApplicationSemanticTaxonomySnapshot s =
                ApplicationSemanticTaxonomyBuilder.buildFromRepositoryReader(reader, NOPLogger.NOP_LOGGER, "A",
                        Instant.now());
        assertTrue(s.isLoaded());
        assertTrue(s.thingIdentityRules().isEmpty());
        assertTrue(s.assetTypeCount() > 0);
        assertEquals(ConfigurationRepositoryPaths.ASSET_TYPES_JSON, s.effectiveSourcePath());
        assertFalse(s.diagnostics().stream()
                .anyMatch(d -> d.severity() == TaxonomyDiagnostic.Severity.ERROR));
    }

    @Test
    void v2_invalid_identity_still_loads_valid_v3_asset_types() throws Exception {
        String assets = readClasspath("/taxonomy/cellfab-v3/asset-types.json");
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) throws Exception {
                if (ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON.equals(path)) {
                    return "{\"version\": 2}";
                }
                if (ConfigurationRepositoryPaths.ASSET_TYPES_JSON.equals(path)) {
                    return assets;
                }
                return null;
            }
        };
        ApplicationSemanticTaxonomySnapshot s =
                ApplicationSemanticTaxonomyBuilder.buildFromRepositoryReader(reader, NOPLogger.NOP_LOGGER, "A",
                        Instant.now());
        assertTrue(s.isLoaded());
        assertTrue(s.assetTypeCount() > 0);
        assertTrue(s.thingIdentityRules().isEmpty());
        assertFalse(s.diagnostics().stream()
                .anyMatch(d -> d.severity() == TaxonomyDiagnostic.Severity.ERROR));
        assertFalse(s.diagnostics().stream().anyMatch(d -> "TAXONOMY_LEGACY_FILE_PRESENT".equals(d.code())),
                "asset-types.json is the loaded taxonomy source; legacy ignored-file warning must not apply");
    }

    @Test
    void v2_invalid_identity_with_malformed_asset_unavailable_without_legacy_diagnostic() throws Exception {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) throws Exception {
                if (ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON.equals(path)) {
                    return "{\"version\": 2}";
                }
                if (ConfigurationRepositoryPaths.ASSET_TYPES_JSON.equals(path)) {
                    return "{ not json";
                }
                return null;
            }
        };
        ApplicationSemanticTaxonomySnapshot s =
                ApplicationSemanticTaxonomyBuilder.buildFromRepositoryReader(reader, NOPLogger.NOP_LOGGER, "A",
                        Instant.now());
        assertFalse(s.isLoaded());
        assertFalse(s.diagnostics().stream().anyMatch(d -> "TAXONOMY_LEGACY_FILE_PRESENT".equals(d.code())));
        assertTrue(s.diagnostics().stream().anyMatch(d -> "TAXONOMY_CONFIG_INVALID".equals(d.code())
                && d.message() != null && d.message().contains("Invalid asset-types.json JSON")),
                "expected asset parse diagnostic: " + s.diagnostics());
    }

    @Test
    void no_identity_asset_types_root_array_is_unavailable() throws Exception {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) throws Exception {
                if (ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON.equals(path)) {
                    return null;
                }
                if (ConfigurationRepositoryPaths.ASSET_TYPES_JSON.equals(path)) {
                    return "[]";
                }
                return null;
            }
        };
        ApplicationSemanticTaxonomySnapshot s =
                ApplicationSemanticTaxonomyBuilder.buildFromRepositoryReader(reader, NOPLogger.NOP_LOGGER, "A",
                        Instant.now());
        assertFalse(s.isLoaded());
        assertTrue(s.diagnostics().stream().anyMatch(d -> "TAXONOMY_CONFIG_INVALID".equals(d.code())));
    }

    @Test
    void no_identity_malformed_asset_json_is_unavailable() throws Exception {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) throws Exception {
                if (ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON.equals(path)) {
                    return null;
                }
                if (ConfigurationRepositoryPaths.ASSET_TYPES_JSON.equals(path)) {
                    return "{ not json";
                }
                return null;
            }
        };
        ApplicationSemanticTaxonomySnapshot s =
                ApplicationSemanticTaxonomyBuilder.buildFromRepositoryReader(reader, NOPLogger.NOP_LOGGER, "A",
                        Instant.now());
        assertFalse(s.isLoaded());
        assertTrue(s.diagnostics().stream().anyMatch(d -> "TAXONOMY_CONFIG_INVALID".equals(d.code())));
    }

    @Test
    void v3_identity_valid_asset_root_array_companion_has_no_error_severity() throws Exception {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) throws Exception {
                if (ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON.equals(path)) {
                    return V3_IDENTITY_ONE_RULE;
                }
                if (ConfigurationRepositoryPaths.ASSET_TYPES_JSON.equals(path)) {
                    return "[]";
                }
                return null;
            }
        };
        ApplicationSemanticTaxonomySnapshot s =
                ApplicationSemanticTaxonomyBuilder.buildFromRepositoryReader(reader, NOPLogger.NOP_LOGGER, "A",
                        Instant.now());
        assertTrue(s.isLoaded());
        assertFalse(s.diagnostics().stream()
                .anyMatch(d -> d.severity() == TaxonomyDiagnostic.Severity.ERROR));
    }
}
