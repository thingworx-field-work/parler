package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.cache.ArtifactCacheException;
import com.thingworx.things.agent.cache.ArtifactCacheFaultCode;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.InfoTablePrimitive;
import com.thingworx.types.primitives.StringPrimitive;

class ExtractNestedCachedResultTest {

    ExtractNestedCachedResultTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void extractNestedPromotesWithLineageAndAccounting() throws Exception {
        AgentToolContext.setConversationId("extract-nested-1");
        String parentId = InvokeServiceExecutor.storeInfotableInConversationCache(parentWithNested());
        String json = ExtractNestedCachedResult.execute(parentId, "[0].children");
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.path("status").asText(), json);
        assertEquals(parentId, root.path("sourceCacheId").asText());
        assertTrue(root.path("cacheId").asText().length() > 0);
        assertEquals("[0].children", root.path("cellPath").asText());
        assertEquals(1, root.path("rowsReturned").asInt());
        assertTrue(root.path("utf16Chars").asInt() > 0);
        assertTrue(root.path("utf8Bytes").asInt() > 0);
        assertTrue(root.has("sizeClass"));

        SourceDescriptor derived = TabularArtifactHub.lookupDescriptor(root.path("cacheId").asText());
        assertNotNull(derived);
        assertTrue(derived.parentSourceCacheIds().contains(parentId));
        assertTrue(derived.sourceRouteId().startsWith("extract_nested:"));

        InfoTable promoted = TabularArtifactHub.lookup(root.path("cacheId").asText());
        assertNotNull(promoted);
        assertEquals("inner", promoted.getRow(0).getStringValue("name"));
    }

    @Test
    void parentWithPasswordNestedRejectedAtStore() {
        AgentToolContext.setConversationId("extract-nested-pw2");
        ArtifactCacheException ex = assertThrows(ArtifactCacheException.class,
                () -> InvokeServiceExecutor.storeInfotableInConversationCache(parentWithPasswordNested()));
        assertEquals(ArtifactCacheFaultCode.PASSWORD_REJECTED, ex.code());
    }

    @Test
    void maliciousPathRejected() throws Exception {
        AgentToolContext.setConversationId("extract-nested-bad");
        String parentId = InvokeServiceExecutor.storeInfotableInConversationCache(parentWithNested());
        JsonNode root = MAPPER.readTree(ExtractNestedCachedResult.execute(parentId, "children..x"));
        assertEquals("error", root.path("status").asText());
        assertEquals("INVALID_PATH", root.path("code").asText());
    }

    private static InfoTable parentWithNested() throws Exception {
        DataShapeDefinition nestedShape = new DataShapeDefinition();
        FieldDefinition name = new FieldDefinition();
        name.setName("name");
        name.setBaseType(BaseTypes.STRING);
        nestedShape.addFieldDefinition(name);
        InfoTable nested = new InfoTable(nestedShape);
        ValueCollection nr = new ValueCollection();
        nr.put("name", new StringPrimitive("inner"));
        nested.addRow(nr);

        DataShapeDefinition parentShape = new DataShapeDefinition();
        FieldDefinition children = new FieldDefinition();
        children.setName("children");
        children.setBaseType(BaseTypes.INFOTABLE);
        children.setLocalDataShape(nestedShape);
        parentShape.addFieldDefinition(children);
        InfoTable parent = new InfoTable(parentShape);
        ValueCollection pr = new ValueCollection();
        pr.put("children", new InfoTablePrimitive(nested));
        parent.addRow(pr);
        return parent;
    }

    private static InfoTable parentWithPasswordNested() {
        DataShapeDefinition nestedShape = new DataShapeDefinition();
        FieldDefinition secret = new FieldDefinition();
        secret.setName("secret");
        secret.setBaseType(BaseTypes.PASSWORD);
        nestedShape.addFieldDefinition(secret);

        DataShapeDefinition parentShape = new DataShapeDefinition();
        FieldDefinition children = new FieldDefinition();
        children.setName("children");
        children.setBaseType(BaseTypes.INFOTABLE);
        children.setLocalDataShape(nestedShape);
        parentShape.addFieldDefinition(children);
        return new InfoTable(parentShape);
    }
}
