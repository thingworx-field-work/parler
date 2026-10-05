package com.thingworx.things.agent.taxonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.configrepo.ConfigurationRepositoryPaths;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.types.InfoTable;

/** Bug 012: identity-only vs asset-only semantic taxonomy surfaces for resolver tools. */
class TaxonomyResolverExecutorSemanticsTest {

    @AfterEach
    void tearDown() {
        AgentToolContext.clear();
    }

    @Test
    void list_asset_types_identity_only_returns_asset_types_not_configured() throws Exception {
        ThingIdentityRuleV3 rule = new ThingIdentityRuleV3("T.Template",
                List.of(new ThingIdentityPropertyMatchV3("name", "equals")), List.of());
        ApplicationSemanticTaxonomySnapshot sem = ApplicationSemanticTaxonomySnapshot.loaded(List.of(), List.of(rule),
                List.of(), false, Instant.now(), ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON);
        String json = TaxonomyResolverExecutor.listAssetTypesForSemantics(sem);
        assertTrue(json.contains("ASSET_TYPES_NOT_CONFIGURED"));
    }

    @Test
    void resolve_thing_identity_only_with_asset_type_key_returns_asset_type_not_found() throws Exception {
        ThingIdentityRuleV3 rule = new ThingIdentityRuleV3("T.Template",
                List.of(new ThingIdentityPropertyMatchV3("name", "equals")), List.of());
        ApplicationSemanticTaxonomySnapshot sem = ApplicationSemanticTaxonomySnapshot.loaded(List.of(), List.of(rule),
                List.of(), false, Instant.now(), ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON);
        ToolCall call = new ToolCall("1", "resolve_thing", "{\"text\":\"any\",\"assetTypeKey\":\"SomeKey\"}");
        String json = TaxonomyResolverExecutor.resolveThingForSemantics(call, sem);
        assertTrue(json.contains("ASSET_TYPE_NOT_FOUND"));
    }

    @Test
    void resolve_thing_asset_types_only_returns_taxonomy_unavailable() throws Exception {
        ApplicationSemanticTaxonomySnapshot sem = assetTypesOnlyCellfabSnapshot();
        ToolCall call = new ToolCall("1", "resolve_thing", "{\"text\":\"ORD-01\"}");
        String json = TaxonomyResolverExecutor.resolveThingForSemantics(call, sem);
        assertTrue(json.contains("TAXONOMY_UNAVAILABLE"));
    }

    @Test
    void list_asset_types_asset_types_only_returns_success() throws Exception {
        ApplicationSemanticTaxonomySnapshot sem = assetTypesOnlyCellfabSnapshot();
        String json = TaxonomyResolverExecutor.listAssetTypesForSemantics(sem);
        assertTrue(json.contains("\"status\":\"success\""));
        assertTrue(json.contains("ASSET_TYPES_INLINE"));
    }

    @Test
    void list_asset_types_maxItems_truncatesWithHasMore() throws Exception {
        ApplicationSemanticTaxonomySnapshot sem = assetTypesOnlyCellfabSnapshot();
        String json = TaxonomyResolverExecutor.listAssetTypesForSemantics(sem, 1);
        assertTrue(json.contains("ASSET_TYPES_LARGE") || json.contains("hasMore"), json);
        assertTrue(json.contains("\"hasMore\":true") || json.contains("\"hasMore\": true"), json);
        assertTrue(json.contains("\"returnedRows\":1") || json.contains("\"returnedRows\": 1"), json);
    }

    @Test
    void list_asset_types_omittedMaxItems_stillCapsAtDefault500() throws Exception {
        // B12 redirect fix: default path must bound even when maxItems is omitted.
        List<AssetTypeEntry> many = new ArrayList<>();
        TaxonomyQueryParent qp = new TaxonomyQueryParent("ThingTemplate", "Synthetic.TT", "");
        for (int i = 0; i < 501; i++) {
            many.add(new AssetTypeEntry("Entity" + i, List.of(), "type", "Key" + i, List.of(),
                    "ThingTemplate", "Synthetic.TT", List.of(), List.of(), List.of(), qp));
        }
        ApplicationSemanticTaxonomySnapshot sem = ApplicationSemanticTaxonomySnapshot.loaded(many, List.of(),
                List.of(), false, Instant.now(), ConfigurationRepositoryPaths.ASSET_TYPES_JSON);
        JsonNode root = new ObjectMapper().readTree(TaxonomyResolverExecutor.listAssetTypesForSemantics(sem, null));
        assertEquals("ASSET_TYPES_LARGE", root.path("resultKind").asText());
        assertEquals(501, root.path("totalCount").asInt());
        assertEquals(500, root.path("returnedRows").asInt());
        assertEquals(500, root.path("assetTypes").size());
        assertTrue(root.path("hasMore").asBoolean());
        assertEquals(TaxonomyResolverExecutor.LIST_ASSET_TYPES_DEFAULT_MAX_ITEMS,
                root.path("maxItemsEffective").asInt());
        assertFalse(root.path("returnedRows").asInt() > 500);
    }

    @Test
    void resolve_asset_type_blankText_isInvalidParameters() throws Exception {
        ApplicationSemanticTaxonomySnapshot sem = assetTypesOnlyCellfabSnapshot();
        ToolCall call = new ToolCall("1", "resolve_asset_type", "{\"text\":\"  \"}");
        String json = TaxonomyResolverExecutor.resolveAssetTypeForSemantics(call, sem);
        assertTrue(json.contains("INVALID_PARAMETERS"), json);
    }

    @Test
    void resolve_asset_type_asset_types_only_can_match_alias() throws Exception {
        ApplicationSemanticTaxonomySnapshot sem = assetTypesOnlyCellfabSnapshot();
        ToolCall call = new ToolCall("1", "resolve_asset_type", "{\"text\":\"sealer\"}");
        String json = TaxonomyResolverExecutor.resolveAssetTypeForSemantics(call, sem);
        assertTrue(json.contains("\"status\":\"success\""));
        assertTrue(json.contains("\"key\":\"Sealing\"") || json.contains("\"key\": \"Sealing\""));
    }

    private static ApplicationSemanticTaxonomySnapshot assetTypesOnlyCellfabSnapshot() throws Exception {
        String assets;
        try (InputStream in = TaxonomyResolverExecutorSemanticsTest.class
                .getResourceAsStream("/taxonomy/cellfab-v3/asset-types.json")) {
            Objects.requireNonNull(in);
            assets = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
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
        return ApplicationSemanticTaxonomyBuilder.buildFromRepositoryReader(reader, NOPLogger.NOP_LOGGER, "Agent",
                Instant.now());
    }
}
