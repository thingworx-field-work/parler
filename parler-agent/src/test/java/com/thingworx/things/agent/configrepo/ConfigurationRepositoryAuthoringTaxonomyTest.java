package com.thingworx.things.agent.configrepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.types.InfoTable;

/** Authoring taxonomy validation mirrors runtime v2/v3 split. */
class ConfigurationRepositoryAuthoringTaxonomyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String readResource(String classpath) throws Exception {
        try (InputStream in = ConfigurationRepositoryAuthoringTaxonomyTest.class.getResourceAsStream(classpath)) {
            Objects.requireNonNull(in, "missing " + classpath);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static RepositoryReader cellfabReader(boolean includeAsset) {
        return new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) throws Exception {
                if (ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON.equals(path)) {
                    return readResource("/taxonomy/cellfab-v3/identity-types.json");
                }
                if (ConfigurationRepositoryPaths.ASSET_TYPES_JSON.equals(path)) {
                    return includeAsset ? readResource("/taxonomy/cellfab-v3/asset-types.json") : null;
                }
                return null;
            }
        };
    }

    @Test
    void v3_cellfab_fixture_valid() throws Exception {
        ObjectNode taxonomy = MAPPER.createObjectNode();
        ArrayNode items = MAPPER.createArrayNode();
        int[] ew = {0, 0};
        ConfigurationRepositoryAuthoringJson.appendStructuredTaxonomyValidation(cellfabReader(true), NOPLogger.NOP_LOGGER,
                "Agent", taxonomy, items, ew);
        assertEquals(0, ew[0]);
        assertEquals(0, ew[1]);
        ObjectNode id = (ObjectNode) taxonomy.get("identityTypesJson");
        assertEquals("loaded", id.get("status").asText());
        assertTrue(id.get("typeCount").asInt() > 0);
    }

    @Test
    void v3_missing_asset_types_warns_identity_still_loaded() throws Exception {
        ObjectNode taxonomy = MAPPER.createObjectNode();
        ArrayNode items = MAPPER.createArrayNode();
        int[] ew = {0, 0};
        ConfigurationRepositoryAuthoringJson.appendStructuredTaxonomyValidation(cellfabReader(false), NOPLogger.NOP_LOGGER,
                "Agent", taxonomy, items, ew);
        assertEquals(0, ew[0], "identity-only v3 must not add blocking repository errors");
        assertTrue(ew[1] >= 1, "expected warning for missing asset-types; warnings=" + ew[1]);
        assertTrue(findItemCode(items, "TAXONOMY_ASSET_TYPES_ABSENT"));
        assertEquals("loaded", taxonomy.get("identityTypesJson").get("status").asText());
        assertEquals("missing", taxonomy.get("assetTypesJson").get("status").asText());
    }

    @Test
    void v3_asset_types_only_fixture_valid() throws Exception {
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
                    return readResource("/taxonomy/cellfab-v3/asset-types.json");
                }
                return null;
            }
        };
        ObjectNode taxonomy = MAPPER.createObjectNode();
        ArrayNode items = MAPPER.createArrayNode();
        int[] ew = {0, 0};
        ConfigurationRepositoryAuthoringJson.appendStructuredTaxonomyValidation(reader, NOPLogger.NOP_LOGGER, "Agent",
                taxonomy, items, ew);
        assertEquals(0, ew[0]);
        assertTrue(ew[1] >= 1, "expected TAXONOMY_IDENTITY_RULES_ABSENT warning");
        assertTrue(findItemCode(items, "TAXONOMY_IDENTITY_RULES_ABSENT"));
        assertEquals("missing", taxonomy.get("identityTypesJson").get("status").asText());
        assertEquals("loaded", taxonomy.get("assetTypesJson").get("status").asText());
        assertTrue(taxonomy.get("assetTypesJson").get("typeCount").asInt() > 0);
    }

    @Test
    void v2_minimal_without_asset_is_loaded() throws Exception {
        String v2 = readResource("/taxonomy/identity-types-minimal.json");
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) throws Exception {
                if (ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON.equals(path)) {
                    return v2;
                }
                return null;
            }
        };
        ObjectNode taxonomy = MAPPER.createObjectNode();
        ArrayNode items = MAPPER.createArrayNode();
        int[] ew = {0, 0};
        ConfigurationRepositoryAuthoringJson.appendStructuredTaxonomyValidation(reader, NOPLogger.NOP_LOGGER, "Agent",
                taxonomy, items, ew);
        assertEquals(0, ew[0]);
        assertEquals("loaded", taxonomy.get("identityTypesJson").get("status").asText());
    }

    @Test
    void v2_minimal_with_nonempty_asset_emits_legacy_warning() throws Exception {
        String v2 = readResource("/taxonomy/identity-types-minimal.json");
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) throws Exception {
                if (ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON.equals(path)) {
                    return v2;
                }
                if (ConfigurationRepositoryPaths.ASSET_TYPES_JSON.equals(path)) {
                    return "{\"dummy\":{}}";
                }
                return null;
            }
        };
        ObjectNode taxonomy = MAPPER.createObjectNode();
        ArrayNode items = MAPPER.createArrayNode();
        int[] ew = {0, 0};
        ConfigurationRepositoryAuthoringJson.appendStructuredTaxonomyValidation(reader, NOPLogger.NOP_LOGGER, "Agent",
                taxonomy, items, ew);
        assertEquals(0, ew[0]);
        assertTrue(ew[1] >= 1, "expected legacy asset-types warning; got warnings=" + ew[1]);
        assertTrue(findItemCode(items, "TAXONOMY_LEGACY_FILE_PRESENT"));
        assertEquals("loaded", taxonomy.get("identityTypesJson").get("status").asText());
    }

    @Test
    void v3_invalid_asset_root_reports_items_under_asset_types_path() throws Exception {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) throws Exception {
                if (ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON.equals(path)) {
                    return readResource("/taxonomy/cellfab-v3/identity-types.json");
                }
                if (ConfigurationRepositoryPaths.ASSET_TYPES_JSON.equals(path)) {
                    return "[]";
                }
                return null;
            }
        };
        ObjectNode taxonomy = MAPPER.createObjectNode();
        ArrayNode items = MAPPER.createArrayNode();
        int[] ew = {0, 0};
        ConfigurationRepositoryAuthoringJson.appendStructuredTaxonomyValidation(reader, NOPLogger.NOP_LOGGER, "Agent",
                taxonomy, items, ew);
        assertEquals(0, ew[0],
                "invalid asset-types root is a companion warning when v3 identity is valid, not a blocking repo error");
        assertTrue(ew[1] >= 1);
        boolean sawAssetPath = false;
        for (JsonNode it : items) {
            if (ConfigurationRepositoryPaths.ASSET_TYPES_JSON.equals(it.path("path").asText())
                    && "TAXONOMY_CONFIG_INVALID".equals(it.path("code").asText())) {
                sawAssetPath = true;
            }
        }
        assertTrue(sawAssetPath, "expected asset-types.json path on diagnostics item: " + items);
    }

    @Test
    void v2_invalid_identity_object_with_valid_v3_assets_summary_has_no_errors() throws Exception {
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
                    return readResource("/taxonomy/cellfab-v3/asset-types.json");
                }
                return null;
            }
        };
        ObjectNode taxonomy = MAPPER.createObjectNode();
        ArrayNode items = MAPPER.createArrayNode();
        int[] ew = {0, 0};
        ConfigurationRepositoryAuthoringJson.appendStructuredTaxonomyValidation(reader, NOPLogger.NOP_LOGGER, "Agent",
                taxonomy, items, ew);
        assertEquals(0, ew[0], "invalid v2 identity + valid v3 asset map must not add blocking repository errors");
        assertTrue(ew[1] >= 1);
        assertEquals("invalid", taxonomy.get("identityTypesJson").get("status").asText());
        assertEquals("loaded", taxonomy.get("assetTypesJson").get("status").asText());
        assertTrue(taxonomy.get("assetTypesJson").get("typeCount").asInt() > 0);
        assertFalse(findItemCode(items, "TAXONOMY_LEGACY_FILE_PRESENT"),
                "salvage path uses asset-types.json; legacy ignored-file warning must not apply");
        for (JsonNode it : items) {
            if (ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON.equals(it.path("path").asText())) {
                assertEquals("warning", it.path("severity").asText(), "identity-side item should downgrade: " + it);
            }
        }
    }

    @Test
    void v2_invalid_identity_with_malformed_asset_surfaces_asset_diagnostics_not_legacy() throws Exception {
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
        ObjectNode taxonomy = MAPPER.createObjectNode();
        ArrayNode items = MAPPER.createArrayNode();
        int[] ew = {0, 0};
        ConfigurationRepositoryAuthoringJson.appendStructuredTaxonomyValidation(reader, NOPLogger.NOP_LOGGER, "Agent",
                taxonomy, items, ew);
        assertTrue(ew[0] >= 1, "expected at least one repository error");
        assertEquals("invalid", taxonomy.get("identityTypesJson").get("status").asText());
        assertEquals("invalid", taxonomy.get("assetTypesJson").get("status").asText());
        assertFalse(findItemCode(items, "TAXONOMY_LEGACY_FILE_PRESENT"));
        boolean sawAssetPathError = false;
        for (JsonNode it : items) {
            if (ConfigurationRepositoryPaths.ASSET_TYPES_JSON.equals(it.path("path").asText())
                    && "error".equals(it.path("severity").asText())
                    && "TAXONOMY_CONFIG_INVALID".equals(it.path("code").asText())) {
                sawAssetPathError = true;
            }
        }
        assertTrue(sawAssetPathError, "expected asset-side error item: " + items);
    }

    @Test
    void v3_invalid_identity_with_valid_assets_summary_has_no_errors() throws Exception {
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
                    return readResource("/taxonomy/cellfab-v3/asset-types.json");
                }
                return null;
            }
        };
        ObjectNode taxonomy = MAPPER.createObjectNode();
        ArrayNode items = MAPPER.createArrayNode();
        int[] ew = {0, 0};
        ConfigurationRepositoryAuthoringJson.appendStructuredTaxonomyValidation(reader, NOPLogger.NOP_LOGGER, "Agent",
                taxonomy, items, ew);
        assertEquals(0, ew[0], "blocking errors when asset-types is usable despite invalid identity");
        assertTrue(ew[1] >= 1);
        assertEquals("invalid", taxonomy.get("identityTypesJson").get("status").asText());
        assertEquals("loaded", taxonomy.get("assetTypesJson").get("status").asText());
        for (JsonNode it : items) {
            if (ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON.equals(it.path("path").asText())) {
                assertEquals("warning", it.path("severity").asText(), "identity-side item should downgrade: " + it);
            }
        }
    }

    private static boolean findItemCode(ArrayNode items, String code) {
        for (JsonNode n : items) {
            if (code.equals(n.get("code").asText())) {
                return true;
            }
        }
        return false;
    }
}
